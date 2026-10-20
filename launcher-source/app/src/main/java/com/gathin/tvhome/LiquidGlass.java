package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * tvOS 26 "Liquid Glass" surface, drawn without blur so it stays cheap on a 1 GB TV: a translucent body,
 * a rim that is bright at the top-left and bottom-right like light caught in a lens, a specular sheen across
 * the top and a refraction glow along the bottom edge. Interactive glass turns solid white when focused,
 * as tvOS buttons do; "on" glass (an active toggle) is tinted blue.
 */
final class LiquidGlass extends Drawable {

    static final int LIGHT = 0;   // over dark content: the dock, the status pill
    static final int DARK = 1;    // darker glass
    static final int SMOKE = 2;   // tvOS 26 smoked glass: dock, status pill, Control Center panel

    private final float radius;   // px; negative means a pill (half the height)
    private final float density;
    private final int style;
    private final boolean interactive;
    private boolean focused;
    private boolean on;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int tintColor;
    private float tintStrength;
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private int alpha = 255;

    LiquidGlass(Context ctx, float radiusDp, int style, boolean interactive) {
        density = ctx.getResources().getDisplayMetrics().density;
        this.radius = radiusDp < 0 ? -1 : radiusDp * density;
        this.style = style;
        this.interactive = interactive;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.25f * density);
    }

    /** Tints the glass as an active toggle (blue), like Do Not Disturb when it is on. */
    void setOn(boolean value) {
        if (on == value) return;
        on = value;
        rebuildShaders();
        invalidateSelf();
    }

    /**
     * Frosted-glass vibrancy: light from the colour glowing behind the glass (the focused app's glow)
     * shows through it brighter and more saturated, strongest at the top-left. 0 strength turns it off.
     */
    void setTint(int color, float strength) {
        strength = Math.max(0f, Math.min(1f, strength));
        if (color == tintColor && Math.abs(strength - tintStrength) < 0.004f) return;
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        tintColor = color;
        tintStrength = strength;
        int c = android.graphics.Color.HSVToColor(hsv) & 0x00FFFFFF;
        Rect b = getBounds();
        tint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{((int) (0x99 * strength) << 24) | c, ((int) (0x47 * strength) << 24) | c, ((int) (0x1F * strength) << 24) | c},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        rebuildShaders();
        if (tintStrength > 0) {
            float s = tintStrength;
            tintStrength = -1;
            setTint(tintColor, s);
        }
    }

    private void rebuildShaders() {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float t = b.top, h = b.height();
        int top, bottom;
        if (on) {
            top = 0xF04DA6FF;
            bottom = 0xF00A72E8;
        } else if (style == SMOKE) {
            top = 0x942A2A2D;
            bottom = 0x8A1B1B1D;
        } else if (style == DARK) {
            top = 0xA63C3C42;
            bottom = 0xB31C1C20;
        } else {
            top = 0x4DFFFFFF;
            bottom = 0x1FFFFFFF;
        }
        fill.setShader(new LinearGradient(0, t, 0, t + h, top, bottom, Shader.TileMode.CLAMP));
        boolean smoke = style == SMOKE && !on;
        // Smoked glass is quieter: a faint sheen and a rim that only just catches the light.
        sheen.setShader(new LinearGradient(0, t, 0, t + h * 0.48f, smoke ? 0x17FFFFFF : 0x52FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        glow.setShader(new LinearGradient(0, t + h * 0.62f, 0, t + h, 0x00FFFFFF, smoke ? 0x0DFFFFFF : 0x24FFFFFF,
                Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(b.left, t, b.right, t + h,
                smoke ? new int[]{0x4DFFFFFF, 0x14FFFFFF, 0x0AFFFFFF, 0x2EFFFFFF}
                        : new int[]{0xF2FFFFFF, 0x2EFFFFFF, 0x1AFFFFFF, 0x99FFFFFF},
                new float[]{0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float inset = rim.getStrokeWidth() / 2f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float r = radius < 0 ? rect.height() / 2f : radius;
        int save = c.saveLayerAlpha(rect.left - inset, rect.top - inset, rect.right + inset, rect.bottom + inset, alpha);

        if (focused) {
            // Focused tvOS control: an opaque white lozenge with a faint inner shading.
            fill.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0xFFFFFFFF, 0xFFE9E9EE, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, r, r, fill);
            rebuildShaders();   // restore the resting fill for next time
            c.restoreToCount(save);
            return;
        }

        c.drawRoundRect(rect, r, r, fill);
        if (tintStrength > 0) c.drawRoundRect(rect, r, r, tint);
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.48f, sheen);
        c.drawRect(rect.left, rect.top + rect.height() * 0.62f, rect.right, rect.bottom, glow);
        c.restore();
        c.drawRoundRect(rect, r, r, rim);
        c.restoreToCount(save);
    }

    @Override
    public boolean isStateful() {
        return interactive;
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        boolean f = false;
        for (int s : state) if (s == android.R.attr.state_focused) f = true;
        if (f == focused) return false;
        focused = f;
        invalidateSelf();
        return true;
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
