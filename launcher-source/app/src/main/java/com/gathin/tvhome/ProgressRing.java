package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Apple's download ring (as on App Store icons): the picture dims, and a thin white ring fills clockwise from
 * the top over a faint track. While the size isn't known yet, a short arc spins instead.
 */
final class ProgressRing extends View {

    private final Paint scrim = new Paint();
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float d;
    private float shown, target = -1;

    ProgressRing(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        scrim.setColor(0x66000000);
        disc.setColor(0x59000000);
        for (Paint p : new Paint[]{track, arc}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3.5f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(0x40FFFFFF);
        arc.setColor(0xFFFFFFFF);
    }

    /** 0–1, or below 0 while the size isn't known. */
    void set(float fraction) {
        target = fraction;
        if (fraction >= 0 && shown > fraction) shown = fraction;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = 20 * d;
        c.drawCircle(cx, cy, r + 7 * d, disc);
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawOval(oval, track);
        if (target < 0) {
            float spin = (SystemClock.uptimeMillis() % 1000) / 1000f * 360f;
            c.drawArc(oval, spin - 90, 80, false, arc);
            postInvalidateOnAnimation();
        } else {
            shown += (target - shown) * 0.25f;   // eases toward the real value instead of jumping
            if (Math.abs(target - shown) > 0.002f) postInvalidateOnAnimation();
            else shown = target;
            c.drawArc(oval, -90, Math.max(2f, shown * 360f), false, arc);
        }
    }
}
