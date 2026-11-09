package com.roldos;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.database.ContentObserver;
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
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
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
    /** Projector cast apps: shown as branded tiles in the Settings row instead of in All apps. */
    private static final ComponentName WIRELESS_CAST =
            new ComponentName("com.softwinner.miracastReceiver", "com.softwinner.miracastReceiver.Miracast");
    private static final ComponentName WIRED_CAST =
            new ComponentName("com.toofifi.lineserver", "com.toofifi.lineserver.ui.MainActivity");
    /** Never shown in All apps: the stock launcher (kept alive for calibration), our old
     *  shortcut app, and the cast apps that live in the Settings row. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            HomeKeyService.STOCK_PKG, "uy.fede.proyector",
            WIRELESS_CAST.getPackageName(), WIRED_CAST.getPackageName()));
    /** B2 Graphite: the home background (flat, no gradient). Accent is amber #FFB547. */
    private static final int BG = 0xFF141516;
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
    /** Width of each row's box (see wideRow) and how much of it is on screen while focused. */
    private int rowBoxW, rowVisibleW;
    private ScrollView vscroll;
    private LinearLayout favSection, allSection, settingsSection, favRow, allRow;
    private HorizontalScrollView favScroll, allScroll;
    private TextView favCaption, allCaption, settingsCaption, clock, date;
    private View closeBtn;
    private int generation;
    private boolean resumed, needRender, favsLoaded;
    /** False until the user moves with the D-pad: until then, keep focus on the first tile. */
    private boolean userMoved;
    private LinearLayout topBar;

    /** The topmost row of tiles currently on screen. */
    private LinearLayout firstRow() {
        return favSection.getVisibility() == View.VISIBLE ? favRow : allRow;
    }
    private LinearLayout settingsRow;
    /** Per row: index of the tile last focused there (no entry = never moved along it). */
    private final Map<ViewGroup, Integer> rowMemory = new HashMap<>();

    /** Rows of tiles on screen, top to bottom. */
    private List<ViewGroup> visibleRows() {
        List<ViewGroup> rows = new ArrayList<>();
        if (mediaSection != null && mediaSection.getVisibility() == View.VISIBLE) rows.add(mediaRow);
        if (favSection.getVisibility() == View.VISIBLE && favRow.getChildCount() > 0) rows.add(favRow);
        if (allRow.getChildCount() > 0) rows.add(allRow);
        if (settingsRow != null && settingsRow.getChildCount() > 0) rows.add(settingsRow);
        return rows;
    }

    /** Focuses the tile this row was left on, or its first tile. */
    private void focusRow(ViewGroup row) {
        Integer i = rowMemory.get(row);
        int idx = i == null ? 0 : Math.max(0, Math.min(i, row.getChildCount() - 1));
        if (!row.getChildAt(idx).requestFocus()) { // that one is hidden now (e.g. a media button)
            for (int k = 0; k < row.getChildCount(); k++) if (row.getChildAt(k).requestFocus()) return;
        }
    }

    /** True for an item Left/Right can land on (media buttons can be hidden mid-row). */
    private static boolean reachable(View v) {
        return v.getVisibility() == View.VISIBLE && v.isFocusable();
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
        int screenW = getResources().getDisplayMetrics().widthPixels;
        rowBoxW = Math.round(screenW / ROW_IDLE);
        rowVisibleW = Math.round((screenW - dp(48)) / ROW_ACTIVE); // left margin 48dp, focused row 1.05x
        // Note: a 16-bit window (setFormat RGB_565) was tried: it saved only ~1.6 MB on the
        // Magcubic (most "Graphics" memory is driver-side) and made the gradient band. Not worth it.
        // Flat graphite, nothing drawn behind the rows (Federico: no transparency, no layers).
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        TileArt.labelFace = Fonts.medium(this);
        // Root: the scrolling home plus the confirmation pill floating at the bottom center.
        FrameLayout root = new FrameLayout(this);
        homeView = (ViewGroup) buildUi();
        root.addView(homeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        notice = buildNotice();
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nlp.bottomMargin = dp(40);
        root.addView(notice, nlp);
        // The app menu (hold OK) lives inside this window, never in a separate dialog: see showMenu.
        menuLayer = new FrameLayout(this);
        menuLayer.setBackgroundColor(0xFF0E0F10); // opaque: no see-through layer over the home
        menuLayer.setVisibility(View.GONE);
        root.addView(menuLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        nowPlaying = new NowPlaying(this, ui, dp(MEDIA_H), this::onNowPlaying);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        reload();

        // The projector's stock settings menu wipes the enabled-accessibility list whenever it
        // opens. Watch that setting and put our Home-key service straight back.
        // (Needs WRITE_SECURE_SETTINGS, granted once over adb by build.sh.)
        ContentResolver cr = getContentResolver();
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, a11yGuard);
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false, a11yGuard);
        ensureHomeService();

        // Wi-Fi name in the top bar, kept current as the connection comes and goes.
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(netWatch);
        } catch (RuntimeException ignored) { }
        updateWifi();
    }

    private final ContentObserver a11yGuard = new ContentObserver(ui) {
        @Override public void onChange(boolean selfChange) { ensureHomeService(); }
    };

    /** Makes sure our accessibility service is in the enabled list (no-op if it already is). */
    private void ensureHomeService() {
        ContentResolver cr = getContentResolver();
        ComponentName me = new ComponentName(this, HomeKeyService.class);
        String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean listed = false;
        if (cur != null) {
            for (String s : cur.split(":")) if (me.equals(ComponentName.unflattenFromString(s))) listed = true;
        }
        try {
            if (!listed) {
                String mine = me.flattenToShortString();
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        TextUtils.isEmpty(cur) ? mine : cur + ":" + mine);
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (SecurityException ignored) {
            // Permission not granted (run build.sh install): the user re-enables it by hand.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        stopped = false; // after onNewIntent (see stopped)
        HomeKeyService.onLauncherShown();
        ensureHomeService();
        ui.removeCallbacks(tick);
        tick.run();
        if (needRender) { needRender = false; render(); }
        armAudioGuard();
        updateWifi();
        nowPlaying.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(audioGuard);
        ui.removeCallbacks(wifiKick);
        nowPlaying.stop(); // only follows the players while the home screen shows
        if (holdingAudioFocus) {
            holdingAudioFocus = false;
            audio().abandonAudioFocus(quietFocus);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home always lands on the start. (Don't check "resumed": Android pauses the activity
        // right before delivering a new intent, even when it was already on screen.)
        // Home also drops whatever was in progress: an open app menu, or a tile mid-move
        // (it stays where it was moved to). Otherwise move mode would keep eating every key.
        if (menuOpen()) closeMenu();
        if (movingPkg != null) endMove();
        userMoved = false;
        View[] rows = {(View) mediaRow.getParent(), favScroll, allScroll, (View) settingsRow.getParent()};
        if (!stopped) {
            // Home pressed while RoldOS was already showing (Federico): glide back instead of
            // jumping. The screen rises to the top while each row slides back to its first tile,
            // one after another from the top, for a soft cascade.
            slide(vscroll, "scrollY", 0, HOME_MS, 0);
            int delay = 0;
            for (View r : rows) {
                if (r.getScrollX() == 0) continue;
                slide(r, "scrollX", 0, HOME_MS, delay);
                delay += HOME_STAGGER_MS;
            }
        } else { // coming back from an app: the home wasn't visible, just reset
            stopSlides();
            vscroll.scrollTo(0, 0);
            for (View r : rows) r.scrollTo(0, 0);
        }
        rowMemory.clear(); // every row is back at its start, so is its memory
        focusFirst(); // its own scroll requests match the glides above, so they don't restart them
    }

    /** Home while already home: the glide back to the start (a bit slower than a normal step). */
    private static final int HOME_MS = 560, HOME_STAGGER_MS = 60;
    /**
     * True from the moment another app covered the home (onStop) until RoldOS is back in front
     * (cleared in onResume, which always comes after onNewIntent, whatever order Android runs
     * onStart in).
     */
    private boolean stopped = true;

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
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
        getContentResolver().unregisterContentObserver(a11yGuard);
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netWatch);
        } catch (RuntimeException ignored) { }
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
        if (handleMoveKey(e)) return true;
        if (handleHoldOk(e)) return true;
        if (menuOpen()) {
            int c = e.getKeyCode();
            if (c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_ESCAPE || c == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) closeMenu();
                return true;
            }
            return super.dispatchKeyEvent(e); // Up/Down walk the menu; the home behind is blocked
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    userMoved = true;
            }
            // The button sits top-right, so Android's geometric focus search can't reach it
            // from tiles on the left. Route Up/Down between the first row and the button by hand.
            View cur = getCurrentFocus();
            int code = e.getKeyCode();
            // Left/Right never leave the row. Android's geometric search used to jump to another
            // row at a row's end (or mid-slide, when rows are scaled and moving): step by index.
            if (cur != null && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (cur == closeBtn) return true; // nothing beside it
                if (cur.getParent() instanceof ViewGroup
                        && cur.getParent().getParent() instanceof HorizontalScrollView) {
                    ViewGroup row = (ViewGroup) cur.getParent();
                    int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    int i = row.indexOfChild(cur) + dir;
                    while (i >= 0 && i < row.getChildCount() && !reachable(row.getChildAt(i))) i += dir;
                    if (i >= 0 && i < row.getChildCount()) row.getChildAt(i).requestFocus();
                    return true; // at the row's end: stay put
                }
            }
            // Up/Down are routed by hand too: each row remembers the tile you were last on and
            // you land back there (the first tile if you never moved along it). Geometric search
            // picked whatever tile sat below on screen: from a row's far right you landed mid-row.
            if (cur != null && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                List<ViewGroup> rows = visibleRows();
                if (cur == closeBtn) {
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN && !rows.isEmpty()) focusRow(rows.get(0));
                    return true;
                }
                int r = rows.indexOf(cur.getParent());
                if (r >= 0) {
                    int t = r + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    if (t < 0) closeBtn.requestFocus(); // the button sits top-right, out of reach otherwise
                    else if (t < rows.size()) focusRow(rows.get(t));
                    return true; // below the last row: stay put
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
        // Scrolling is ours (see onFocusMoved): Android's own jump-to-focus is switched off.
        vscroll = new ScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
        };
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
        top.setPadding(dp(9), 0, dp(12), dp(6));
        top.setClipChildren(false); // the focused button grows slightly; don't cut its edge
        top.setClipToPadding(false);
        // Brand, left of the clock: the RoldOS wordmark (Unbounded, white "Rold" + amber "OS") and a hairline.
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.wordmark);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)); // Unbounded is wide: 30dp keeps it ~165dp across
        logoLp.setMarginEnd(dp(22));
        top.addView(logo, logoLp);
        View hairline = new View(this);
        hairline.setBackgroundColor(0xFF2E3033);
        LinearLayout.LayoutParams hairLp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(46));
        hairLp.setMarginEnd(dp(22));
        top.addView(hairline, hairLp);
        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);
        clock = text(38, 0xFFFFFFFF, true);
        date = text(14, 0xFF8F949B, false);
        timeBox.addView(clock);
        timeBox.addView(date);
        top.addView(timeBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final boolean projector = isProjector();
        // Wi-Fi status: signal arcs + the network's name ("No Wi-Fi" in amber when offline).
        LinearLayout wifiBox = new LinearLayout(this);
        wifiBox.setOrientation(LinearLayout.HORIZONTAL);
        wifiBox.setGravity(Gravity.CENTER_VERTICAL);
        ImageView wifiIcon = new ImageView(this);
        wifiIcon.setImageDrawable(wifiGlyph);
        wifiBox.addView(wifiIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        wifiText = text(15, 0xFFB8BCC2, false);
        wifiText.setSingleLine(true);
        wifiText.setEllipsize(TextUtils.TruncateAt.END);
        wifiText.setMaxWidth(dp(260));
        LinearLayout.LayoutParams wtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wtLp.setMarginStart(dp(8));
        wifiBox.addView(wifiText, wtLp);
        LinearLayout.LayoutParams wbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wbLp.setMarginEnd(dp(22));
        top.addView(wifiBox, wbLp);
        // "Close all apps": a plain text pill (white with dark text while focused).
        TextView close = text(15, 0, true);
        close.setText(R.string.chip_close);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(20), dp(10), dp(20), dp(10));
        close.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF10121C, 0xFFFFFFFF}));
        StateListDrawable cbg = new StateListDrawable();
        cbg.addState(new int[]{android.R.attr.state_focused}, pill(0xFFFFFFFF));
        cbg.addState(new int[]{}, pill(0xFF25272A));
        close.setBackground(cbg);
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(v -> closeEverything());
        close.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.06f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(EASE).start();
        });
        top.addView(close);
        closeBtn = close;
        col.addView(top);

        // Now playing: only on screen while an app plays (or paused it in the last 10 minutes).
        // No name under its card and buttons (Federico): the symbols speak for themselves.
        HorizontalScrollView ms = hscroll();
        mediaRow = row();
        ms.addView(mediaRow);
        mediaRow.addView(buildMediaCard());
        mediaRow.addView(mediaPrev = mediaButton(NowPlaying.Glyph.PREV, false, () -> nowPlaying.previous()));
        mediaRow.addView(mediaPlay = mediaButton(NowPlaying.Glyph.PAUSE, true, () -> nowPlaying.playPause()));
        mediaRow.addView(mediaNext = mediaButton(NowPlaying.Glyph.NEXT, false, () -> nowPlaying.next()));
        mediaRow.addView(mediaButton(NowPlaying.Glyph.CLOSE, false, this::closeMedia));
        mediaSection = section();
        mediaSection.addView(header(R.string.row_now_playing));
        mediaSection.addView(ms);
        mediaSection.setVisibility(View.GONE);
        col.addView(mediaSection, wideRow());

        // Favorites row
        favSection = section();
        favCaption = caption();
        favScroll = hscroll();
        favRow = row();
        favScroll.addView(favRow);
        favSection.addView(header(R.string.row_favs));
        favSection.addView(favScroll);
        favSection.addView(favCaption);
        col.addView(favSection, wideRow());

        // All apps row
        allCaption = caption();
        allScroll = hscroll();
        allRow = row();
        allScroll.addView(allRow);
        allSection = section();
        allSection.addView(header(R.string.row_apps));
        allSection.addView(allScroll);
        allSection.addView(allCaption);
        col.addView(allSection, wideRow());

        // Settings row: projector controls as tiles.
        settingsCaption = caption();
        HorizontalScrollView hs = hscroll();
        LinearLayout r = settingsRow = row();
        hs.addView(r);
        if (projector) {
            r.addView(settingsTile(TileArt.Kind.CALIBRATION, R.string.tile_calibration, this::openProjection));
            r.addView(settingsTile(TileArt.Kind.SETTINGS, R.string.tile_settings, this::openSettingsMenu));
            r.addView(settingsTile(TileArt.Kind.HDMI, R.string.tile_hdmi, () -> openInput(HDMI_SOURCE)));
            if (isInstalled(WIRELESS_CAST)) r.addView(settingsTile(TileArt.Kind.WIRELESS_CAST,
                    R.string.tile_wireless_cast, () -> openComponent(WIRELESS_CAST)));
            if (isInstalled(WIRED_CAST)) r.addView(settingsTile(TileArt.Kind.WIRED_CAST,
                    R.string.tile_wired_cast, () -> openComponent(WIRED_CAST)));
            r.addView(settingsTile(TileArt.Kind.ORIGINAL_HOME, R.string.tile_original_home, this::openStockHome));
        } else {
            r.addView(settingsTile(TileArt.Kind.SETTINGS, R.string.tile_settings,
                    () -> open(new Intent(Settings.ACTION_SETTINGS))));
        }
        bindCaption(favScroll, favCaption);
        bindCaption(allScroll, allCaption);
        bindCaption(hs, settingsCaption);
        settingsSection = section();
        settingsSection.addView(header(R.string.row_settings));
        settingsSection.addView(hs);
        settingsSection.addView(settingsCaption);
        col.addView(settingsSection, wideRow());
        // One place reacts to every focus move: row sizes and all scrolling.
        vscroll.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> onFocusMoved(now));
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
        bg.setColor(0xFF25272A);
        bg.setCornerRadius(dp(12));
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setForeground(focusRing());
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        Bitmap pic = TileArt.settingsBanner(kind, label, w, h);
        iv.setImageBitmap(pic);
        f.addView(iv, new FrameLayout.LayoutParams(w, h));
        f.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(EASE).start();
            if (has) {
                showCaption(settingsCaption, label, v);
            }
        });
        f.setOnClickListener(v -> action.run());
        return f;
    }

    private StateListDrawable focusRing() { return focusRing(12); }

    private StateListDrawable focusRing(int radiusDp) {
        StateListDrawable ring = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0);
        on.setStroke(dp(3), 0xFFFFFFFF);
        on.setCornerRadius(dp(radiusDp));
        ring.addState(new int[]{android.R.attr.state_focused}, on);
        ring.addState(new int[]{}, new ColorDrawable(0));
        return ring;
    }

    /**
     * Shows the focused tile's name directly under that tile (and clears the other rows).
     * The caption is one TextView per row, slid sideways to sit under the focused tile.
     */
    private void showCaption(TextView which, String label, View tile) {
        for (TextView c : new TextView[]{favCaption, allCaption, settingsCaption}) {
            if (c == null) continue;
            if (c == which) {
                c.setText(label);
                c.setTag(tile);
                positionCaption(c);
                c.post(() -> positionCaption(c)); // again after the row finishes laying out
            } else {
                c.setText("");
                c.setTag(null);
            }
        }
    }

    /** Lines the caption's left edge up with its tile, following the row's scroll. */
    private void positionCaption(TextView cap) {
        if (!(cap.getTag() instanceof View)) return;
        View tile = (View) cap.getTag();
        if (!(tile.getParent() instanceof View) || !(tile.getParent().getParent() instanceof HorizontalScrollView)) return;
        HorizontalScrollView hs = (HorizontalScrollView) tile.getParent().getParent();
        // translationX: a favorite mid-move slides into its new place (see stepMove).
        cap.setTranslationX(tile.getLeft() + tile.getTranslationX() - hs.getScrollX());
    }

    private void bindCaption(HorizontalScrollView hs, TextView cap) {
        hs.setOnScrollChangeListener((v, x, y, ox, oy) -> positionCaption(cap));
    }

    // ------------------------------------------------------------------ row focus + motion

    /** Fast start, long soft landing: every slide and zoom in RoldOS uses this curve. */
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final float ROW_ACTIVE = 1.05f, ROW_IDLE = 0.8f;
    private static final int SLIDE_MS = 360;
    /** Breathing room after a row's last tile once it's scrolled all the way. */
    private static final int ROW_END_DP = 48;
    private final Map<View, ObjectAnimator> slides = new HashMap<>();
    private final Map<View, Integer> slideTargets = new HashMap<>();
    private LinearLayout activeSection;

    /**
     * Google TV style: the row you're on grows and the others shrink and dim; the screen
     * glides so that row sits near the top, and the row glides so the focused tile sits at
     * its start. Called for every focus change.
     */
    private void onFocusMoved(View now) {
        if (now == null || menuOpen()) return;
        if (now.getParent() instanceof ViewGroup && visibleRows().contains(now.getParent())) {
            ViewGroup row = (ViewGroup) now.getParent();
            rowMemory.put(row, row.indexOfChild(now));
        }
        LinearLayout sec = null;
        for (View v = now; v != null && v != vscroll; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == mediaSection || v == favSection || v == allSection || v == settingsSection) {
                sec = (LinearLayout) v;
                break;
            }
        }
        LinearLayout firstApps = favSection.getVisibility() == View.VISIBLE ? favSection : allSection;
        boolean mediaShown = mediaSection.getVisibility() == View.VISIBLE;
        boolean onTopBar = sec == null;
        if (onTopBar) sec = mediaShown ? mediaSection : firstApps;
        setActiveSection(sec);

        // Now playing and the first app row share the top of the screen: both stay in view.
        int target = 0;
        View col = vscroll.getChildAt(0);
        if (!onTopBar && sec != firstApps && sec != mediaSection) {
            target = sec.getTop() - dp(30);
        }
        int max = Math.max(0, col.getHeight() - vscroll.getHeight());
        slide(vscroll, "scrollY", Math.max(0, Math.min(target, max)));
        if (!onTopBar) scrollRowTo(now);
    }

    private void setActiveSection(LinearLayout sec) {
        if (sec == activeSection) return;
        activeSection = sec;
        for (LinearLayout s : new LinearLayout[]{mediaSection, favSection, allSection, settingsSection}) {
            if (s == null) continue;
            boolean on = s == sec;
            s.animate().scaleX(on ? ROW_ACTIVE : ROW_IDLE).scaleY(on ? ROW_ACTIVE : ROW_IDLE).setDuration(SLIDE_MS).setInterpolator(EASE).start();
        }
    }

    /** Glides the tile's row so the tile sits at the row's start (until the row runs out). */
    private void scrollRowTo(View tile) {
        if (!(tile.getParent() instanceof View) || !(tile.getParent().getParent() instanceof HorizontalScrollView)) return;
        View row = (View) tile.getParent();
        HorizontalScrollView hs = (HorizontalScrollView) row.getParent();
        if (hs.getWidth() == 0) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) tile.getLayoutParams();
        int target = row.getLeft() + tile.getLeft() - lp.leftMargin;
        // The row box is wider than the screen (see wideRow): clamp to what is actually visible.
        // The row's extra end padding (see row()) exists only so the scroll can get this far.
        int contentEnd = row.getLeft() + row.getWidth() - row.getPaddingRight() + dp(ROW_END_DP);
        int max = Math.max(0, contentEnd - rowVisibleW);
        slide(hs, "scrollX", Math.max(0, Math.min(target, max)));
    }

    /** Eased scroll; a new press re-aims the running slide instead of queueing another. */
    private void slide(View v, String prop, int to) { slide(v, prop, to, SLIDE_MS, 0); }

    private void slide(View v, String prop, int to, int ms, int delay) {
        Integer cur = slideTargets.get(v);
        int now = "scrollX".equals(prop) ? v.getScrollX() : v.getScrollY();
        // isStarted, not isRunning: a glide still waiting out its delay counts too.
        if (cur != null && cur == to && slides.get(v) != null && slides.get(v).isStarted()) return;
        ObjectAnimator old = slides.get(v);
        if (old != null) old.cancel();
        slideTargets.put(v, to);
        if (now == to) return;
        ObjectAnimator a = ObjectAnimator.ofInt(v, prop, now, to);
        a.setDuration(ms);
        a.setStartDelay(delay);
        a.setInterpolator(EASE);
        slides.put(v, a);
        a.start();
    }

    private void stopSlides() {
        for (ObjectAnimator a : slides.values()) a.cancel();
        slides.clear();
        slideTargets.clear();
    }

    /** Row name, Google TV style: large and in the regular weight. */
    private TextView header(int res) {
        TextView t = text(26, 0xFFF4F4F5, false);
        t.setText(res);
        t.setPadding(dp(9), dp(18), 0, dp(2));
        return t;
    }

    private TextView caption() {
        TextView t = text(15, 0xFFFFFFFF, false);
        t.setSingleLine(true);
        t.setMinHeight(dp(20));
        return t;
    }

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        // Rows grow/shrink from their left edge and vertical middle (see setActiveSection).
        s.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { v.setPivotX(0); v.setPivotY((b - t) / 2f); });
        return s;
    }

    /**
     * Each row is laid out wider than the screen. A row draws only inside its own box, and a
     * shrunk (idle) row's box shrinks with it: at 0.8x a screen-wide row ended at 80% of the
     * screen, leaving its right side cut off. Sized at screen / 0.8 it still reaches the edge.
     */
    private LinearLayout.LayoutParams wideRow() {
        return new LinearLayout.LayoutParams(rowBoxW, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private HorizontalScrollView hscroll() {
        HorizontalScrollView h = new HorizontalScrollView(this) {
            @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }
        };
        h.setSmoothScrollingEnabled(false); // our own eased slide does the moving
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        return h;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        // Tiles clip to their own box: lets the GPU skip every tile that's off screen instead of
        // drawing whole rows each frame (measured 2026-11-08: 38 ms median frames before).
        // A focused tile's zoom (1.08x) and the move lift (6 dp) still fit inside the tile margins.
        r.setClipChildren(true);
        r.setClipToPadding(false);
        // A scroll box can't scroll further than (row width - box width), and the box is wider
        // than the screen (see wideRow). Without extra end padding the last tiles sat past the
        // screen's right edge, unreachable: focus went off screen. The padding equals the
        // off-screen part of the box, so the scroll can bring the last tile fully into view.
        r.setPadding(0, 0, dp(ROW_END_DP) + Math.max(0, rowBoxW - rowVisibleW), 0);
        return r;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(40));
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setTypeface(bold ? Fonts.medium(this) : Fonts.regular(this));
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
        // Hold-OK is detected in handleHoldOk(), not by View long-click (see there why).

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
            v.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(EASE).start();
            if (has && movingPkg != null) {
                // Mid-move the row is being reshuffled: keep the move hint under the moving tile.
                if (a.pkg().equals(movingPkg)) showCaption(favCaption, getString(R.string.move_hint), v);
            } else if (has) {
                showCaption(cap, a.label, v);
            }
        });
        f.setOnClickListener(v -> launch(a));
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
                if (!key.equals(iv.getTag())) return;
                iv.setImageBitmap(b);
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
        if (banner == null) banner = downloadedBanner(a.pkg());
        if (banner == null) return TileArt.appBanner(icon, a.label, w, h); // generated per-app design
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        c.drawColor(color);
        banner.setBounds(0, 0, w, h);
        banner.draw(c);
        return b;
    }

    /**
     * Apps that ship no TV banner (e.g. Netflix's phone app): the brand's own official banner,
     * downloaded once into assets/banners/<package>.png (not in git: other brands' art).
     * System apps never get one; they keep RoldOS's own drawn banners.
     */
    private Drawable downloadedBanner(String pkg) {
        try (java.io.InputStream in = getAssets().open("banners/" + pkg + ".png")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeStream(in, null, o);
            return bm == null ? null : new BitmapDrawable(getResources(), bm);
        } catch (java.io.IOException e) {
            return null;
        }
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
        if (open(i)) lastOpenedPkg = a.pkg();
        else Toast.makeText(this, getString(R.string.err_launch, a.label), Toast.LENGTH_SHORT).show();
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

    private boolean isInstalled(ComponentName cn) {
        try {
            return getPackageManager().getActivityInfo(cn, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openComponent(ComponentName cn) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn);
        if (open(i)) lastOpenedPkg = cn.getPackageName();
        else Toast.makeText(this, getString(R.string.err_launch, cn.getPackageName()),
                Toast.LENGTH_SHORT).show();
    }

    /** Visit the projector's original launcher. Home brings the user back to RoldOS. */
    private void openStockHome() {
        HomeKeyService.allowStockHome();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(HomeKeyService.STOCK_PKG, HomeKeyService.STOCK_HOME))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (RuntimeException e) {
            Toast.makeText(this, getString(R.string.err_launch, "LuminaOS"), Toast.LENGTH_SHORT).show();
        }
    }

    /** Settings: always the stock MAIN settings menu, never wherever it was last left. */
    private void openSettingsMenu() {
        HomeKeyService.requestSettingsMenu();
        lumina("OPEN_MAIN_SETTINGS");
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
        if (open(i)) lastOpenedPkg = INPUT_VIEWER_PKG;
        else lumina("OPEN_SOURCE");
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
        showNotice(getString(R.string.closing), 0); // instant feedback
        // Players first: pause + stop them (a playing app isn't "background" and survives the
        // kill otherwise) and hide the Now playing row along with them.
        quietGuard();
        nowPlaying.closeAll();
        AudioManager audioMgr = audio();
        if (!holdingAudioFocus && audioMgr.requestAudioFocus(quietFocus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            holdingAudioFocus = true;
        }
        for (String p : pkgs) {
            try { am.killBackgroundProcesses(p); } catch (RuntimeException ignored) { }
        }
        ui.postDelayed(() -> {
            long freedMb = Math.max(0, availMem(am) - before) / (1024 * 1024);
            showNotice(freedMb > 0 ? getString(R.string.closed_freed, freedMb)
                    : getString(R.string.closed_nothing), 2800);
        }, 1200);
    }

    // ------------------------------------------------------------------ on-screen notice

    private TextView notice;
    private final Runnable hideNotice = () -> notice.setVisibility(View.GONE);

    /**
     * RoldOS's own confirmation pill (bottom center, graphite with an amber check), used
     * instead of Android's small grey toast so actions are clearly acknowledged.
     * hideAfterMs = 0 keeps it up until the next call.
     */
    private void showNotice(String message, long hideAfterMs) {
        SpannableString s = new SpannableString("✓  " + message);
        s.setSpan(new ForegroundColorSpan(0xFFFFB547), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        notice.setText(s);
        ui.removeCallbacks(hideNotice);
        notice.setVisibility(View.VISIBLE); // appears at once: no fade (no transparency)
        if (hideAfterMs > 0) ui.postDelayed(hideNotice, hideAfterMs);
    }

    private TextView buildNotice() {
        TextView t = text(18, 0xFFF4F4F5, true);
        t.setPadding(dp(26), dp(14), dp(28), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF202124);
        g.setStroke(Math.max(1, dp(1)), 0xFF3A3C40);
        g.setCornerRadius(dp(40));
        t.setBackground(g);
        t.setElevation(dp(8));
        t.setVisibility(View.GONE);
        return t;
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
        if (isFav && favs.size() > 1) {
            labels.add(getString(R.string.m_move));
            actions.add(() -> startMove(pkg));
        }
        labels.add(getString(R.string.m_info));
        actions.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        labels.add(getString(R.string.m_uninstall));
        actions.add(() -> open(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))));

        // Drawn inside our own window on purpose. With a separate dialog window, this projector
        // (Android 11 + our key-filtering service) sometimes never handed key focus back to the
        // home screen after the dialog closed: every key was then dropped ("no window focus"),
        // so Move did nothing and the screen looked frozen. (Found in logcat, 2026-11-09.)
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(10));
        card.setMinimumWidth(dp(380));
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0xFF1E1F22);
        cg.setStroke(Math.max(1, dp(1)), 0xFF3A3C40);
        cg.setCornerRadius(dp(20));
        card.setBackground(cg);
        card.setElevation(dp(12));

        TextView title = text(21, 0xFFF4F4F5, true);
        title.setText(a.label);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.addView(title);

        View first = null;
        for (int i = 0; i < labels.size(); i++) {
            final Runnable action = actions.get(i);
            TextView item = text(19, 0, false);
            item.setText(labels.get(i));
            item.setPadding(dp(18), dp(14), dp(18), dp(14));
            item.setTextColor(new ColorStateList(
                    new int[][] { { android.R.attr.state_focused }, {} },
                    new int[] { 0xFF141516, 0xFFE4E5E7 }));
            GradientDrawable on = new GradientDrawable();
            on.setColor(0xFFFFB547);
            on.setCornerRadius(dp(12));
            StateListDrawable sel = new StateListDrawable();
            sel.addState(new int[] { android.R.attr.state_focused }, on);
            sel.addState(new int[0], new ColorDrawable(0));
            item.setBackground(sel);
            item.setFocusable(true);
            item.setClickable(true);
            item.setOnClickListener(v -> { closeMenu(); action.run(); });
            card.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (first == null) first = item;
        }

        menuReturn = getCurrentFocus();
        menuLayer.removeAllViews();
        menuLayer.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        homeView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menuLayer.setVisibility(View.VISIBLE);
        if (first != null) first.requestFocus();
    }

    private ViewGroup homeView;
    private FrameLayout menuLayer;
    /** Tile that had focus when the menu opened; it gets focus back when the menu closes. */
    private View menuReturn;

    private boolean menuOpen() { return menuLayer != null && menuLayer.getVisibility() == View.VISIBLE; }

    private void closeMenu() {
        // Give focus back to the home first, then hide the menu, so focus never lands nowhere.
        homeView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (menuReturn != null && menuReturn.isAttachedToWindow()) menuReturn.requestFocus();
        else focusFirst();
        menuReturn = null;
        menuLayer.setVisibility(View.GONE);
        menuLayer.removeAllViews();
    }

    // ------------------------------------------------------------------ hold OK -> app menu

    /**
     * Android's own View long-click proved unreliable here: once our accessibility service
     * filters the remote's keys, OK reaches us re-injected and the long-press can fail to fire.
     * So we time the hold ourselves: menu after ~0.5 s, or as soon as the remote reports the
     * key as held (repeat / long-press flag). The release after a menu never launches the app.
     */
    private boolean holdFired;
    private final Runnable holdTimeout = () -> {
        View v = getCurrentFocus();
        if (v != null && v.getTag() instanceof App && this.movingPkg == null) {
            holdFired = true;
            v.setPressed(false);
            showMenu((App) v.getTag());
        }
    };

    private static boolean isSelectKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    private boolean handleHoldOk(KeyEvent e) {
        if (!isSelectKey(e.getKeyCode())) return false;
        View v = getCurrentFocus();
        boolean onApp = v != null && v.getTag() instanceof App;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                holdFired = false;
                ui.removeCallbacks(holdTimeout);
                if (onApp) ui.postDelayed(holdTimeout, ViewConfiguration.getLongPressTimeout());
                return false; // let the tile see the press (normal click on release)
            }
            if (!holdFired && onApp && (e.isLongPress() || e.getRepeatCount() > 0)) {
                ui.removeCallbacks(holdTimeout);
                holdTimeout.run();
            }
            return holdFired; // swallow the repeats once the menu is up
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            ui.removeCallbacks(holdTimeout);
            if (holdFired) {
                holdFired = false;
                if (v != null) v.setPressed(false);
                return true; // the release after a hold must not open the app
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ move mode (like Google TV)

    /** Package of the favorite being moved, or null when not in move mode. */
    private String movingPkg;
    /** Key whose UP we must swallow because we handled its DOWN in move mode. */
    private int swallowUpKey = -1;

    /** Lifts the favorite and lets Left/Right slide it along the row; OK or Back drops it. */
    private void startMove(String pkg) {
        View tile = findTile(favRow, pkg);
        if (tile == null) return;
        movingPkg = pkg;
        tile.requestFocus();
        tile.setForeground(moveRing());
        tile.animate().translationY(-dp(6)).setDuration(120).start();
        showCaption(favCaption, getString(R.string.move_hint), tile);
    }

    /** Handles keys while moving. Returns true when the key was used. */
    private boolean handleMoveKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (code == swallowUpKey) { swallowUpKey = -1; return true; }
            return movingPkg != null;
        }
        if (movingPkg == null) return false;
        swallowUpKey = code;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: stepMove(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: stepMove(1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                endMove(); return true;
            default:
                return true; // Up/Down and everything else do nothing while moving
        }
    }

    private void stepMove(int dir) {
        // Work on the row as shown (favs may still list an app that was uninstalled), and move
        // the *neighbor* across instead of pulling out the focused tile, so focus never jumps.
        View tile = findTile(favRow, movingPkg);
        if (tile == null) return;
        int from = favRow.indexOfChild(tile), to = from + dir;
        if (to < 0 || to >= favRow.getChildCount()) return;
        View other = favRow.getChildAt(to);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) tile.getLayoutParams();
        float slot = tile.getWidth() + lp.leftMargin + lp.rightMargin;
        favRow.removeView(other);
        favRow.addView(other, from);
        // Swapping places is instant in the layout; offset both tiles back to where they were
        // drawn and glide them into their new spots (from wherever a previous glide left them).
        glideHome(tile, tile.getTranslationX() - dir * slot);
        glideHome(other, other.getTranslationX() + dir * slot);
        favs.clear();
        for (int i = 0; i < favRow.getChildCount(); i++) favs.add(((App) favRow.getChildAt(i).getTag()).pkg());
        favRow.post(() -> { scrollRowTo(tile); positionCaption(favCaption); });
    }

    private static final int SWAP_MS = 240;
    private final Map<View, ObjectAnimator> glides = new HashMap<>();

    /** Puts the tile at offset `from` and slides it to its laid-out place; the caption follows. */
    private void glideHome(View v, float from) {
        ObjectAnimator old = glides.remove(v);
        if (old != null) old.cancel();
        v.setTranslationX(from);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, from, 0f);
        a.setDuration(SWAP_MS);
        a.setInterpolator(EASE);
        a.addUpdateListener(an -> positionCaption(favCaption));
        glides.put(v, a);
        a.start();
    }

    private void endMove() {
        View tile = findTile(favRow, movingPkg);
        movingPkg = null;
        saveFavs();
        if (tile != null) {
            tile.setForeground(focusRing());
            tile.animate().translationY(0).setDuration(120).start();
            showCaption(favCaption, ((App) tile.getTag()).label, tile);
        }
    }

    /** Thick dashed amber outline: "this tile is being moved". */
    private GradientDrawable moveRing() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0);
        g.setStroke(dp(4), 0xFFFFC94D, dp(10), dp(6));
        g.setCornerRadius(dp(12));
        return g;
    }

    // ------------------------------------------------------------------ now playing

    /** Height of the Now playing card and its buttons. */
    private static final int MEDIA_H = 72;
    private NowPlaying nowPlaying;
    private NowPlaying.Info mediaInfo;
    private LinearLayout mediaSection, mediaRow;
    private TextView mediaTitle, mediaSub;
    private ImageView mediaArt;
    private View mediaCard, mediaPrev, mediaPlay, mediaNext;
    private NowPlaying.Glyph playGlyph;

    /** Cover art, title and "artist · app". OK opens the app on its player. */
    private View buildMediaCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(400), dp(MEDIA_H));
        lp.setMargins(dp(9), dp(12), dp(9), dp(12));
        card.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF25272A);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setForeground(focusRing());
        card.setFocusable(true);
        card.setClickable(true);
        mediaArt = new ImageView(this);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(mediaArt, new LinearLayout.LayoutParams(dp(MEDIA_H), dp(MEDIA_H)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(16), 0, dp(16), 0);
        mediaTitle = text(17, 0xFFF4F4F5, true);
        mediaSub = text(14, 0xFF8F949B, false);
        for (TextView t : new TextView[]{mediaTitle, mediaSub}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
        }
        words.addView(mediaTitle);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        words.addView(mediaSub, subLp);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.04f : 1f; // wide card: a smaller zoom than the tiles
            v.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(EASE).start();
            if (has) showCaption(null, null, null); // no name here: just clear the other rows'
        });
        card.setOnClickListener(v -> {
            quietGuard();
            String pkg = nowPlaying.openPlayer();
            if (pkg != null) lastOpenedPkg = pkg;
            else nowPlaying.playPause(); // nothing to open (unknown player): play/pause instead
        });
        mediaCard = card;
        return card;
    }

    /** A round control button; play/pause is amber (the accent), the others graphite. */
    private View mediaButton(int glyph, boolean accent, Runnable action) {
        FrameLayout b = new FrameLayout(this);
        int size = dp(MEDIA_H);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(dp(9), dp(12), dp(9), dp(12));
        b.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent ? 0xFFFFB547 : 0xFF25272A);
        bg.setCornerRadius(size / 2f);
        b.setBackground(bg);
        b.setForeground(focusRing(MEDIA_H / 2));
        b.setFocusable(true);
        b.setClickable(true);
        NowPlaying.Glyph g = new NowPlaying.Glyph(glyph, accent ? 0xFF141516 : 0xFFFFFFFF);
        if (accent) playGlyph = g;
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(g);
        int pad = size * 3 / 10;
        b.addView(iv, new FrameLayout.LayoutParams(size - 2 * pad, size - 2 * pad, Gravity.CENTER));
        b.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(EASE).start();
            if (has) showCaption(null, null, null);
        });
        b.setOnClickListener(v -> { quietGuard(); action.run(); });
        return b;
    }

    /** NowPlaying reports a change: fill the row, show it, or hide it (null). */
    private void onNowPlaying(NowPlaying.Info info) {
        mediaInfo = info;
        boolean shown = mediaSection.getVisibility() == View.VISIBLE;
        if (info == null) {
            if (!shown) return;
            boolean hadFocus = mediaRow.hasFocus();
            if (hadFocus) focusFirst(); // move off the row before it disappears
            rowMemory.remove(mediaRow);
            // The row goes at once (no fade: no transparency) and the rows below glide up.
            glideRows(() -> {
                mediaSection.setVisibility(View.GONE);
                if (activeSection == mediaSection) activeSection = null;
            });
            refreshScroll();
            return;
        }
        mediaTitle.setText(info.title);
        mediaSub.setText(info.subtitle);
        mediaArt.setImageBitmap(info.art);
        playGlyph.setKind(info.playing ? NowPlaying.Glyph.PAUSE : NowPlaying.Glyph.PLAY);
        boolean prevHad = mediaPrev.hasFocus(), nextHad = mediaNext.hasFocus();
        if ((prevHad && !info.canPrev) || (nextHad && !info.canNext)) mediaPlay.requestFocus();
        mediaPrev.setVisibility(info.canPrev ? View.VISIBLE : View.GONE);
        mediaNext.setVisibility(info.canNext ? View.VISIBLE : View.GONE);
        if (!shown) {
            boolean active = activeSection == mediaSection;
            mediaSection.setScaleX(active ? ROW_ACTIVE : ROW_IDLE);
            mediaSection.setScaleY(active ? ROW_ACTIVE : ROW_IDLE);
            glideRows(() -> mediaSection.setVisibility(View.VISIBLE)); // the rows below glide down
            refreshScroll();
        }
    }

    private final Map<View, ObjectAnimator> rowGlides = new HashMap<>();

    /**
     * Runs a change that makes the rows below Now playing move (the row appears or goes),
     * then lets those rows glide from where they were to their new place instead of jumping.
     */
    private void glideRows(Runnable change) {
        final LinearLayout[] below = {favSection, allSection, settingsSection};
        final float[] before = new float[below.length];
        for (int i = 0; i < below.length; i++) before[i] = below[i].getTop() + below[i].getTranslationY();
        change.run();
        final View col = vscroll.getChildAt(0);
        col.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                col.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < below.length; i++) {
                    View s = below[i];
                    ObjectAnimator old = rowGlides.remove(s);
                    if (old != null) old.cancel();
                    float from = before[i] - s.getTop();
                    if (s.getVisibility() != View.VISIBLE || Math.abs(from) < 1f) { s.setTranslationY(0f); continue; }
                    ObjectAnimator a = ObjectAnimator.ofFloat(s, View.TRANSLATION_Y, from, 0f);
                    a.setDuration(SLIDE_MS);
                    a.setInterpolator(EASE);
                    rowGlides.put(s, a);
                    a.start();
                }
                return true;
            }
        });
    }

    /** X: the player stops for good (pause + stop + we take the sound focus) and its app ends. */
    private void closeMedia() {
        nowPlaying.close();
        AudioManager am = audio();
        if (!holdingAudioFocus && am.requestAudioFocus(quietFocus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            holdingAudioFocus = true; // given back when the user leaves the home screen
        }
    }

    /** The rows above the focus changed height: re-aim the screen once the new layout is in. */
    private void refreshScroll() {
        vscroll.post(() -> {
            View f = getCurrentFocus();
            if (f != null) onFocusMoved(f);
        });
    }

    /** The user is driving playback from the home: the audio guard must not undo it. */
    private void quietGuard() {
        guardPkg = null;
        ui.removeCallbacks(audioGuard);
        if (holdingAudioFocus) {
            holdingAudioFocus = false;
            audio().abandonAudioFocus(quietFocus);
        }
    }

    // ------------------------------------------------------------------ quiet home (audio guard)

    /**
     * Bug (Federico): press Home while an app is still loading, and a moment later that app
     * starts playing behind the home screen. Apps like TizenTube play through a foreground
     * service, so they keep going when sent to the back.
     *
     * Guard: for a minute after returning from an app RoldOS opened, watch for sound that
     * *starts* while the home screen is showing. Music that was already playing when you came
     * back (e.g. Spotify) is left alone; only if it goes quiet for a few seconds and then starts
     * again does that count as a new start. On a start: send Pause, take the audio focus
     * (well-behaved players pause when they lose it) and, if it still plays, end that app.
     */
    private static final long GUARD_MS = 60_000, GUARD_POLL_MS = 500, QUIET_TO_ARM_MS = 3_000;
    /** Package of the last app RoldOS opened (handed to the guard on the next return home). */
    private String lastOpenedPkg;
    private String guardPkg;
    private long guardUntil, silentSince;
    private boolean guardArmed, holdingAudioFocus;
    private final AudioManager.OnAudioFocusChangeListener quietFocus = change -> { };

    private AudioManager audio() { return (AudioManager) getSystemService(AUDIO_SERVICE); }

    private void armAudioGuard() {
        ui.removeCallbacks(audioGuard);
        guardPkg = lastOpenedPkg;
        lastOpenedPkg = null;
        if (guardPkg == null || NEVER_CLOSE.contains(guardPkg)) { guardPkg = null; return; }
        long now = SystemClock.uptimeMillis();
        guardUntil = now + GUARD_MS;
        guardArmed = !audio().isMusicActive(); // already playing when we came back: leave it be
        silentSince = guardArmed ? now : 0;
        ui.postDelayed(audioGuard, GUARD_POLL_MS);
    }

    private final Runnable audioGuard = new Runnable() {
        @Override public void run() {
            if (!resumed || guardPkg == null) return;
            long now = SystemClock.uptimeMillis();
            if (now > guardUntil) { guardPkg = null; return; }
            if (!audio().isMusicActive()) {
                if (silentSince == 0) silentSince = now;
                if (now - silentSince >= QUIET_TO_ARM_MS) guardArmed = true;
            } else {
                silentSince = 0;
                if (guardArmed) {
                    silence(guardPkg);
                    guardPkg = null;
                    return;
                }
            }
            ui.postDelayed(this, GUARD_POLL_MS);
        }
    };

    private void silence(final String pkg) {
        AudioManager am = audio();
        long t = SystemClock.uptimeMillis();
        try {
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        } catch (RuntimeException ignored) { }
        if (!holdingAudioFocus && am.requestAudioFocus(quietFocus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            holdingAudioFocus = true; // given back when the user leaves the home screen
        }
        ui.postDelayed(() -> {
            if (!resumed || !audio().isMusicActive()) return;
            try {
                ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
            } catch (RuntimeException ignored) { }
        }, 1500);
    }

    // ------------------------------------------------------------------ Wi-Fi status + recovery

    private TextView wifiText;
    private final WifiGlyph wifiGlyph = new WifiGlyph();
    private final Runnable wifiUpdate = this::updateWifi;
    private final ConnectivityManager.NetworkCallback netWatch = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { postWifiUpdate(); }
        @Override public void onLost(Network n) { postWifiUpdate(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { postWifiUpdate(); }
    };

    private void postWifiUpdate() {
        ui.removeCallbacks(wifiUpdate);
        ui.post(wifiUpdate);
    }

    /**
     * Shows the Wi-Fi network's name and signal. Android hides the name unless the app holds
     * the location permission; build.sh grants it. Without it the bar just says "Wi-Fi".
     */
    private void updateWifi() {
        if (wifiText == null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = null;
        try {
            Network n = cm.getActiveNetwork();
            if (n != null) caps = cm.getNetworkCapabilities(n);
        } catch (RuntimeException ignored) { }
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            String name = "Wi-Fi";
            int level = 3;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                String ssid = wi == null ? null : wi.getSSID();
                if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) {
                    if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    name = ssid;
                }
                if (wi != null) {
                    float frac = android.os.Build.VERSION.SDK_INT >= 30
                            ? wm.calculateSignalLevel(wi.getRssi()) / (float) Math.max(1, wm.getMaxSignalLevel())
                            : WifiManager.calculateSignalLevel(wi.getRssi(), 5) / 4f;
                    level = Math.max(1, Math.round(frac * 3));
                }
            } catch (RuntimeException ignored) { }
            showWifi(name, level, true);
        } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            showWifi("Ethernet", 3, true);
        } else {
            showWifi(getString(R.string.no_wifi), 0, false);
        }
    }

    private void showWifi(String name, int level, boolean online) {
        wifiText.setText(name);
        wifiText.setTextColor(online ? 0xFFB8BCC2 : 0xFFFFB547);
        wifiGlyph.set(level, online);
        ui.removeCallbacks(wifiKick);
        if (!online && resumed) ui.postDelayed(wifiKick, 3_000);
    }

    /**
     * While the home screen shows and there's no connection, ask for a Wi-Fi scan every 25 s:
     * a scan makes Android look for the saved network and reconnect. Without it, after Wireless
     * Cast (which takes the Wi-Fi off the home network while it's open) the projector once
     * took 3 minutes to come back on its own.
     */
    private final Runnable wifiKick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm.isWifiEnabled()) wm.startScan();
            } catch (RuntimeException ignored) { }
            ui.postDelayed(this, 25_000);
        }
    };

    /** The Wi-Fi fan: a dot and three arcs; arcs light up with the signal, all dim when offline. */
    private static final class WifiGlyph extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private int level;
        private boolean online;

        void set(int level, boolean online) {
            this.level = level;
            this.online = online;
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float cx = b.exactCenterX(), cy = b.top + s * 0.86f;
            final int on = 0xFFFFFFFF, off = 0x4DFFFFFF;
            p.setStyle(Paint.Style.FILL);
            p.setColor(online ? on : off);
            c.drawCircle(cx, cy, s * 0.075f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.1f);
            p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 1; i <= 3; i++) {
                float r = s * (0.04f + 0.2f * i);
                oval.set(cx - r, cy - r, cx + r, cy + r);
                p.setColor(online && i <= level ? on : off);
                c.drawArc(oval, -135, 90, false, p);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private int dp(float v) { return Math.round(v * density); }
}
