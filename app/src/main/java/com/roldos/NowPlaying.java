package com.roldos;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches what other apps are playing (Spotify, YouTube, ...) and reports the one worth showing
 * on the home screen, with its controls. Runs only while the home screen is showing.
 *
 * Reading other apps' media sessions needs RoldOS to be an approved notification listener
 * (see MediaAccess). Without that approval it falls back to "something is playing" (Android
 * tells any app whether music is active) and the remote's media keys, which reach whichever
 * app last played.
 */
final class NowPlaying {

    /** Snapshot of what to show. Null means: nothing playing, hide the row. */
    static final class Info {
        final String pkg, title, subtitle, appLabel;
        final Bitmap art;
        final boolean playing, canPrev, canNext;
        Info(String pkg, String title, String subtitle, String appLabel, Bitmap art,
             boolean playing, boolean canPrev, boolean canNext) {
            this.pkg = pkg; this.title = title; this.subtitle = subtitle; this.appLabel = appLabel;
            this.art = art; this.playing = playing; this.canPrev = canPrev; this.canNext = canNext;
        }
    }

    interface Listener { void onNowPlaying(Info info); }

    /** A paused player stays on screen this long after it paused, so you can resume it. */
    private static final long PAUSED_KEEP_MS = 10 * 60_000;
    private static final long FALLBACK_POLL_MS = 1_500;
    /** Art source marker for fallback mode (the note glyph). */
    private static final Object NOTE_ART = new Object();

    private final Context ctx;
    private final Handler ui;
    private final Listener out;
    private final ComponentName access;
    private final int artPx;
    private final List<Watched> watched = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
    /**
     * Apps closed with X or Close all apps (and when, uptime): hidden until they play again on
     * their own. Saved, so a restart of RoldOS (an update, low memory) doesn't bring a closed
     * player's paused session back (Federico saw Spotify reappear right after a reinstall).
     */
    private final Map<String, Long> dismissed = new HashMap<>();
    private MediaSessionManager msm;
    private boolean running, fallback;
    private Watched current;
    /** Last art drawn, and what it was drawn from (so it isn't redrawn on every state change). */
    private Object artSource;
    private Bitmap artBmp;
    /** Fallback mode: when the user last paused from the widget (it stays a while after). */
    private long fallbackPausedAt;

    NowPlaying(Context ctx, Handler ui, int artPx, Listener out) {
        this.ctx = ctx;
        this.ui = ui;
        this.artPx = artPx;
        this.out = out;
        this.access = new ComponentName(ctx, MediaAccess.class);
        long bootNow = SystemClock.elapsedRealtime();
        for (String e : prefs().getStringSet(PREF_DISMISSED, java.util.Collections.<String>emptySet())) {
            int at = e.lastIndexOf('@');
            if (at <= 0) continue;
            long t;
            try { t = Long.parseLong(e.substring(at + 1)); } catch (NumberFormatException x) { t = 0; }
            dismissed.put(e.substring(0, at), t > bootNow ? 0L : t); // later than now = before a reboot
        }
    }

    private static final String PREF_DISMISSED = "np_dismissed";

    private android.content.SharedPreferences prefs() {
        return ctx.getSharedPreferences("roldos", Context.MODE_PRIVATE);
    }

