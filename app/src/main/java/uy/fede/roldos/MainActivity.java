package uy.fede.roldos;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RoldOS: a deliberately tiny TV launcher. Framework widgets only (no AndroidX),
 * one background thread, tile art pre-rendered to small RGB_565 bitmaps.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "roldos";
    private static final String KEY_FAVS = "favs";
    /** Seeded as favorites on first run, if installed. */
    private static final String[] DEFAULT_FAVS = {
            "io.gh.reisxd.tizentube.cobalt", "com.netflix.mediaclient", "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom", "com.spotify.tv.android", "com.google.android.youtube.tv"};
    /** Never shown as tiles: the stock launcher (kept alive for calibration) and our old shortcut app. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            HomeKeyService.STOCK_PKG, "uy.fede.proyector"));
    private static final String LUMINA_ACTION = "com.htc.intent.action.";
    private static final String INPUT_VIEWER_PKG = "com.softwinner.awlivetv";
    /** Value the input viewer expects. This projector has a single physical HDMI port. */
    private static final String HDMI_SOURCE = "HDMI1";
    /** Background apps that aren't launchable but are worth closing too. */
    private static final String[] EXTRA_CLOSE = {"com.android.vending", INPUT_VIEWER_PKG};
    /** Must keep running: the stock launcher hosts calibration, vafocus runs autofocus. */
    private static final Set<String> NEVER_CLOSE = new HashSet<>(Arrays.asList(
            HomeKeyService.STOCK_PKG, "com.hysd.vafocus"));
    private static final int[] PALETTE = {
            0xFF2B3A67, 0xFF41306B, 0xFF1F5A54, 0xFF6B3A2B, 0xFF52305E,
            0xFF23506B, 0xFF6B2D4A, 0xFF3D5A27};

    static final class App {
        final ComponentName cn;
        final String label;
        final boolean leanback;
        App(ComponentName cn, String label, boolean leanback) {
            this.cn = cn; this.label = label; this.leanback = leanback;
        }
        String pkg() { return cn.getPackageName(); }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> art = new HashMap<>();
    private final List<String> favs = new ArrayList<>();
    private List<App> apps = new ArrayList<>();

    private float density;
    private ScrollView vscroll;
    private LinearLayout favSection, favRow, allRow;
    private HorizontalScrollView favScroll, allScroll;
    private TextView favCaption, allCaption, settingsCaption, clock, date, closeHint;
    private View closeBtn;
    private int generation;
    private boolean resumed, needRender, favsLoaded;
    /** False until the user moves with the D-pad: until then, keep focus on the first tile. */
    private boolean userMoved;
    private LinearLayout topBar;
    /** The last tile that had focus, so Down from the buttons returns to it. */
    private View lastTile;

    /** The topmost row of tiles currently on screen. */
    private LinearLayout firstRow() {
        return favSection.getVisibility() == View.VISIBLE ? favRow : allRow;
    }
    private String refocusPkg;
    private boolean refocusInFavs;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            ui.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50);
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        // Note: a 16-bit window (setFormat RGB_565) was tried: it saved only ~1.6 MB on the
        // Magcubic (most "Graphics" memory is driver-side) and made the gradient band. Not worth it.
        getWindow().setBackgroundDrawable(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF161C33, 0xFF0C0F1A, 0xFF07080D}));
        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(tick);
        tick.run();
        if (needRender) { needRender = false; render(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home always lands on the start. (Don't check "resumed": Android pauses the activity
        // right before delivering a new intent, even when it was already on screen.)
        userMoved = false;
        vscroll.scrollTo(0, 0);
        favScroll.scrollTo(0, 0);
        allScroll.scrollTo(0, 0);
        focusFirst();
    }

    @Override
    public void onBackPressed() { /* a home screen has nowhere to go back to */ }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Running low while an app is in front: drop all tile art; rebuilt on return.
        if (level >= TRIM_MEMORY_BACKGROUND && !resumed) {
            favRow.removeAllViews();
            allRow.removeAllViews();
            art.clear();
            needRender = true;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(pkgReceiver);
        io.shutdownNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !userMoved) {
            View cur = getCurrentFocus();
            if (cur == null || !(cur.getTag() instanceof App)) focusFirst();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    userMoved = true;
            }
            // The button sits top-right, so Android's geometric focus search can't reach it
            // from tiles on the left. Route Up/Down between the first row and the button by hand.
            View cur = getCurrentFocus();
            if (cur != null) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && cur.getParent() == firstRow()) {
                    closeBtn.requestFocus();
                    vscroll.smoothScrollTo(0, 0);
                    return true;
                }
                if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && cur == closeBtn) {
                    if (lastTile != null && lastTile.isAttachedToWindow()) lastTile.requestFocus();
                    else focusFirst();
                    return true;
                }
            }
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            View v = getCurrentFocus();
            if (v != null && v.getTag() instanceof App) { showMenu((App) v.getTag()); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ UI skeleton

    private View buildUi() {
        vscroll = new ScrollView(this);
        vscroll.setVerticalScrollBarEnabled(false);
        vscroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(26), dp(48), dp(24));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        vscroll.addView(col);

        // Top bar: clock on the left, quick actions on the right.
        LinearLayout top = topBar = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(9), 0, dp(9), dp(6));
        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);
        clock = text(38, 0xFFFFFFFF, true);
        date = text(14, 0xFFA9B0CC, false);
        timeBox.addView(clock);
        timeBox.addView(date);
        top.addView(timeBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final boolean projector = isProjector();
        // "Close everything": round icon button; its name appears beside it while focused.
        closeHint = text(15, 0xFFFFFFFF, false);
        closeHint.setText(R.string.chip_close);
        closeHint.setVisibility(View.INVISIBLE);
        closeHint.setPadding(0, 0, dp(12), 0);
        top.addView(closeHint);
        ImageView close = new ImageView(this);
        close.setImageDrawable(new TileArt.CloseIcon(density));
        close.setScaleType(ImageView.ScaleType.CENTER);
        StateListDrawable cbg = new StateListDrawable();
        cbg.addState(new int[]{android.R.attr.state_focused}, oval(0xFFFFFFFF));
        cbg.addState(new int[]{}, oval(0xFF2A3050));
        close.setBackground(cbg);
        close.setFocusable(true);
        close.setClickable(true);
        close.setContentDescription(getString(R.string.chip_close));
        close.setOnClickListener(v -> closeEverything());
        close.setOnFocusChangeListener((v, has) -> {
            closeHint.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            float s = has ? 1.1f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(110).start();
        });
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        closeBtn = close;
        col.addView(top);

        // Favorites row
        favSection = new LinearLayout(this);
        favSection.setOrientation(LinearLayout.VERTICAL);
        favSection.setClipChildren(false);
        favSection.setClipToPadding(false);
        favCaption = caption();
        favScroll = hscroll();
        favRow = row();
        favScroll.addView(favRow);
        favSection.addView(header(R.string.row_favs));
        favSection.addView(favScroll);
        favSection.addView(favCaption);
        col.addView(favSection);

        // All apps row
        allCaption = caption();
        allScroll = hscroll();
        allRow = row();
        allScroll.addView(allRow);
        col.addView(header(R.string.row_apps));
        col.addView(allScroll);
        col.addView(allCaption);

        // Settings row: projector controls as tiles.
        settingsCaption = caption();
        HorizontalScrollView hs = hscroll();
        LinearLayout r = row();
        hs.addView(r);
        if (projector) {
            r.addView(settingsTile(TileArt.Kind.CALIBRATION, R.string.tile_calibration, this::openProjection));
            r.addView(settingsTile(TileArt.Kind.INPUT, R.string.tile_input, () -> openInput(HDMI_SOURCE)));
            r.addView(settingsTile(TileArt.Kind.SETTINGS, R.string.tile_settings, () -> lumina("OPEN_MAIN_SETTINGS")));
            r.addView(settingsTile(TileArt.Kind.WIFI, R.string.tile_wifi, () -> lumina("OPEN_WIFI_SETTINGS")));
        } else {
            r.addView(settingsTile(TileArt.Kind.SETTINGS, R.string.tile_settings,
                    () -> open(new Intent(Settings.ACTION_SETTINGS))));
            r.addView(settingsTile(TileArt.Kind.WIFI, R.string.tile_wifi,
                    () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        }
        col.addView(header(R.string.row_settings));
        col.addView(hs);
        col.addView(settingsCaption);
        return vscroll;
    }

    private View settingsTile(TileArt.Kind kind, int labelRes, Runnable action) {
        final String label = getString(labelRes);
        final int w = dp(176), h = w * 9 / 16;
        FrameLayout f = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(dp(9), dp(12), dp(9), dp(12));
        f.setLayoutParams(lp);
        f.setFocusable(true);
        f.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF232A47);
        bg.setCornerRadius(dp(12));
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setForeground(focusRing());
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setImageBitmap(TileArt.settingsBanner(kind, label, w, h));
        f.addView(iv, new FrameLayout.LayoutParams(w, h));
        f.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(110).start();
            if (has) {
                showCaption(settingsCaption, label);
                lastTile = v;
                // Last row: scroll all the way down so the tile and its caption are fully visible.
                vscroll.post(() -> vscroll.smoothScrollTo(0, vscroll.getChildAt(0).getHeight()));
            }
        });
        f.setOnClickListener(v -> action.run());
        return f;
    }

    private StateListDrawable focusRing() {
        StateListDrawable ring = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0);
        on.setStroke(dp(3), 0xFFFFFFFF);
        on.setCornerRadius(dp(12));
        ring.addState(new int[]{android.R.attr.state_focused}, on);
        ring.addState(new int[]{}, new ColorDrawable(0));
        return ring;
    }

    /** Shows the focused tile's name under its own row and clears the other rows' captions. */
    private void showCaption(TextView which, String label) {
        for (TextView c : new TextView[]{favCaption, allCaption, settingsCaption}) {
            if (c != null) c.setText(c == which ? label : "");
        }
    }

    private TextView header(int res) {
        TextView t = text(17, 0xFFE4E7F2, true);
        t.setText(res);
        t.setPadding(dp(9), dp(16), 0, 0);
        return t;
    }

    private TextView caption() {
        TextView t = text(15, 0xFFFFFFFF, false);
        t.setPadding(dp(9), 0, 0, 0);
        t.setSingleLine(true);
        t.setMinHeight(dp(20));
        return t;
    }

    private HorizontalScrollView hscroll() {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return h;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(0, 0, dp(48), 0);
        return r;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        final int gen = ++generation;
        io.execute(() -> {
            List<App> list = queryApps();
            ui.post(() -> {
                if (gen != generation || isDestroyed()) return;
                apps = list;
                if (!favsLoaded) loadFavs();
                if (resumed || !needRender) render(); else needRender = true;
            });
        });
    }

    private List<App> queryApps() {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, App> out = new LinkedHashMap<>();
        Set<String> leanbackPkgs = new HashSet<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            boolean lb = cat.equals(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> found = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo ri : found) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || !ai.enabled || getPackageName().equals(ai.packageName)
                        || HIDDEN.contains(ai.packageName)) continue;
                if (!lb && leanbackPkgs.contains(ai.packageName)) continue; // prefer the TV entry
                ComponentName cn = new ComponentName(ai.packageName, ai.name);
                String key = cn.flattenToShortString();
                if (out.containsKey(key)) continue;
                if (lb) leanbackPkgs.add(ai.packageName);
                out.put(key, new App(cn, String.valueOf(ri.loadLabel(pm)).trim(), lb));
            }
        }
        List<App> list = new ArrayList<>(out.values());
        final Collator col = Collator.getInstance();
        Collections.sort(list, (a, b) -> col.compare(a.label, b.label));
        return list;
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void loadFavs() {
        favsLoaded = true;
        favs.clear();
        SharedPreferences p = prefs();
        if (p.contains(KEY_FAVS)) {
            String s = p.getString(KEY_FAVS, "");
            if (!s.isEmpty()) favs.addAll(Arrays.asList(s.split(",")));
        } else {
            for (String pkg : DEFAULT_FAVS) if (findByPkg(pkg) != null) favs.add(pkg);
            saveFavs();
        }
    }

    private void saveFavs() { prefs().edit().putString(KEY_FAVS, TextUtils.join(",", favs)).apply(); }

    private App findByPkg(String pkg) {
        for (App a : apps) if (a.pkg().equals(pkg)) return a;
        return null;
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        String focusPkg = null;
        boolean inFavs = false;
        View cur = getCurrentFocus();
        if (cur != null && cur.getTag() instanceof App) {
            focusPkg = ((App) cur.getTag()).pkg();
            inFavs = cur.getParent() == favRow;
        }
        if (refocusPkg != null) { focusPkg = refocusPkg; inFavs = refocusInFavs; refocusPkg = null; }
        boolean hadTileFocus = cur == null || cur.getTag() instanceof App;

        favRow.removeAllViews();
        for (String pkg : favs) {
            App a = findByPkg(pkg);
            if (a != null) favRow.addView(tile(a, true));
        }
        favSection.setVisibility(favRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        allRow.removeAllViews();
        for (App a : apps) allRow.addView(tile(a, false));

        // Focus on a chip the user picked: leave it. (Before any D-pad move it was the system's
        // default pick, not the user's, so we move it to the first tile.)
        if (!hadTileFocus && userMoved) return;
        View target = focusPkg == null ? null : findTile(inFavs ? favRow : allRow, focusPkg);
        if (target != null) target.requestFocus(); else focusFirst();
    }

    private View findTile(LinearLayout row, String pkg) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v.getTag() instanceof App && ((App) v.getTag()).pkg().equals(pkg)) return v;
        }
        return null;
    }

    private void focusFirst() {
        if (favRow.getChildCount() > 0) favRow.getChildAt(0).requestFocus();
        else if (allRow.getChildCount() > 0) allRow.getChildAt(0).requestFocus();
    }

    private View tile(final App a, final boolean big) {
        final int w = dp(big ? 224 : 176), h = w * 9 / 16;
        FrameLayout f = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(dp(9), dp(12), dp(9), dp(12));
        f.setLayoutParams(lp);
        f.setTag(a);
        f.setFocusable(true);
        f.setClickable(true);
        f.setLongClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorFor(a));
        bg.setCornerRadius(dp(12));
        f.setBackground(bg);
        f.setClipToOutline(true);

        f.setForeground(focusRing());

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        f.addView(iv, new FrameLayout.LayoutParams(w, h));
        loadArt(a, w, h, iv);

        final TextView cap = big ? favCaption : allCaption;
        f.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(110).start();
            if (has) {
                showCaption(cap, a.label);
                lastTile = v;
                // First row: keep the clock and buttons in view.
                if (big) vscroll.smoothScrollTo(0, 0);
            }
        });
        f.setOnClickListener(v -> launch(a));
        f.setOnLongClickListener(v -> { showMenu(a); return true; });
        return f;
    }

    private int colorFor(App a) {
        return PALETTE[(a.pkg().hashCode() & 0x7fffffff) % PALETTE.length];
    }

    private void loadArt(final App a, final int w, final int h, final ImageView iv) {
        final String key = a.cn.flattenToShortString() + "@" + w;
        iv.setTag(key);
        Bitmap cached = art.get(key);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        final int color = colorFor(a);
        io.execute(() -> {
            final Bitmap b = drawArt(a, w, h, color);
            if (b == null) return;
            ui.post(() -> {
                art.put(key, b);
                if (key.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        });
    }

    /** Renders a tile's final pixels once, at exact size, in 16-bit color. */
    private Bitmap drawArt(App a, int w, int h, int color) {
        PackageManager pm = getPackageManager();
        Drawable banner, icon = null;
        try {
            ActivityInfo ai = pm.getActivityInfo(a.cn, 0);
            banner = ai.loadBanner(pm);
            if (banner == null) icon = ai.loadIcon(pm);
        } catch (Exception e) {
            return null;
        }
        if (banner == null) return TileArt.appBanner(icon, a.label, w, h); // generated per-app design
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        c.drawColor(color);
        banner.setBounds(0, 0, w, h);
        banner.draw(c);
        return b;
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(this).format(now));
        Locale loc = Locale.getDefault();
        String d = new SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM"), loc).format(now);
        date.setText(d.isEmpty() ? d : d.substring(0, 1).toUpperCase(loc) + d.substring(1));
    }

    // ------------------------------------------------------------------ actions

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(a.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!open(i)) Toast.makeText(this, getString(R.string.err_launch, a.label), Toast.LENGTH_SHORT).show();
    }

    private boolean open(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_HOME)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isProjector() {
        try {
            getPackageManager().getPackageInfo(HomeKeyService.STOCK_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Calibration: open the stock settings menu; HomeKeyService then presses its "Projection" tile. */
    private void openProjection() {
        HomeKeyService.requestProjection();
        lumina("OPEN_MAIN_SETTINGS");
    }

    /** Opens a video input in the projector's input viewer, the same way the stock launcher does. */
    private void openInput(String source) {
        Intent i = new Intent()
                .setComponent(new ComponentName(INPUT_VIEWER_PKG, INPUT_VIEWER_PKG + ".MainActivity"))
                .putExtra("input_source", source);
        if (!open(i)) lumina("OPEN_SOURCE");
    }

    /**
     * Ends every other app's background process to free memory. Apps that are actively doing
     * something (e.g. music playing in a foreground service) are left alone by the system.
     */
    private void closeEverything() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final long before = availMem(am);
        Set<String> pkgs = new HashSet<>(Arrays.asList(EXTRA_CLOSE));
        for (App a : apps) pkgs.add(a.pkg());
        pkgs.remove(getPackageName());
        pkgs.removeAll(NEVER_CLOSE);
        for (String p : pkgs) {
            try { am.killBackgroundProcesses(p); } catch (RuntimeException ignored) { }
        }
        ui.postDelayed(() -> {
            long freedMb = Math.max(0, availMem(am) - before) / (1024 * 1024);
            Toast.makeText(this, freedMb > 0 ? getString(R.string.closed_freed, freedMb)
                    : getString(R.string.closed_nothing), Toast.LENGTH_SHORT).show();
        }, 1200);
    }

    private static long availMem(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    /** Asks the projector's stock launcher (kept alive in the background) to open one of its menus. */
    private void lumina(String action) {
        Intent i = new Intent(LUMINA_ACTION + action);
        i.setComponent(new ComponentName(HomeKeyService.STOCK_PKG,
                HomeKeyService.STOCK_PKG + ".receiver.ProjectModeReceiver"));
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(i);
    }

    private void showMenu(final App a) {
        final String pkg = a.pkg();
        final boolean isFav = favs.contains(pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(getString(isFav ? R.string.m_remove_fav : R.string.m_add_fav));
        actions.add(() -> {
            if (isFav) favs.remove(pkg); else favs.add(pkg);
            saveFavs();
            refocusPkg = pkg;
            refocusInFavs = !isFav;
            render();
        });
        if (isFav) {
            final int idx = favs.indexOf(pkg);
            if (idx > 0) { labels.add(getString(R.string.m_left)); actions.add(() -> moveFav(idx, idx - 1)); }
            if (idx < favs.size() - 1) { labels.add(getString(R.string.m_right)); actions.add(() -> moveFav(idx, idx + 1)); }
        }
        labels.add(getString(R.string.m_info));
        actions.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        labels.add(getString(R.string.m_uninstall));
        actions.add(() -> open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void moveFav(int from, int to) {
        String pkg = favs.remove(from);
        favs.add(to, pkg);
        saveFavs();
        refocusPkg = pkg;
        refocusInFavs = true;
        render();
    }

    private int dp(float v) { return Math.round(v * density); }
}
