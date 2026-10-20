package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/**
 * The TV end of "Use TV as Display" from the Mac (TCP port 8802): the Mac streams a screen (an extra
 * display, a mirror of its own, or one window such as iPhone Mirroring) as H.264/HEVC made by its media
 * engine, and this decodes it in hardware straight onto a full-screen surface ({@link ScreenActivity}), with
 * the Mac's sound. The TV remote's buttons go back to the Mac while it is showing. Home ends it.
 *
 * <p>Frames: {@code u8 type, u32 length, payload}. The Mac's first frame is HELLO with its pairing token; the
 * TV answers with its real display size and refresh rate, so the Mac renders exactly that.
 */
final class ScreenReceiver {

    static final int PORT = 8802;
    private static final String TAG = "TvScreen";

    // Mac -> TV
    static final int HELLO = 0x01, VIDEO_CONFIG = 0x10, VIDEO_FRAME = 0x11, AUDIO_CONFIG = 0x20, AUDIO_PCM = 0x21,
            CURSOR_POSITION = 0x30, CURSOR_IMAGE = 0x31, BYE = 0x40;
    // TV -> Mac
    static final int WELCOME = 0x81, DENIED = 0x82, KEY = 0x50, NEED_KEYFRAME = 0x51, STOPPED = 0x52;

    static volatile ScreenReceiver current;

    private final Context ctx;
    private final SharedPreferences prefs;
    private ServerSocket server;
    private volatile boolean running;
    private volatile Session session;
    private volatile Surface surface;

