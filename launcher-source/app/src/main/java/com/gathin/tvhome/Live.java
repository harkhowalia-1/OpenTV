package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Live wallpapers: scenes drawn on the TV, live, with a small OpenGL shader (the same ones OpenTV's website
 * shows). "live:ocean" is light falling through water with drifting specks; "live:aurora" is northern lights
 * over mountains under a starry sky. They never repeat and need no download.
 *
 * <p>Drawn small (960 × 540, scaled up by the TV; the scenes are soft, so it looks the same) at 24 frames a second,
 * and only while Home or the screensaver is showing, so even a Chromecast stays cool.
 */
final class Live {

    static final String[] IDS = {"ocean", "aurora"};
    private static final String TAG = "OtvLive";
    static final int W = 960, H = 540;

    private Live() {
    }

    static boolean is(String kind) {
        return kind != null && kind.startsWith("live:");
    }

    /** The shader's scene number for a wallpaper kind, or -1 for anything that isn't live. */
    static int mode(String kind) {
        if (!is(kind)) return -1;
        return kind.equals("live:aurora") ? 2 : 1;
    }

    static String name(Context c, String idOrKind) {
        String id = idOrKind.startsWith("live:") ? idOrKind.substring(5) : idOrKind;
        return c.getString(id.equals("aurora") ? R.string.live_aurora : R.string.live_ocean);
    }

    // ---- stills: the picture Home shows while the scene starts, and previews for menus and the iPhone -------

    /** A preview (made once, then kept). Call off the main thread. */
    static File thumbnail(Context c, String id) {
        File f = new File(Wallpapers.dir(c, "live-thumbs"), id + ".jpg");
        if (!f.exists()) save(render(mode("live:" + id), 480, 270, 40f), f);
        return f;
    }

    /** The full still for Home. Call off the main thread. */
    static void saveStill(Context c, String id, File out) {
        save(render(mode("live:" + id), W, H, 40f), out);
    }