    private java.util.Set<String> saved() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (Map.Entry<String, Long> e : dismissed.entrySet()) s.add(e.getKey() + "@" + e.getValue());
        return s;
    }

    private void saveDismissed() {
        prefs().edit().putStringSet(PREF_DISMISSED, saved()).apply();
    }

    /** One media session we follow, with its latest metadata and state (kept from callbacks:
     *  asking the session again would be a round trip to the other app every time). */
    private final class Watched extends MediaController.Callback {
        final MediaController c;
        MediaMetadata meta;
        PlaybackState state;

        Watched(MediaController c) {
            this.c = c;
            try { meta = c.getMetadata(); state = c.getPlaybackState(); } catch (RuntimeException ignored) { }
            c.registerCallback(this, ui);
        }
        @Override public void onPlaybackStateChanged(PlaybackState s) { state = s; post(); }
        @Override public void onMetadataChanged(MediaMetadata m) { meta = m; post(); }
        @Override public void onSessionDestroyed() { state = null; post(); }
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::follow;

    private final Runnable evaluate = this::evaluate;

    private void post() {
        ui.removeCallbacks(evaluate);
        ui.post(evaluate);
    }

    // ------------------------------------------------------------------ start / stop

    void start() {
        if (running) return;
        running = true;
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(sessionsChanged, access, ui);
            fallback = false;
            follow(msm.getActiveSessions(access));
        } catch (RuntimeException notApproved) { // SecurityException: listener not approved
            fallback = true;
            ui.post(fallbackPoll);
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        if (!fallback && msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessionsChanged); } catch (RuntimeException ignored) { }
        }
        unfollowAll();
        ui.removeCallbacks(evaluate);
        ui.removeCallbacks(fallbackPoll);
    }

    private void follow(List<MediaController> list) {
        unfollowAll();
        if (list != null) {
            for (MediaController c : list) {
                if (ctx.getPackageName().equals(c.getPackageName())) continue;
                try { watched.add(new Watched(c)); } catch (RuntimeException ignored) { }
            }
        }
        evaluate();
    }

    private void unfollowAll() {
        for (Watched w : watched) {
            try { w.c.unregisterCallback(w); } catch (RuntimeException ignored) { }
        }
        watched.clear();
        current = null;
    }

    // ------------------------------------------------------------------ what to show

    private static boolean isPlaying(PlaybackState s) {
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                || st == PlaybackState.STATE_FAST_FORWARDING || st == PlaybackState.STATE_REWINDING
                || st == PlaybackState.STATE_SKIPPING_TO_NEXT || st == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || st == PlaybackState.STATE_CONNECTING;
    }

    /** Milliseconds a paused session still has on screen (0 = not paused, or paused too long ago). */
    private static long pausedLeft(PlaybackState s) {
        if (s == null || s.getState() != PlaybackState.STATE_PAUSED) return 0;
        long at = s.getLastPositionUpdateTime();
        if (at <= 0) return 0;
        long left = PAUSED_KEEP_MS - (SystemClock.elapsedRealtime() - at);
        return Math.max(0, left);
    }

    private void evaluate() {
        ui.removeCallbacks(evaluate);
        if (!running) return;
        if (fallback) { out.onNowPlaying(fallbackInfo()); return; }
        // A playing session wins; else the most recent one paused within the last 10 minutes.
        Watched pick = null;
        long keep = 0;
        for (Watched w : watched) {
            Long gone = dismissed.get(w.c.getPackageName());
            if (gone != null) {
                // Back when it plays again, or when it was used in the player itself since the X
                // (its position moved later than the X's own pause/stop, which land within 3 s).
                long touched = w.state == null ? 0 : w.state.getLastPositionUpdateTime();
                boolean usedSince = touched > gone + 3_000;
                boolean playsAgain = isPlaying(w.state) && SystemClock.elapsedRealtime() - gone >= 3_000;
                if (!usedSince && !playsAgain) continue;
                dismissed.remove(w.c.getPackageName());
                saveDismissed();
            }
            if (isPlaying(w.state)) { pick = w; keep = 0; break; }
            long left = pausedLeft(w.state);
            if (pick == null && left > 0) { pick = w; keep = left; }
        }
        current = pick;
        if (keep > 0) ui.postDelayed(evaluate, keep + 200); // drop it when its time runs out
        out.onNowPlaying(pick == null ? null : info(pick));
    }

    private Info info(Watched w) {
        String pkg = w.c.getPackageName();
        String app = label(pkg);
        String title = null, sub = null;
        Bitmap art = null;
        if (w.meta != null) {
            title = first(w.meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            sub = first(w.meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
        }
        if (TextUtils.isEmpty(title)) { title = app; sub = null; }
        String subtitle = TextUtils.isEmpty(sub) ? app : sub + "  ·  " + app;
        long actions = w.state == null ? 0 : w.state.getActions();
        return new Info(pkg, title, subtitle, app, art(w, pkg), isPlaying(w.state),
                (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0,
                (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
    }

    private Info fallbackInfo() {
        boolean playing = audio().isMusicActive();
        if (!playing && SystemClock.uptimeMillis() - fallbackPausedAt > PAUSED_KEEP_MS) return null;
        String t = ctx.getString(R.string.np_unknown);
        if (artSource != NOTE_ART) { artSource = NOTE_ART; artBmp = drawArt(null, null); }
        return new Info(null, t, ctx.getString(R.string.np_unknown_sub), null, artBmp, playing, true, true);
    }

    private final Runnable fallbackPoll = new Runnable() {
        @Override public void run() {
            if (!running || !fallback) return;
            evaluate();
            ui.postDelayed(this, FALLBACK_POLL_MS);
        }
    };

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString().trim();
        }
        return null;
    }

    private String label(String pkg) {
        String l = labels.get(pkg);
        if (l == null) {
            PackageManager pm = ctx.getPackageManager();
            try { l = String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm)).trim(); }
            catch (PackageManager.NameNotFoundException e) { l = pkg; }
            labels.put(pkg, l);
        }
        return l;
    }

    /** Cover art (or the app's icon) as a small square; redrawn only when the source changes. */
    private Bitmap art(Watched w, String pkg) {
        Bitmap cover = null;
        if (w.meta != null) {
            cover = w.meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (cover == null) cover = w.meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (cover == null) cover = w.meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        }
        Object source = cover != null ? cover : pkg;
        if (source == artSource && artBmp != null) return artBmp;
        Drawable icon = null;
        if (cover == null) {
            try { icon = ctx.getPackageManager().getApplicationIcon(pkg); }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        artSource = source;
        artBmp = drawArt(cover, icon);
        return artBmp;
    }

    /** Center-cropped cover, or the icon on graphite, or a note glyph when there's neither. */
    private Bitmap drawArt(Bitmap cover, Drawable icon) {
        Bitmap b = Bitmap.createBitmap(artPx, artPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (cover != null && !cover.isRecycled()) {
            int w = cover.getWidth(), h = cover.getHeight(), s = Math.min(w, h);
            Rect src = new Rect((w - s) / 2, (h - s) / 2, (w - s) / 2 + s, (h - s) / 2 + s);
            c.drawBitmap(cover, src, new Rect(0, 0, artPx, artPx), new Paint(Paint.FILTER_BITMAP_FLAG));
            return b;
        }
        c.drawColor(0xFF2E3033);
        int pad = artPx / 5;
        Drawable d = icon != null ? icon : new Glyph(Glyph.NOTE, 0xFFFFB547);
        d.setBounds(pad, pad, artPx - pad, artPx - pad);
        d.draw(c);
        return b;
    }

    // ------------------------------------------------------------------ controls

    private AudioManager audio() { return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE); }

    void playPause() {
        if (current != null) {
            MediaController.TransportControls t = current.c.getTransportControls();
            if (isPlaying(current.state)) t.pause(); else t.play();
            return;
        }
        if (fallback) {
            if (audio().isMusicActive()) fallbackPausedAt = SystemClock.uptimeMillis();
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            ui.postDelayed(evaluate, 700); // the player takes a moment to go quiet / start
        }
    }

    /**
     * X button: silences the player, hides the row and ends the app. Some players (Spotify on
     * TV) ignore a bare "stop", so: pause first, then stop. MainActivity also takes the audio
     * focus, which makes well-behaved players pause for good. Returns the app's package.
     */
    String close() {
        if (current == null) {
            if (fallback) {
                fallbackPausedAt = 0;
                mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
                mediaKey(KeyEvent.KEYCODE_MEDIA_STOP);
                ui.postDelayed(evaluate, 700);
            }
            return null;
        }
        final String pkg = current.c.getPackageName();
        MediaController.TransportControls t = current.c.getTransportControls();
        try { t.pause(); } catch (RuntimeException ignored) { }
        try { t.stop(); } catch (RuntimeException ignored) { }
        dismissed.put(pkg, SystemClock.elapsedRealtime());
        saveDismissed();
        evaluate();
        ui.postDelayed(() -> {
            try {
                ((android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
            } catch (RuntimeException ignored) { }
        }, 1200);
        return pkg;
    }

    /**
     * "Close all apps": silences every player we follow (they're being ended anyway) so the
     * Now playing row goes away with them instead of lingering on a paused session.
     */
    void closeAll() {
        long now = SystemClock.elapsedRealtime();
        for (Watched w : watched) {
            MediaController.TransportControls t = w.c.getTransportControls();
            try { t.pause(); } catch (RuntimeException ignored) { }
            try { t.stop(); } catch (RuntimeException ignored) { }
            final String pkg = w.c.getPackageName();
            dismissed.put(pkg, now);
            saveDismissed();
            ui.postDelayed(() -> { // again once it has gone quiet and dropped its foreground service
                try {
                    ((android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
                } catch (RuntimeException ignored) { }
            }, 1200);
        }
        if (fallback) {
            fallbackPausedAt = 0;
            mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
            mediaKey(KeyEvent.KEYCODE_MEDIA_STOP);
            ui.postDelayed(evaluate, 700);
        }
        evaluate();
    }

    void next() {
        if (current != null) current.c.getTransportControls().skipToNext();
        else if (fallback) mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    void previous() {
        if (current != null) current.c.getTransportControls().skipToPrevious();
        else if (fallback) mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    /** Opens the playing app on its player screen. Returns its package, or null if it couldn't. */
    String openPlayer() {
        if (current == null) return null;
        String pkg = current.c.getPackageName();
        PendingIntent pi = current.c.getSessionActivity();
        if (pi != null) {
            try { pi.send(); return pkg; } catch (PendingIntent.CanceledException ignored) { }
        }
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return null;
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return pkg;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void mediaKey(int code) {
        long t = SystemClock.uptimeMillis();
        try {
            audio().dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            audio().dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ button glyphs

    /** Media control symbols, drawn (no image files): previous, play, pause, next, note. */
    static final class Glyph extends Drawable {
        static final int PREV = 0, PLAY = 1, PAUSE = 2, NEXT = 3, NOTE = 4, CLOSE = 5;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private int kind;

        Glyph(int kind, int color) {
            this.kind = kind;
            p.setColor(color);
        }

        void setKind(int k) {
            if (k == kind) return;
            kind = k;
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.exactCenterX() - s / 2, y = b.exactCenterY() - s / 2;
            p.setStyle(Paint.Style.FILL);
            path.reset();
            switch (kind) {
                case CLOSE:
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(s * 0.12f);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(x + s * 0.25f, y + s * 0.25f, x + s * 0.75f, y + s * 0.75f, p);
                    c.drawLine(x + s * 0.75f, y + s * 0.25f, x + s * 0.25f, y + s * 0.75f, p);
                    break;
                case PLAY:
                    path.moveTo(x + s * 0.28f, y + s * 0.18f);
                    path.lineTo(x + s * 0.84f, y + s * 0.5f);
                    path.lineTo(x + s * 0.28f, y + s * 0.82f);
                    path.close();
                    c.drawPath(path, p);
                    break;
                case PAUSE:
                    r.set(x + s * 0.24f, y + s * 0.2f, x + s * 0.42f, y + s * 0.8f);
                    c.drawRoundRect(r, s * 0.04f, s * 0.04f, p);
                    r.offset(s * 0.34f, 0);
                    c.drawRoundRect(r, s * 0.04f, s * 0.04f, p);
                    break;
                case NEXT: case PREV: {
                    boolean next = kind == NEXT;
                    float dir = next ? 1 : -1, cx = x + s / 2;
                    path.moveTo(cx - dir * s * 0.3f, y + s * 0.2f);
                    path.lineTo(cx + dir * s * 0.18f, y + s * 0.5f);
                    path.lineTo(cx - dir * s * 0.3f, y + s * 0.8f);
                    path.close();
                    c.drawPath(path, p);
                    float bx = cx + dir * s * 0.18f;
                    r.set(Math.min(bx, bx + dir * s * 0.12f), y + s * 0.2f,
                            Math.max(bx, bx + dir * s * 0.12f), y + s * 0.8f);
                    c.drawRoundRect(r, s * 0.03f, s * 0.03f, p);
                    break;
                }
                default: { // NOTE: a beamed pair of eighth notes
                    float stroke = s * 0.09f;
                    c.drawCircle(x + s * 0.3f, y + s * 0.74f, s * 0.13f, p);
                    c.drawCircle(x + s * 0.74f, y + s * 0.64f, s * 0.13f, p);
                    r.set(x + s * 0.43f - stroke, y + s * 0.2f, x + s * 0.43f, y + s * 0.74f);
                    c.drawRect(r, p);
                    r.set(x + s * 0.87f - stroke, y + s * 0.12f, x + s * 0.87f, y + s * 0.64f);
                    c.drawRect(r, p);
                    path.moveTo(x + s * 0.43f - stroke, y + s * 0.2f);
                    path.lineTo(x + s * 0.87f, y + s * 0.1f);
                    path.lineTo(x + s * 0.87f, y + s * 0.24f);
                    path.lineTo(x + s * 0.43f - stroke, y + s * 0.34f);
                    path.close();
                    c.drawPath(path, p);
                }
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
