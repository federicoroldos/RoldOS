# Swaps TizenTube for YouTube in the README screenshots (owner's call, 2026-11-09: it looks nicer).
# The screenshots were taken with TizenTube as the first favorite; this puts YouTube's real Android TV
# banner (shots/youtube-banner.png, res/dN.png of com.google.android.youtube.tv 5.30.304) on its tile
# and writes "YouTube" where the name was. The originals in shots/ stay untouched; the results are
# shots/*-youtube.png, which the promo pages use.
# Usage (PowerShell, from the repo root):  .\docs\promo\youtube.ps1   then  .\docs\promo\render.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System; using System.Drawing; using System.Drawing.Drawing2D; using System.Drawing.Imaging;
using System.Drawing.Text;

public static class YtSwap {
    // The unfocused TizenTube tile in closed.png (no ring, no lift): the "before" picture.
    const double NX = 115, NY = 317.6, NW = 470, NH = 265.3, RAD = 24;
    static readonly Color BG = Color.FromArgb(20, 21, 22);

    static double[] Sample(Bitmap b, double x, double y) {
        int x0 = (int)Math.Floor(x), y0 = (int)Math.Floor(y);
        double fx = x - x0, fy = y - y0; double[] r = new double[3];
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            int xi = Math.Max(0, Math.Min(b.Width - 1, x0 + i)), yj = Math.Max(0, Math.Min(b.Height - 1, y0 + j));
            Color c = b.GetPixel(xi, yj); double w = (i == 0 ? 1 - fx : fx) * (j == 0 ? 1 - fy : fy);
            r[0] += c.R * w; r[1] += c.G * w; r[2] += c.B * w;
        }
        return r;
    }

    // How much of pixel (u, v) (tile coordinates) the rounded tile, grown by g, covers, 0..1.
    static double Cover(double u, double v, double g) {
        double dx = Math.Max(Math.Max(NX + RAD - u, u - (NX + NW - RAD)), 0);
        double dy = Math.Max(Math.Max(NY + RAD - v, v - (NY + NH - RAD)), 0);
        double d = Math.Sqrt(dx * dx + dy * dy) - RAD;               // > 0 outside the corner arc
        double edge = Math.Min(Math.Min(u - NX, NX + NW - u), Math.Min(v - NY, NY + NH - v));
        return Math.Max(0, Math.Min(1, Math.Min(0.5 + g - d, edge + 0.5 + g)));
    }

    // Repaints the tile scaled by `s` around (cx, cy) in `shot`, keeping its border (color `bc`).
    // `grow` (screen px) lets the new banner reach under a solid border, so no seam shows inside it.
    public static void Tile(Bitmap shot, Bitmap before, Bitmap banner, double s, double cx, double cy, Color bc, double grow) {
        double ncx = NX + NW / 2, ncy = NY + NH / 2;
        double l = cx - NW * s / 2, t = cy - NH * s / 2, r = cx + NW * s / 2, btm = cy + NH * s / 2;
        double[] B = { bc.R, bc.G, bc.B };
        for (int y = (int)(t - 10); y <= (int)(btm + 10); y++)
        for (int x = (int)(l - 10); x <= (int)(r + 10); x++) {
            double px = x + 0.5, py = y + 0.5;
            double u = (px - cx) / s + ncx, v = (py - cy) / s + ncy;
            // New content: YouTube's banner on the rounded tile, over the background.
            double cov = Cover(u, v, grow / s);
            double[] yt = Sample(banner, (u - NX) / NW * banner.Width - 0.5, (v - NY) / NH * banner.Height - 0.5);
            double[] N = { BG.R + (yt[0] - BG.R) * cov, BG.G + (yt[1] - BG.G) * cov, BG.B + (yt[2] - BG.B) * cov };
            Color p = shot.GetPixel(x, y);
            double[] P = { p.R, p.G, p.B };
            double inside = Math.Min(Math.Min(px - l, r - px), Math.Min(py - t, btm - py));
            double a = 0;
            if (inside < 14) {
                // Near the edge the screenshot = old tile + some of the border on top. Find how much
                // border each pixel has and lay the same amount over the new tile.
                double[] O = Sample(before, u - 0.5, v - 0.5);
                double num = 0, den = 0;
                for (int k = 0; k < 3; k++) { num += (P[k] - O[k]) * (B[k] - O[k]); den += (B[k] - O[k]) * (B[k] - O[k]); }
                if (den > 400) a = Math.Max(0, Math.Min(1, num / den));
            }
            shot.SetPixel(x, y, Color.FromArgb(
                (int)Math.Round(a * B[0] + (1 - a) * N[0]),
                (int)Math.Round(a * B[1] + (1 - a) * N[1]),
                (int)Math.Round(a * B[2] + (1 - a) * N[2])));
        }
    }

    // Replaces the text `oldText` whose ink box is (x0, y0, x1, y1) with `newText`, same font and size.
    public static void Text(Bitmap shot, string fontFile, string oldText, string newText,
                            int x0, int y0, int x1, int y1, Color ink, Color bg) {
        var pfc = new PrivateFontCollection(); pfc.AddFontFile(fontFile);
        var fam = pfc.Families[0]; var fmt = StringFormat.GenericTypographic;
        // Find the size at which the old text's ink is as wide as on screen.
        double lo = 10, hi = 200; RectangleF ob = RectangleF.Empty;
        for (int i = 0; i < 40; i++) {
            double m = (lo + hi) / 2;
            using (var gp = new GraphicsPath(FillMode.Winding)) { gp.AddString(oldText, fam, 0, (float)m, PointF.Empty, fmt); ob = gp.GetBounds(); }
            if (ob.Width < x1 + 1 - x0) lo = m; else hi = m;
        }
        float size = (float)lo;
        using (var gp = new GraphicsPath(FillMode.Winding)) { gp.AddString(oldText, fam, 0, size, PointF.Empty, fmt); ob = gp.GetBounds(); }
        // Same origin as the old text, so the new one sits on the same baseline.
        float ox = x0 - ob.Left, oy = y0 - ob.Top;
        using (var g = Graphics.FromImage(shot)) {
            g.SmoothingMode = SmoothingMode.AntiAlias; g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            using (var br = new SolidBrush(bg)) g.FillRectangle(br, x0 - 4, y0 - 4, x1 - x0 + 9, y1 - y0 + 9);
            using (var gp = new GraphicsPath(FillMode.Winding)) {
                gp.AddString(newText, fam, 0, size, new PointF(ox, oy), fmt);
                using (var br = new SolidBrush(ink)) g.FillPath(br, gp);
            }
        }
        Console.WriteLine("  \"" + oldText + "\" -> \"" + newText + "\" at " + size.ToString("0.0") + " px, old ink height "
            + ob.Height.ToString("0.0") + " vs " + (y1 + 1 - y0) + " on screen");
    }
}
'@

