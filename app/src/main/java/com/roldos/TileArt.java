package com.roldos;

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
        if (base == 0) return new int[]{0xFF3A3D42, 0xFF1A1B1E};
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

    enum Kind { CALIBRATION, SETTINGS, HDMI, WIRELESS_CAST, WIRED_CAST, ORIGINAL_HOME }

    /** A Settings-row tile: same layout as the app banners, with a hand-drawn line icon. */
    static Bitmap settingsBanner(Kind kind, String label, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        int[] g;
        switch (kind) {
            case CALIBRATION:   g = new int[]{0xFF6A47C2, 0xFF22134A}; break;
            case HDMI:          g = new int[]{0xFF1F8F84, 0xFF0B2E2B}; break;
            case WIRELESS_CAST: g = new int[]{0xFFC8642C, 0xFF3A1609}; break;
            case WIRED_CAST:    g = new int[]{0xFFA83A78, 0xFF330E24}; break;
            case ORIGINAL_HOME: g = new int[]{0xFF8A7A3A, 0xFF241F0B}; break;
            default:            g = new int[]{0xFF2C6FD1, 0xFF0D2049}; break; // SETTINGS (blue)
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
            case HDMI: {
                // An HDMI port seen head-on: wide top edge, chamfered lower corners,
                // and the contact strip inside.
                p.moveTo(x + s * 0.06f, y + s * 0.28f);
                p.lineTo(x + s * 0.94f, y + s * 0.28f);
                p.lineTo(x + s * 0.94f, y + s * 0.52f);
                p.lineTo(x + s * 0.80f, y + s * 0.72f);
                p.lineTo(x + s * 0.20f, y + s * 0.72f);
                p.lineTo(x + s * 0.06f, y + s * 0.52f);
                p.close();
                c.drawPath(p, st);
                RectF strip = new RectF(x + s * 0.24f, y + s * 0.42f, x + s * 0.76f, y + s * 0.54f);
                c.drawRoundRect(strip, s * 0.03f, s * 0.03f, fill);
                break;
            }
            case WIRELESS_CAST: {
                // A screen on a stand receiving waves from its top-right corner.
                RectF scr = new RectF(x + s * 0.06f, y + s * 0.30f, x + s * 0.72f, y + s * 0.78f);
                c.drawRoundRect(scr, s * 0.06f, s * 0.06f, st);
                c.drawLine(x + s * 0.39f, y + s * 0.78f, x + s * 0.39f, y + s * 0.90f, st);
                c.drawLine(x + s * 0.25f, y + s * 0.92f, x + s * 0.53f, y + s * 0.92f, st);
                float ox = x + s * 0.72f, oy = y + s * 0.30f; // waves centered on the corner
                for (int i = 1; i <= 2; i++) {
                    float rr = s * 0.13f * i;
                    c.drawArc(new RectF(ox - rr, oy - rr, ox + rr, oy + rr), 270, 90, false, st);
                }
                c.drawCircle(ox, oy, s * 0.04f, fill);
                break;
            }
            case WIRED_CAST: {
                // A phone and a screen side by side, joined by a cable that dips below both
                // (a clean U from the phone's bottom up into the screen's bottom).
                RectF scr = new RectF(x + s * 0.34f, y + s * 0.10f, x + s * 0.96f, y + s * 0.54f);
                c.drawRoundRect(scr, s * 0.06f, s * 0.06f, st);
                RectF phone = new RectF(x + s * 0.04f, y + s * 0.30f, x + s * 0.24f, y + s * 0.70f);
                c.drawRoundRect(phone, s * 0.05f, s * 0.05f, st);
                Path cable = new Path();
                cable.moveTo(x + s * 0.14f, y + s * 0.70f);
                cable.cubicTo(x + s * 0.14f, y + s * 0.95f, x + s * 0.65f, y + s * 0.95f,
                        x + s * 0.65f, y + s * 0.54f);
                c.drawPath(cable, st);
                break;
            }
            case ORIGINAL_HOME: {
                // A house with a curved "go back" arrow beside it.
                p.moveTo(x + s * 0.06f, y + s * 0.50f);
                p.lineTo(x + s * 0.36f, y + s * 0.22f);
                p.lineTo(x + s * 0.66f, y + s * 0.50f);
                c.drawPath(p, st);
                Path house = new Path();
                house.moveTo(x + s * 0.14f, y + s * 0.44f);
                house.lineTo(x + s * 0.14f, y + s * 0.84f);
                house.lineTo(x + s * 0.58f, y + s * 0.84f);
                house.lineTo(x + s * 0.58f, y + s * 0.44f);
                c.drawPath(house, st);
                c.drawLine(x + s * 0.30f, y + s * 0.84f, x + s * 0.30f, y + s * 0.64f, st);
                c.drawLine(x + s * 0.30f, y + s * 0.64f, x + s * 0.42f, y + s * 0.64f, st);
                c.drawLine(x + s * 0.42f, y + s * 0.64f, x + s * 0.42f, y + s * 0.84f, st);
                // back arrow: arc from top-right curving down, arrowhead pointing left
                RectF arc = new RectF(x + s * 0.62f, y + s * 0.10f, x + s * 0.96f, y + s * 0.44f);
                c.drawArc(arc, 180, 260, false, st);
                float ax = x + s * 0.62f, ay = y + s * 0.27f;
                c.drawLine(ax, ay, ax - s * 0.06f, ay - s * 0.08f, st);
                c.drawLine(ax, ay, ax + s * 0.07f, ay - s * 0.07f, st);
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

}
