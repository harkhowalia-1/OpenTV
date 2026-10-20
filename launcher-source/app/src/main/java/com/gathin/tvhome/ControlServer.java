package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The remote apps' live connection (TCP port 8801). Each paired iPhone, iPad or Mac keeps one open, so a
 * button press is a few bytes on an open socket instead of a new HTTP request, any number of devices can be
 * connected at once, and the TV can push things back (what's playing, the pointer leaving the screen).
 * Clients reconnect on their own when the TV sleeps, restarts or changes address.
 *
 * <p>Frames are {@code u16 length, u8 type, payload}; the client's first frame is HELLO with its token.
 */
final class ControlServer {

    static final int PORT = 8801;
    private static final String TAG = "TvControl";

    // client -> TV
    static final int HELLO = 0x01, KEY = 0x02, TEXT = 0x03, POINTER_ENTER = 0x04, POINTER_MOVE = 0x05,
            POINTER_BUTTON = 0x06, POINTER_SCROLL = 0x07, POINTER_EXIT = 0x08, TYPE = 0x0A, PING = 0x0F;
    // TV -> client
    static final int WELCOME = 0x81, DENIED = 0x82, STATE = 0x83, POINTER_LEFT = 0x84, PONG = 0x8F;

    /** What the connection can do on the TV; implemented by HomeKeyService. */
    interface Actions {
        void key(String name);

        void text(String value);

        void type(String chars);

        /** The Mac's cursor came onto the TV at this point along the entry edge (0..1). */
        void pointerEnter(Client from, float along, String edge);

        void pointerMove(int dx, int dy);

        void pointerButton(int button, boolean down);

        /** Scrolling; phase is PointerOverlay.SCROLL_WHEEL, _BEGAN, _CHANGED or _ENDED. */
        void pointerScroll(int dx, int dy, int phase);

        void pointerExit();

        /** A device disconnected; drop the pointer if it was that device's. */
        void clientGone(Client c);
    }

    private final Context ctx;
    private final Actions actions;
    private final SharedPreferences prefs;
    private final Set<Client> clients = new CopyOnWriteArraySet<>();
    private ServerSocket server;
    private volatile boolean running;
    static volatile ControlServer current;

    ControlServer(Context ctx, Actions actions) {
        this.ctx = ctx.getApplicationContext();
        this.actions = actions;
        this.prefs = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    void start() {
        if (running) return;
        running = true;
        current = this;
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                while (running) {
                    Socket s = server.accept();
                    new Client(s).start();
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "control server stopped", e);
            }
        }, "tv-control-server");
        t.setDaemon(true);
        t.start();
        Thread push = new Thread(() -> {
            String last = "";
            while (running) {
                try {
                    Thread.sleep(1000);
                    if (clients.isEmpty()) continue;
                    String now = RemoteServer.nowPlaying(ctx).toString();
                    if (!now.equals(last)) {
                        last = now;
                        broadcastState(new JSONObject(now));
                    }
                } catch (Exception ignored) {
                }
            }
        }, "tv-control-state");
        push.setDaemon(true);
        push.start();
    }

    void stop() {
        running = false;
        if (current == this) current = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (Client c : clients) c.close();
    }

    int connectedCount() {
        return clients.size();
    }

    /** Pushes the now-playing state to every connected device. */
    void broadcastState(JSONObject state) {
        byte[] b = state.toString().getBytes(StandardCharsets.UTF_8);
        for (Client c : clients) c.send(STATE, b);
    }

    private boolean authorized(String token) {
        Set<String> tokens = prefs.getStringSet("tokens", new HashSet<>());
        return token != null && tokens.contains(token);
    }

    /** One connected device. */
    final class Client extends Thread {
        private final Socket sock;
        private DataOutputStream out;
        final String address;

        Client(Socket s) {
            super("tv-control-client");
            setDaemon(true);
            sock = s;
            address = String.valueOf(s.getInetAddress());
        }

        @Override
        public void run() {
            try {
                sock.setTcpNoDelay(true);
                sock.setKeepAlive(true);
                sock.setSoTimeout(30_000);   // clients ping every 10 s
                DataInputStream in = new DataInputStream(sock.getInputStream());
                out = new DataOutputStream(sock.getOutputStream());
                if (!authorized(readFrame(in, HELLO))) {
                    send(DENIED, new byte[0]);
                    return;
                }
                clients.add(this);
                JSONObject hello = new JSONObject();
                hello.put("name", MainActivity.tvName(ctx));
                hello.put("id", RemoteServer.tvId(ctx));
                send(WELCOME, hello.toString().getBytes(StandardCharsets.UTF_8));
                while (running) {
                    int len = in.readUnsignedShort();
                    int type = in.readUnsignedByte();
                    byte[] p = new byte[len];
                    in.readFully(p);
                    handle(type, p);
                }
            } catch (Exception e) {
                // Disconnected; the client reconnects by itself.
            } finally {
                clients.remove(this);
                actions.clientGone(this);
                close();
            }
        }

        private String readFrame(DataInputStream in, int expect) throws IOException {
            int len = in.readUnsignedShort();
            int type = in.readUnsignedByte();
            byte[] p = new byte[len];
            in.readFully(p);
            return type == expect ? new String(p, StandardCharsets.UTF_8) : null;
        }

        private void handle(int type, byte[] p) {
            switch (type) {
                case KEY: actions.key(new String(p, StandardCharsets.UTF_8)); break;
                case TEXT: actions.text(new String(p, StandardCharsets.UTF_8)); break;
                case TYPE: actions.type(new String(p, StandardCharsets.UTF_8)); break;
                case POINTER_ENTER: {
                    float along = u16(p, 0) / 65535f;
                    String edge = p.length > 2 ? new String(p, 2, p.length - 2, StandardCharsets.UTF_8) : "bottom";
                    actions.pointerEnter(this, along, edge);
                    break;
                }
                case POINTER_MOVE: actions.pointerMove(s16(p, 0), s16(p, 2)); break;
                case POINTER_BUTTON: actions.pointerButton(p[0], p[1] != 0); break;
                case POINTER_SCROLL: actions.pointerScroll(s16(p, 0), s16(p, 2), p.length > 4 ? p[4] & 0xFF : 0); break;
                case POINTER_EXIT: actions.pointerExit(); break;
                case PING: send(PONG, new byte[0]); break;
                default: break;
            }
        }

        /** Tells this device its cursor left the TV, at this point along the edge it came in by. */
        void pointerLeft(float along) {
            int v = Math.round(Math.max(0, Math.min(1, along)) * 65535);
            send(POINTER_LEFT, new byte[]{(byte) (v >> 8), (byte) v});
        }

        /** Writes go out on this device's own thread: callers include the UI thread (the pointer leaving). */
        private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();

        void send(int type, byte[] payload) {
            if (out == null) return;
            try {
                writer.execute(() -> {
                    try {
                        out.writeShort(payload.length);
                        out.writeByte(type);
                        out.write(payload);
                        out.flush();
                    } catch (IOException e) {
                        close();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Already closed.
            }
        }

        void close() {
            writer.shutdown();
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int s16(byte[] b, int i) {
        return (short) u16(b, i);
    }
}
