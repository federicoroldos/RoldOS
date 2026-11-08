package uy.fede.roldos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * All of the launcher's hand-drawn art: generated banners for apps that don't ship one,
 * the Settings-row tiles, and the "Close everything" icon. Pure Canvas drawing, no assets.
 */
final class TileArt {
    private TileArt() { }

    // ------------------------------------------------------------------ app banners

    /**
     * Builds a banner for an app that only has an icon: a gradient in the icon's own main
     * color, the icon on the left, the name on the right and a large faded copy of the icon
     * bleeding off the right edge, so every app gets a distinct look.
     */
    static Bitmap appBanner(Drawable icon, String label, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        int base = icon != null ? dominantColor(icon) : 0;
        int[] grad = gradientFor(base);
        paintGradient(c, w, h, grad[0], grad[1]);

        if (icon != null) {
            icon = icon.mutate();
            // Faded oversized echo of the icon, cropped by the right edge.
            int big = Math.round(h * 1.25f);
            int bx = w - Math.round(big * 0.72f), by = (h - big) / 2 + Math.round(h * 0.12f);
            icon.setAlpha(34);
            icon.setBounds(bx, by, bx + big, by + big);
            icon.draw(c);
            icon.setAlpha(255);
        }
        int pad = Math.round(h * 0.17f);
        int s = Math.round(h * 0.44f);
        if (icon != null) {
            int top = (h - s) / 2;
            icon.setBounds(pad, top, pad + s, top + s);
            icon.draw(c);
        }
        int textLeft = icon != null ? pad + s + Math.round(pad * 0.75f) : pad;
        drawLabel(c, label, textLeft, w - textLeft - Math.round(pad * 0.5f), h);
        return b;
    }