    ScreenReceiver(Context ctx) {
        this.ctx = ctx.getApplicationContext();
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
                    Session old = session;
                    if (old != null) old.close();   // a new stream replaces the old one
                    Session n = new Session(s);
                    session = n;
                    n.start();
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "screen server stopped", e);
            }
        }, "tv-screen-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        if (current == this) current = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        Session s = session;
        if (s != null) s.close();
    }

    boolean streaming() {
        Session s = session;
        return s != null && s.welcomed;
    }

    // ---- from ScreenActivity ------------------------------------------------------------------------

    void attach(Surface s) {
        surface = s;
        Session cur = session;
        if (cur != null) cur.surfaceChanged();
    }

    void detach() {
        surface = null;
        Session cur = session;
        if (cur != null) cur.surfaceChanged();
    }

    /** A remote button while the Mac's screen is showing. */
    void sendKey(String name) {
        Session s = session;
        if (s != null) s.send(KEY, name.getBytes(StandardCharsets.UTF_8));
    }

    /** The viewer was closed on the TV (Home): end the stream. */
    void endFromTv() {
        Session s = session;
        if (s != null) {
            s.send(STOPPED, new byte[0]);
            s.close();
        }
    }

    /** The display's real mode, so the Mac streams exactly that. */
    private JSONObject displayInfo() throws Exception {
        Display d = ctx.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Display.Mode m = d.getMode();
        JSONObject o = new JSONObject();
        o.put("name", MainActivity.tvName(ctx));
        o.put("width", m.getPhysicalWidth());
        o.put("height", m.getPhysicalHeight());
        o.put("refresh", Math.round(m.getRefreshRate()));
        JSONArray codecs = new JSONArray();
        if (hasDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) codecs.put("hevc");
        codecs.put("avc");
        o.put("codecs", codecs);
        o.put("cursor", true);   // draws the Mac's cursor itself (CURSOR_POSITION / CURSOR_IMAGE)
        return o;
    }

    private static boolean hasDecoder(String mime) {
        for (MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder() || info.isSoftwareOnly()) continue;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    /** One Mac streaming to the TV. */
    private final class Session extends Thread {
        private final Socket sock;
        private DataOutputStream out;
        volatile boolean welcomed;
        private volatile MediaCodec decoder;
        private String mime;
        private byte[] csd;
        private int width, height;
        private boolean needKeyframe = true;
        private AudioTrack audio;
        private Thread drain;
        /** Keeps the Wi-Fi radio out of power saving while streaming (the same lock Moonlight takes). */
        private android.net.wifi.WifiManager.WifiLock wifiLock;
        /** When each picture went into the decoder, to log how long decoding takes (logcat "TvScreen"). */
        private final java.util.concurrent.ConcurrentHashMap<Long, Long> queuedAt = new java.util.concurrent.ConcurrentHashMap<>();
        private long latencySum, latencyCount, outputs, matched;

        Session(Socket s) {
            super("tv-screen-session");
            setDaemon(true);
            sock = s;
        }

        @Override
        public void run() {
            try {
                sock.setTcpNoDelay(true);
                sock.setTrafficClass(0xA0);   // Wi-Fi "video" priority for what goes back to the Mac
                takeWifiLock();
                DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(sock.getInputStream(), 1 << 16));
                out = new DataOutputStream(sock.getOutputStream());
                int type = in.readUnsignedByte();
                byte[] token = new byte[in.readInt()];
                in.readFully(token);
                if (type != HELLO || !prefs.getStringSet("tokens", new HashSet<>()).contains(new String(token, StandardCharsets.UTF_8))) {
                    send(DENIED, new byte[0]);
                    return;
                }
                send(WELCOME, displayInfo().toString().getBytes(StandardCharsets.UTF_8));
                welcomed = true;
                byte[] buf = new byte[1 << 20];
                while (!sock.isClosed()) {
                    type = in.readUnsignedByte();
                    int len = in.readInt();
                    if (len > buf.length) buf = new byte[len];
                    in.readFully(buf, 0, len);
                    switch (type) {
                        case VIDEO_CONFIG: videoConfig(buf, len); break;
                        case VIDEO_FRAME: videoFrame(buf, len); break;
                        case AUDIO_CONFIG: audioConfig(buf); break;
                        case AUDIO_PCM: if (audio != null) audio.write(buf, 0, len, AudioTrack.WRITE_NON_BLOCKING); break;
                        case CURSOR_POSITION: cursorPosition(buf); break;
                        case CURSOR_IMAGE: cursorImage(buf, len); break;
                        case BYE: return;
                        default: break;
                    }
                }
            } catch (Exception e) {
                // Stream ended.
            } finally {
                close();
                if (session == this) {
                    session = null;
                    ScreenActivity.finishCurrent();
                }
            }
        }

        /** Codec, size and the parameter sets (SPS/PPS, plus VPS for HEVC) in Annex-B form. */
        private void videoConfig(byte[] b, int len) {
            ByteBuffer bb = ByteBuffer.wrap(b, 0, len);
            String m = bb.get() == 1 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            int w = bb.getShort() & 0xFFFF, h = bb.getShort() & 0xFFFF;
            byte[] c = new byte[bb.remaining()];
            bb.get(c);
            // Keyframes repeat the same parameter sets; only a real change needs a new decoder.
            if (decoder != null && m.equals(mime) && w == width && h == height && java.util.Arrays.equals(c, csd)) return;
            boolean first = mime == null;
            mime = m;
            width = w;
            height = h;
            csd = c;
            if (first) {
                // The Mac's first picture is on its way: now take over the screen.
                ctx.startActivity(new Intent(ctx, ScreenActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            restartDecoder();
        }

        void surfaceChanged() {
            restartDecoder();
        }

        private synchronized void restartDecoder() {
            releaseDecoder();
            Surface s = surface;
            if (s == null || mime == null) return;
            try {
                MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
                f.setInteger(MediaFormat.KEY_PRIORITY, 0);   // realtime
                f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                f.setInteger("vendor.low-latency.enable", 1);
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(f, s, null, 0);
                decoder.start();
                Log.i(TAG, "decoder " + decoder.getName() + " started for " + width + "x" + height);
                needKeyframe = true;
                send(NEED_KEYFRAME, new byte[0]);
                MediaCodec d = decoder;
                drain = new Thread(() -> drainLoop(d), "tv-screen-render");
                drain.setDaemon(true);
                drain.start();
            } catch (Exception e) {
                Log.w(TAG, "decoder failed", e);
                releaseDecoder();
            }
        }

        private synchronized void releaseDecoder() {
            MediaCodec d = decoder;
            decoder = null;
            if (d != null) {
                try {
                    d.stop();
                } catch (Exception ignored) {
                }
                d.release();
            }
        }

        /** Shows each picture the moment it's decoded: no frame pacing, which is where latency hides. */
        private void drainLoop(MediaCodec d) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (decoder == d) {
                try {
                    int idx = d.dequeueOutputBuffer(info, 20_000);
                    if (idx >= 0) {
                        d.releaseOutputBuffer(idx, true);
                        Long t = queuedAt.remove(info.presentationTimeUs);
                        if (++outputs % 120 == 0) Log.i(TAG, "shown " + outputs + " pictures, timing matched " + matched);
                        if (t != null) {
                            matched++;
                            latencySum += System.nanoTime() - t;
                            if (++latencyCount == 120) {
                                Log.i(TAG, String.format(java.util.Locale.ROOT, "decode-to-screen %.1f ms average",
                                        latencySum / 120 / 1e6));
                                latencySum = latencyCount = 0;
                            }
                        }
                    }
                } catch (Exception e) {
                    if (decoder == d) Log.w(TAG, "render loop ended", e);
                    return;
                }
            }
        }

        private void videoFrame(byte[] b, int len) {
            MediaCodec d = decoder;
            if (d == null) return;
            boolean key = b[0] != 0;
            long pts = ByteBuffer.wrap(b, 1, 8).getLong();
            if (needKeyframe && !key) return;   // can't start from the middle of a picture sequence
            needKeyframe = false;
            try {
                int idx = d.dequeueInputBuffer(30_000);
                if (idx < 0) {
                    // The decoder is behind: drop this one and restart cleanly at the next keyframe.
                    needKeyframe = true;
                    send(NEED_KEYFRAME, new byte[0]);
                    return;
                }
                ByteBuffer in = d.getInputBuffer(idx);
                in.clear();
                in.put(b, 9, len - 9);
                if (queuedAt.size() > 240) queuedAt.clear();
                queuedAt.put(pts, System.nanoTime());
                d.queueInputBuffer(idx, 0, len - 9, pts, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            } catch (Exception e) {
                Log.w(TAG, "queue failed", e);
                needKeyframe = true;
            }
        }

        /** The Mac's cursor, drawn by the TV over the picture so it never waits for video. */
        private void cursorPosition(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            int x = bb.getShort(), y = bb.getShort();
            boolean visible = bb.get() != 0;
            ScreenActivity.cursorMoved(x, y, visible, width, height);
        }

        private void cursorImage(byte[] b, int len) {
            ByteBuffer bb = ByteBuffer.wrap(b, 0, len);
            int hx = bb.getShort() & 0xFFFF, hy = bb.getShort() & 0xFFFF;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(b, 4, len - 4);
            if (bmp != null) ScreenActivity.cursorLook(bmp, hx, hy);
        }

        private void takeWifiLock() {
            try {
                android.net.wifi.WifiManager wm = ctx.getSystemService(android.net.wifi.WifiManager.class);
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tv-screen");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            } catch (Exception e) {
                Log.w(TAG, "no low-latency Wi-Fi lock", e);
            }
        }

        private void audioConfig(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            int rate = bb.getInt();
            int channels = bb.get();
            if (audio != null) audio.release();
            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            audio = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, rate * channels * 2 / 10))   // ~100 ms
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            audio.play();
        }

        /** Messages to the Mac go out on their own thread: callers include the UI thread (surface changes). */
        private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();

        void send(int type, byte[] payload) {
            if (out == null) return;
            try {
                writer.execute(() -> {
                    try {
                        out.writeByte(type);
                        out.writeInt(payload.length);
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
            releaseDecoder();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (audio != null) {
                audio.release();
                audio = null;
            }
        }
    }
}
