package com.roldos;

import android.animation.ValueAnimator;
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

/**
 * The home's background, Google TV style: the whole screen takes on a deep tint of the
 * focused tile's color, and a soft light in that color glows behind the tile itself.
 * Moving to another tile cross-fades the color and slides the light over.
 *
 * Kept cheap for the projector's small GPU: one flat fill, one full-screen gradient and one
 * gradient limited to the glow's box. Gradients are recreated only while the color changes;
 * the glow just follows its tile (MainActivity calls {@link #track()} before each frame).
 */
final class Ambient extends Drawable {
    private static final int BASE = 0xFF141516; // B2 Graphite: what's left with no tint
    private static final int MS = 450;
    private static final float R = 100f; // gradients are built at this radius, then scaled

    private final Paint fill = new Paint();
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Matrix m = new Matrix();
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    private final int[] loc = new int[2];

    /** Tint color: from (start of the fade), to (target), now (current). */
    private int fromC = BASE, toC = BASE, nowC = BASE, shaderC = 0;
    /** Glow strength 0..1 (0 when the focus is off the tiles). */
    private float fromA, toA, nowA;
    /** Glow box (center + half size), where it was when the fade started and where it is. */
    private float sx, sy, sw, sh, gx, gy, gw, gh;
    private float f = 1f;
    private View target;

    Ambient() {
        anim.setDuration(MS);
        anim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        anim.addUpdateListener(a -> { f = (float) a.getAnimatedValue(); step(); invalidateSelf(); });
    }

    /**
     * Focus moved. {@code color} is the tile's main color, or 0 to keep the current tint and
     * put the light out (focus on something that isn't a tile, like the top bar button).
     */
    void focus(View v, int color) {
        float wantA = color != 0 && v != null ? 1f : 0f;
        if (wantA == toA && (wantA == 0f ? color == 0 || color == toC : v == target && color == toC)) return; // no change
        fromC = nowC;
        fromA = nowA;
        sx = gx; sy = gy; sw = gw; sh = gh;
        if (color != 0) toC = color;
        toA = color != 0 && v != null ? 1f : 0f;
        if (toA > 0 && (target == null || nowA < 0.02f)) {
            // Light was out: switch it on in place rather than flying in from the old spot.
            if (measure(v)) { sx = gx; sy = gy; sw = gw; sh = gh; }
        }
        if (toA > 0) target = v;
        f = 0f;
        anim.cancel();
        anim.start();
    }

    /** Called before every frame: keeps the light on its tile while rows glide. */
    void track() {
        if (target == null || nowA <= 0f) return;
        float ox = gx, oy = gy, ow = gw;
        step();
        if (ox != gx || oy != gy || ow != gw) invalidateSelf();
    }

    private void step() {
        nowC = mix(fromC, toC, f);
        nowA = fromA + (toA - fromA) * f;
        if (target != null && measure(target)) {
            // measure() put the tile's live box in g*: ease from where the light started.
            gx = sx + (gx - sx) * f; gy = sy + (gy - sy) * f;
            gw = sw + (gw - sw) * f; gh = sh + (gh - sh) * f;
        }
        if (f >= 1f && toA == 0f) target = null;
    }

    /** Puts {@code v}'s on-screen center and half size (with all zooms applied) in g*. */
    private boolean measure(View v) {
        if (v == null || !v.isAttachedToWindow() || v.getWidth() == 0) return false;
        float scale = 1f;
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            scale *= p.getScaleX();
        }
        v.getLocationInWindow(loc); // top-left corner, after zooms and scrolls
        gw = v.getWidth() * scale / 2f;
        gh = v.getHeight() * scale / 2f;
        gx = loc[0] + gw;
        gy = loc[1] + gh;
        return true;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        int w = b.width(), h = b.height();
        if (shaderC != nowC) buildShaders();
        // 1. Deep tint of the color over the whole screen.
        fill.setColor(mix(BASE, tone(nowC, 0.62f), 0.55f));
        c.drawRect(b, fill);
        // 2. A broad lighter wash from the lower right, so the screen isn't one flat color.
        m.setScale(w * 0.85f / R, h * 1.1f / R);
        m.postTranslate(w * 0.8f, h * 0.75f);
        wash.getShader().setLocalMatrix(m);
        c.drawRect(b, wash);
        // 3. The light behind the focused tile (an ellipse a bit wider than the tile).
        if (nowA > 0.01f && gw > 0) {
            float rx = gw * 2.1f, ry = gh * 2.6f;
            m.setScale(rx / R, ry / R);
            m.postTranslate(gx, gy);
            glow.getShader().setLocalMatrix(m);
            glow.setAlpha(Math.round(255 * nowA));
            c.drawRect(gx - rx, gy - ry, gx + rx, gy + ry, glow);
        }
    }

    private void buildShaders() {
        shaderC = nowC;
        int t = tone(nowC, 0.8f);
        wash.setShader(new RadialGradient(0, 0, R,
                new int[]{alpha(t, 0.38f), alpha(t, 0.16f), 0}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        int g = tone(nowC, 1f);
        glow.setShader(new RadialGradient(0, 0, R,
                new int[]{alpha(g, 0.85f), alpha(g, 0.5f), alpha(g, 0.16f), 0},
                new float[]{0f, 0.38f, 0.68f, 1f}, Shader.TileMode.CLAMP));
    }

    /** The color at a given brightness, saturation kept lively but not neon. */
    private static int tone(int c, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] > 0.12f) {
            hsv[1] = Math.max(0.45f, Math.min(hsv[1], 0.85f));
            hsv[2] = value;
        } else {
            hsv[2] = value * 0.35f; // gray, white or black art: a dim neutral, not a gray screen
        }
        return Color.HSVToColor(hsv);
    }

    private static int alpha(int c, float a) { return (Math.round(a * 255) << 24) | (c & 0xFFFFFF); }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