    private static void save(Bitmap b, File out) {
        if (b == null) return;
        File tmp = new File(out.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, o);
        } catch (Exception e) {
            Log.w(TAG, "couldn't save " + out.getName(), e);
            return;
        } finally {
            b.recycle();
        }
        if (!tmp.renameTo(out)) tmp.delete();
    }

    /** One frame, drawn off screen. */
    static synchronized Bitmap render(int mode, int w, int h, float t) {
        Gl gl = null;
        try {
            gl = new Gl(null, w, h);
            gl.draw(mode, w, h, t);
            ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            Bitmap upside = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            upside.copyPixelsFromBuffer(px);
            // OpenGL reads from the bottom row up.
            android.graphics.Matrix flip = new android.graphics.Matrix();
            flip.setScale(1, -1);
            Bitmap b = Bitmap.createBitmap(upside, 0, 0, w, h, flip, false);
            upside.recycle();
            return b;
        } catch (Throwable e) {
            Log.w(TAG, "couldn't draw a still", e);
            return null;
        } finally {
            if (gl != null) gl.release();
        }
    }

    // ---- the view ----------------------------------------------------------------------------------------

    /** The scene on screen: a TextureView (so Home's frosted glass can see it) drawn on its own thread. */
    static final class Scene extends TextureView implements TextureView.SurfaceTextureListener {
        private static final long FRAME_MS = 1000 / 24;
        private volatile int mode = 1;
        private volatile boolean wanted, paused;
        private Thread thread;

        Scene(Context c) {
            super(c);
            setSurfaceTextureListener(this);
        }

        void setMode(int m) {
            mode = m;
        }

        /** Holds the last frame (under Control Center and other panels) without letting go of the surface. */
        void setPaused(boolean p) {
            paused = p;
        }

        void start() {
            wanted = true;
            if (thread == null && isAvailable()) begin(getSurfaceTexture());
        }

        void stop() {
            wanted = false;
            Thread t = thread;
            thread = null;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(600);
                } catch (InterruptedException ignored) {
                }
            }
        }

        boolean running() {
            return thread != null;
        }

        private void begin(SurfaceTexture st) {
            st.setDefaultBufferSize(W, H);
            Thread t = new Thread(() -> {
                Gl gl = null;
                try {
                    gl = new Gl(st, W, H);
                    // Start somewhere in the scene's life, not at its very first second.
                    long t0 = SystemClock.elapsedRealtime() - 30_000;
                    while (wanted && !Thread.currentThread().isInterrupted()) {
                        if (paused) {
                            Thread.sleep(120);
                            continue;
                        }
                        long f = SystemClock.elapsedRealtime();
                        float secs = ((f - t0) % 3_600_000L) / 1000f;   // stays precise on the GPU
                        gl.draw(mode, W, H, secs);
                        gl.swap();
                        // Repaint the whole scene each frame: without it Home only redraws the parts that change for
                        // other reasons (the title, the glass), and the rest of the screen stays black.
                        postInvalidateOnAnimation();
                        long wait = FRAME_MS - (SystemClock.elapsedRealtime() - f);
                        if (wait > 0) Thread.sleep(wait);
                    }
                } catch (InterruptedException ignored) {
                } catch (Throwable e) {
                    Log.w(TAG, "live wallpaper stopped", e);
                } finally {
                    if (gl != null) gl.release();
                }
            }, "otv-live");
            thread = t;
            t.start();
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            if (wanted && thread == null) begin(st);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            boolean was = wanted;
            stop();
            wanted = was;   // comes back with the surface
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    }

    // ---- OpenGL --------------------------------------------------------------------------------------------

    private static final class Gl {
        private final EGLDisplay display;
        private final EGLContext context;
        private final EGLSurface surface;
        private final int uR, uT, uMode;

        Gl(SurfaceTexture window, int w, int h) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("EGL");
            int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, window != null ? EGL14.EGL_WINDOW_BIT : EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) || n[0] == 0) throw new IllegalStateException("config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = window != null
                    ? EGL14.eglCreateWindowSurface(display, configs[0], window, new int[]{EGL14.EGL_NONE}, 0)
                    : EGL14.eglCreatePbufferSurface(display, configs[0], new int[]{EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IllegalStateException("current");
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 a; void main(){ gl_Position = vec4(a, 0.0, 1.0); }"));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAG));
            GLES20.glLinkProgram(program);
            GLES20.glUseProgram(program);
            FloatBuffer tri = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            tri.put(new float[]{-1, -1, 3, -1, -1, 3}).position(0);
            int a = GLES20.glGetAttribLocation(program, "a");
            GLES20.glEnableVertexAttribArray(a);
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, tri);
            uR = GLES20.glGetUniformLocation(program, "r");
            uT = GLES20.glGetUniformLocation(program, "t");
            uMode = GLES20.glGetUniformLocation(program, "mode");
        }

        private static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        void draw(int mode, int w, int h, float t) {
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUniform2f(uR, w, h);
            GLES20.glUniform1f(uT, t);
            GLES20.glUniform1f(uMode, mode);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        }

        void swap() {
            EGL14.eglSwapBuffers(display, surface);
        }

        /** Lets go of this context only: the display is shared with the rest of the app's drawing. */
        void release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
        }
    }

    /** The scenes (also on OpenTV's website, docs/demo.js). */
    private static final String FRAG = ""
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
            + "uniform vec2 r; uniform float t; uniform float mode;\n"
            + "float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
            + "float noise(vec2 p){ vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);\n"
            + "  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y); }\n"
            + "float fbm(vec2 p){ float v = 0.0, a = 0.5; for (int i = 0; i < 4; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; } return v; }\n"
            + "vec3 ocean(vec2 uv, vec2 p){\n"
            + "  vec3 col = mix(vec3(0.004, 0.03, 0.07), vec3(0.06, 0.33, 0.43), pow(uv.y, 1.7));\n"
            + "  vec2 src = vec2(0.7 * r.x / r.y, 1.7);\n"
            + "  vec2 d = p - src; float ang = atan(d.x, -d.y);\n"
            + "  float rays = (0.5 + 0.5 * sin(ang * 23.0 + t * 0.17)) * (0.5 + 0.5 * sin(ang * 41.0 - t * 0.11 + 2.0));\n"
            + "  rays = pow(rays, 1.6);\n"
            + "  col += vec3(0.36, 0.64, 0.68) * rays * pow(uv.y, 1.6) * 0.5;\n"
            + "  vec2 q = p * 7.0 + vec2(t * 0.07, t * 0.045);\n"
            + "  float c = abs(sin(q.x + 2.0 * sin(q.y * 0.7 + t * 0.3))) * abs(sin(q.y + 2.0 * sin(q.x * 0.8 - t * 0.25)));\n"
            + "  c = pow(1.0 - c, 16.0);\n"
            + "  col += vec3(0.45, 0.8, 0.85) * c * smoothstep(0.55, 1.0, uv.y) * 0.3;\n"
            + "  for (int i = 0; i < 3; i++) {\n"
            + "    float fi = float(i);\n"
            + "    vec2 s = p * (5.0 + fi * 3.5) + vec2(sin(t * 0.1 + fi) * 0.4, -t * (0.05 + 0.03 * fi));\n"
            + "    vec2 id = floor(s); vec2 f = fract(s) - 0.5;\n"
            + "    float k = hash(id + fi * 13.1);\n"
            + "    if (k > 0.9) {\n"
            + "      vec2 o = vec2(hash(id + 3.7), hash(id + 9.1)) - 0.5;\n"
            + "      float dd = length(f - o * 0.6);\n"
            + "      col += vec3(0.55, 0.78, 0.82) * smoothstep(0.11 - fi * 0.02, 0.0, dd) * (0.10 + 0.08 * fi);\n"
            + "    }\n"
            + "  }\n"
            + "  return col;\n"
            + "}\n"
            + "vec3 aurora(vec2 uv, vec2 p){\n"
            + "  vec3 col = mix(vec3(0.004, 0.012, 0.04), vec3(0.02, 0.05, 0.13), uv.y);\n"
            + "  vec2 s = floor(p * 170.0); float st = hash(s);\n"
            + "  col += step(0.9965, st) * vec3(0.85) * (0.55 + 0.45 * sin(t * 1.7 + st * 60.0));\n"
            + "  for (int i = 0; i < 3; i++) {\n"
            + "    float fi = float(i);\n"
            + "    float y = 0.52 + 0.11 * fi + 0.07 * sin(p.x * 1.6 + t * 0.09 + fi * 2.0) + 0.06 * (fbm(vec2(p.x * 1.8 + t * 0.04, fi * 3.0)) - 0.5);\n"
            + "    float band = exp(-pow((uv.y - y) * (6.5 + fi), 2.0));\n"
            + "    float above = smoothstep(y - 0.02, y + 0.22, uv.y) * smoothstep(y + 0.45, y + 0.05, uv.y);\n"
            + "    float streak = 0.55 + 0.45 * fbm(vec2(p.x * 14.0 + t * 0.25 + fi * 7.0, uv.y * 1.5));\n"
            + "    vec3 c = mix(vec3(0.12, 0.95, 0.58), vec3(0.58, 0.32, 0.98), fi / 2.0);\n"
            + "    col += c * (band * 0.75 + above * 0.18) * streak * 0.6;\n"
            + "  }\n"
            + "  float m = 0.16 + 0.07 * fbm(vec2(p.x * 1.4, 2.0)) + 0.03 * sin(p.x * 3.0);\n"
            + "  col = mix(col, vec3(0.004, 0.008, 0.016), smoothstep(m + 0.004, m - 0.004, uv.y));\n"
            + "  return col;\n"
            + "}\n"
            + "void main(){\n"
            + "  vec2 uv = gl_FragCoord.xy / r;\n"
            + "  vec2 p = vec2(uv.x * r.x / r.y, uv.y);\n"
            + "  vec3 col = mode < 1.5 ? ocean(uv, p) : aurora(uv, p);\n"
            + "  col *= 1.0 - 0.35 * pow(length(uv - 0.5) * 1.25, 2.4);\n"
            + "  col += (hash(gl_FragCoord.xy + t) - 0.5) / 255.0;\n"
            + "  gl_FragColor = vec4(col, 1.0);\n"
            + "}\n";
}