$shots = Join-Path $PSScriptRoot 'shots'
$fonts = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'app\src\main\assets\fonts'
function Load($n) { New-Object System.Drawing.Bitmap (Join-Path $shots $n) }
function Save($bmp, $n) { $bmp.Save((Join-Path $shots $n), [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose(); Write-Host "wrote shots/$n" }
$before = Load 'closed.png'
$banner = Load 'youtube-banner.png'

# home.png: TizenTube focused (1.05x, white ring) and its name under the tile (Unbounded Light).
$b = Load 'home.png'
[YtSwap]::Tile($b, $before, $banner, 1.05, 350, 450.25, [System.Drawing.Color]::White, 1.5)
[YtSwap]::Text($b, (Join-Path $fonts 'Unbounded-Light.ttf'), 'TizenTube', 'YouTube', 115, 615, 291, 640,
    [System.Drawing.Color]::White, [System.Drawing.Color]::FromArgb(20, 21, 22))
Save $b 'home-youtube.png'

# moving.png: the tile lifted for a move (1.08x, dashed amber border, 12.7 px higher).
$b = Load 'moving.png'
[YtSwap]::Tile($b, $before, $banner, 1.08, 350, 437.6, [System.Drawing.Color]::FromArgb(255, 201, 77), 0)
Save $b 'moving-youtube.png'

# menu.png: the hold-OK menu's title (Unbounded Regular).
$b = Load 'menu.png'
[YtSwap]::Text($b, (Join-Path $fonts 'Unbounded-Regular.ttf'), 'TizenTube', 'YouTube', 636, 316, 880, 350,
    [System.Drawing.Color]::FromArgb(244, 244, 245), [System.Drawing.Color]::FromArgb(30, 31, 34))
Save $b 'menu-youtube.png'

$before.Dispose(); $banner.Dispose()
