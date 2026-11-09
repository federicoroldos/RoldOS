package com.roldos;

import android.animation.TimeAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The home's background: the graphite stays the background everywhere, and only the area
 * around the focused tile takes on that tile's color, in a wide, very soft spot of light.
 *
 * Federico (2026-11-08): the first version tinted the whole screen and switched colors
 * abruptly. Now each tile has its own spot: moving to another tile fades the old spot out
 * where it was and the new one in around the new tile (a slow cross-fade, never a jump
 * through a muddy in-between color). Colors keep their own saturation.
 *
 * Cheap on the projector's GPU: a flat fill plus one gradient per visible spot (usually one,
 * two during a cross-fade), each drawn only inside its own box.
 */
final class Ambient extends Drawable {
    static final int BASE = 0xFF141516; // B2 Graphite
    private static final long IN_MS = 750, OUT_MS = 900;
    private static final float R = 100f; // gradients are built at this radius, then scaled
    /** Strength at the spot's center. Low on purpose: the graphite must stay the main color. */
    private static final float PEAK = 0.34f;
    private static final PathInterpolator SOFT = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /** One spot of light following one tile. */
    private static final class Spot {
        View v;
        final int color;
        final RadialGradient shader;
        float x, y, hw, hh;        // last known center and half size on screen
        float a, from, to;         // strength now / at the fade's start / target
        long start, ms;
        Spot(View v, int color) {
            this.v = v;
            this.color = color;
            int c = glowColor(color);
            float k = isNeutral(color) ? 0.55f : 1f; // neutral art: a fainter, grey-white light
            // A long, smooth fall-off (roughly bell-shaped) so the spot has no visible edge.
            float[] stops = {0f, 0.18f, 0.36f, 0.54f, 0.72f, 0.86f, 1f};
            float[] amount = {1f, 0.9f, 0.68f, 0.42f, 0.2f, 0.07f, 0f};
            int[] colors = new int[stops.length];
            for (int i = 0; i < stops.length; i++) colors[i] = withAlpha(c, PEAK * k * amount[i]);
            shader = new RadialGradient(0, 0, R, colors, stops, Shader.TileMode.CLAMP);
        }
    }

    private final Paint fill = new Paint();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Matrix m = new Matrix();
    private final List<Spot> spots = new ArrayList<>();
    private final TimeAnimator ticker = new TimeAnimator();
    private final int[] loc = new int[2];
    private Spot lit; // the spot that is (fading) on

    Ambient() {
        fill.setColor(BASE);
        ticker.setTimeListener((a, total, delta) -> step());
    }

    /**
     * Focus moved to {@code v}. {@code color} is its main color; 0 = no light here (the top-bar
     * button, or art that hasn't loaded yet).
     */
    void focus(View v, int color) {
        if (lit != null && lit.v == v && lit.color == color && lit.to == 1f) return; // no change
        if (lit == null && (v == null || color == 0)) return;
        long now = now();
        for (Spot s : spots) fade(s, 0f, OUT_MS, now);
        lit = null;
        if (v != null && color != 0) {
            Spot s = new Spot(v, color);
            measure(s);
            fade(s, 1f, IN_MS, now);
            spots.add(s);
            lit = s;
        }
        if (!ticker.isStarted()) ticker.start();
        invalidateSelf();
    }

    /** Called before every frame: keeps each spot on its tile while rows glide and zoom. */
    void track() {
        boolean moved = false;
        for (Spot s : spots) {
            float ox = s.x, oy = s.y, ow = s.hw;
            measure(s);
            if (ox != s.x || oy != s.y || ow != s.hw) moved = true;
        }
        if (moved) invalidateSelf();
    }

    private static void fade(Spot s, float to, long ms, long now) {
        s.from = s.a;
        s.to = to;
        s.start = now;
        s.ms = ms;
    }

    private void step() {
        long now = now();
        boolean running = false;
        for (int i = spots.size() - 1; i >= 0; i--) {
            Spot s = spots.get(i);
            float t = Math.min(1f, (now - s.start) / (float) s.ms);
            s.a = s.from + (s.to - s.from) * SOFT.getInterpolation(t);
            if (t < 1f) running = true;
            else if (s.to == 0f) { spots.remove(i); s.v = null; }
        }
        if (!running) ticker.end();
        invalidateSelf();
    }

    /** The spot's tile: on-screen center and half size, with all zooms and scrolls applied. */
    private void measure(Spot s) {
        View v = s.v;
        if (v == null || !v.isAttachedToWindow() || v.getWidth() == 0) return; // keep last place
        float scale = 1f;
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            scale *= p.getScaleX();
        }
        v.getLocationInWindow(loc);
        s.hw = v.getWidth() * scale / 2f;
        s.hh = v.getHeight() * scale / 2f;
        s.x = loc[0] + s.hw;
        s.y = loc[1] + s.hh;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        c.drawRect(b, fill);
        for (Spot s : spots) {
            if (s.a < 0.004f || s.hw <= 0) continue;
            // A wide ellipse: reaches well past the tile so the color melts into the graphite.
            float rx = s.hw * 2.6f + s.hh * 0.6f, ry = s.hh * 3.2f;
            m.setScale(rx / R, ry / R);
            m.postTranslate(s.x, s.y);
            s.shader.setLocalMatrix(m);
            paint.setShader(s.shader);
            paint.setAlpha(Math.round(255 * s.a));
            c.drawRect(s.x - rx, s.y - ry, s.x + rx, s.y + ry, paint);
        }
    }

    private static boolean isNeutral(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        return hsv[1] < 0.15f;
    }

    /** The art's color, its saturation kept; only lifted when too dark to show on graphite. */
    private static int glowColor(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.15f) return 0xFFB8BCC2; // white / grey / black art
        hsv[2] = Math.max(hsv[2], 0.75f);
        return Color.HSVToColor(hsv);
    }

    private static int withAlpha(int c, float a) {
        return (Math.round(Math.max(0f, Math.min(1f, a)) * 255) << 24) | (c & 0xFFFFFF);
    }

    private static long now() { return android.os.SystemClock.uptimeMillis(); }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