    /** Average color of the icon's saturated, opaque pixels (falls back to all opaque pixels). */
    static int dominantColor(Drawable icon) {
        final int n = 24;
        Bitmap small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        Rect old = icon.copyBounds();
        icon.setBounds(0, 0, n, n);
        icon.draw(c);
        icon.setBounds(old);
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, wsum = 0, r2 = 0, g2 = 0, b2 = 0;
        int cnt = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = small.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                r2 += Color.red(p); g2 += Color.green(p); b2 += Color.blue(p); cnt++;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.28f || hsv[2] < 0.18f) continue;
                double wgt = hsv[1] * hsv[2];
                r += Color.red(p) * wgt; g += Color.green(p) * wgt; bl += Color.blue(p) * wgt; wsum += wgt;
            }
        }
        small.recycle();
        if (wsum > 0) return Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
        if (cnt > 0) return Color.rgb((int) (r2 / cnt), (int) (g2 / cnt), (int) (b2 / cnt));
        return 0;
    }

    /** Two dark-ish shades of the base color, so white text stays readable. */
    private static int[] gradientFor(int base) {
        if (base == 0) return new int[]{0xFF3A4570, 0xFF161B2E};
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        boolean gray = hsv[1] < 0.15f;
        float sat = gray ? 0.12f : Math.min(Math.max(hsv[1], 0.45f), 0.85f);
        int top = Color.HSVToColor(new float[]{hsv[0], sat, 0.50f});
        int bottom = Color.HSVToColor(new float[]{hsv[0], Math.min(sat + 0.1f, 1f), 0.17f});
        return new int[]{top, bottom};
    }

    private static void paintGradient(Canvas c, int w, int h, int from, int to) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, from, to, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    /** Bold label, up to two lines, vertically centered in the space right of the icon. */
    private static void drawLabel(Canvas c, CharSequence label, int left, int width, int h) {
        if (width <= 0) return;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tp.setTextSize(h * 0.15f);
        tp.setShadowLayer(h * 0.03f, 0, h * 0.01f, 0x66000000);
        // A single long word would be split mid-word across two lines: shrink it to fit instead.
        String s = label.toString();
        if (s.indexOf(' ') < 0) {
            float wNow = tp.measureText(s);
            if (wNow > width) tp.setTextSize(Math.max(h * 0.10f, tp.getTextSize() * width / wNow));
        }
        StaticLayout sl = StaticLayout.Builder.obtain(label, 0, label.length(), tp, width)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();
        c.save();
        c.translate(left, (h - sl.getHeight()) / 2f);
        sl.draw(c);
        c.restore();
    }

    // ------------------------------------------------------------------ Settings row tiles

    enum Kind { CALIBRATION, INPUT, SETTINGS, WIFI }

    /** A Settings-row tile: same layout as the app banners, with a hand-drawn line icon. */
    static Bitmap settingsBanner(Kind kind, String label, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        int[] g;
        switch (kind) {
            case CALIBRATION: g = new int[]{0xFF6A47C2, 0xFF22134A}; break;
            case INPUT:       g = new int[]{0xFF1F8F84, 0xFF0B2E2B}; break;
            case WIFI:        g = new int[]{0xFF2C6FD1, 0xFF0D2049}; break;
            default:          g = new int[]{0xFF4A5A7E, 0xFF151B2B}; break;
        }
        paintGradient(c, w, h, g[0], g[1]);

        int pad = Math.round(h * 0.17f);
        int s = Math.round(h * 0.44f);
        // Faded echo of the icon on the right, like the app banners.
        int big = Math.round(h * 1.15f);
        drawIcon(c, kind, w - Math.round(big * 0.7f), (h - big) / 2 + Math.round(h * 0.1f), big, 0x22FFFFFF);
        drawIcon(c, kind, pad, (h - s) / 2, s, 0xFFFFFFFF);
        int textLeft = pad + s + Math.round(pad * 0.75f);
        drawLabel(c, label, textLeft, w - textLeft - Math.round(pad * 0.5f), h);
        return b;
    }

    /** Draws a line icon inside the square (x, y, s, s). */
    private static void drawIcon(Canvas c, Kind kind, float x, float y, float s, int color) {
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setColor(color);
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(s * 0.075f);
        st.setStrokeCap(Paint.Cap.ROUND);
        st.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        Path p = new Path();
        switch (kind) {
            case CALIBRATION: {
                // A keystone-distorted screen squared up inside focus brackets.
                p.moveTo(x + s * 0.30f, y + s * 0.28f);
                p.lineTo(x + s * 0.70f, y + s * 0.28f);
                p.lineTo(x + s * 0.80f, y + s * 0.72f);
                p.lineTo(x + s * 0.20f, y + s * 0.72f);
                p.close();
                c.drawPath(p, st);
                float a = s * 0.18f, m = s * 0.04f;
                float l = x + m, t = y + m, r = x + s - m, bt = y + s - m;
                c.drawLine(l, t, l + a, t, st); c.drawLine(l, t, l, t + a, st);
                c.drawLine(r, t, r - a, t, st); c.drawLine(r, t, r, t + a, st);
                c.drawLine(l, bt, l + a, bt, st); c.drawLine(l, bt, l, bt - a, st);
                c.drawLine(r, bt, r - a, bt, st); c.drawLine(r, bt, r, bt - a, st);
                break;
            }
            case INPUT: {
                // A video connector plug with an arrow pointing into it.
                p.moveTo(x + s * 0.14f, y + s * 0.30f);
                p.lineTo(x + s * 0.86f, y + s * 0.30f);
                p.lineTo(x + s * 0.86f, y + s * 0.50f);
                p.lineTo(x + s * 0.74f, y + s * 0.64f);
                p.lineTo(x + s * 0.26f, y + s * 0.64f);
                p.lineTo(x + s * 0.14f, y + s * 0.50f);
                p.close();
                c.drawPath(p, st);
                c.drawLine(x + s * 0.32f, y + s * 0.44f, x + s * 0.68f, y + s * 0.44f, st);
                float cx = x + s * 0.5f;
                c.drawLine(cx, y + s * 0.78f, cx, y + s * 0.97f, st);
                c.drawLine(cx, y + s * 0.78f, cx - s * 0.09f, y + s * 0.87f, st);
                c.drawLine(cx, y + s * 0.78f, cx + s * 0.09f, y + s * 0.87f, st);
                c.drawLine(cx, y + s * 0.04f, cx, y + s * 0.20f, st);
                break;
            }
            case WIFI: {
                float cx = x + s * 0.5f, cy = y + s * 0.80f;
                for (int i = 1; i <= 3; i++) {
                    float rr = s * (0.20f + 0.20f * i) / 1.25f;
                    c.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr), 225, 90, false, st);
                }
                c.drawCircle(cx, cy - s * 0.02f, s * 0.06f, fill);
                break;
            }
            default: {
                // Gear: toothed ring with a hole (even-odd fill).
                float cx = x + s / 2f, cy = y + s / 2f;
                float body = s * 0.30f, tooth = s * 0.44f, hole = s * 0.12f;
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a0 = Math.PI * 2 * i / (teeth * 2);
                    double a1 = Math.PI * 2 * (i + 1) / (teeth * 2);
                    float rr = (i % 2 == 0) ? tooth : body;
                    float x0 = cx + (float) Math.cos(a0) * rr, y0 = cy + (float) Math.sin(a0) * rr;
                    float x1 = cx + (float) Math.cos(a1) * rr, y1 = cy + (float) Math.sin(a1) * rr;
                    if (i == 0) p.moveTo(x0, y0); else p.lineTo(x0, y0);
                    p.lineTo(x1, y1);
                }
                p.close();
                p.addCircle(cx, cy, hole, Path.Direction.CW);
                p.setFillType(Path.FillType.EVEN_ODD);
                st.setStyle(Paint.Style.STROKE);
                c.drawPath(p, st);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ "Close everything" icon

    /** Two stacked windows with an X: "close all apps". Dark when its button is focused. */
    static final class CloseIcon extends Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private boolean focused;

        CloseIcon(float density) {
            size = Math.round(26 * density);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.2f * density);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public boolean isStateful() { return true; }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean f = false;
            for (int s : state) if (s == android.R.attr.state_focused) f = true;
            if (f == focused) return false;
            focused = f;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.left + (b.width() - s) / 2f, y = b.top + (b.height() - s) / 2f;
            int fg = focused ? 0xFF10121C : 0xFFFFFFFF;
            int bg = focused ? 0xFFFFFFFF : 0xFF2A3050;
            stroke.setColor(fg);
            float r = s * 0.12f;
            // back window (offset up-right), only its visible edges
            RectF back = new RectF(x + s * 0.30f, y + s * 0.06f, x + s * 0.94f, y + s * 0.62f);
            c.drawRoundRect(back, r, r, stroke);
            // front window, filled with the button color so it hides the back one
            RectF front = new RectF(x + s * 0.06f, y + s * 0.32f, x + s * 0.70f, y + s * 0.92f);
            fill.setColor(bg);
            c.drawRoundRect(front, r, r, fill);
            c.drawRoundRect(front, r, r, stroke);
            // X in the front window
            float cx = front.centerX(), cy = front.centerY(), k = s * 0.12f;
            c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke);
            c.drawLine(cx + k, cy - k, cx - k, cy + k, stroke);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
