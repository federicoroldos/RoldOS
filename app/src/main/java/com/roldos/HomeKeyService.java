package com.roldos;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Some devices (e.g. Magcubic projectors) send the remote's Home key straight to their
 * stock launcher, ignoring the default-home setting. This service catches the key and,
 * as a fallback, bounces us back whenever the stock launcher's main screen appears.
 * Only the stock launcher's MAIN screen is redirected: its settings, keystone and
 * calibration screens are left alone.
 *
 * It also completes the "Calibration" tile: the stock Projection page isn't exported, so
 * RoldOS opens the stock settings menu and this service presses "Projection" for the user.
 */
public class HomeKeyService extends AccessibilityService {
    static final String STOCK_PKG = "com.htc.luminaos";
    static final String STOCK_HOME = "com.htc.luminaos.activity.MainActivity";
    private static final String STOCK_SETTINGS = "com.htc.luminaos.activity.MainSettingActivity";
    private static final String STOCK_PROJECTION = "com.htc.luminaos.activity.ProjectActivity";
    /** The "Projection" tile inside the stock settings menu. */
    private static final String PROJECTION_TILE_ID = STOCK_PKG + ":id/rl_project";
    /** The "Installation Mode" row: only exists on the Projection page itself. */
    private static final String PROJECTION_PAGE_MARKER = STOCK_PKG + ":id/rl_project_mode";
    /** Long enough to survive the stock launcher kicking us off and MainActivity re-enabling us. */
    private static final long REQUEST_WINDOW_MS = 7000;
    private static final long POLL_MS = 150;

    private static volatile HomeKeyService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** When MainActivity asked for the Projection page (0 = no request pending). */
    private static volatile long projectionRequestedAt;
    /** True once we've taken the user into Projection from the Calibration tile. */
    private static volatile boolean openedProjection;
    /** Last stock-launcher screen seen on top (null once RoldOS is back in front). */
    private static volatile String stockTop;
    /**
     * True while the user deliberately visits the stock launcher (the "Original launcher"
     * tile): don't bounce them back. The next Home press clears it.
     */
    private static volatile boolean stockHomeAllowed;
    /** We swallowed a Back DOWN, so swallow its UP too. */
    private boolean backSwallowed;
    /** Back presses we injected to climb out of a stale stock sub-page during one request. */
    private static volatile int backsSent;
    private static final int MAX_BACKS = 4;
    /** The pending request only wants the main settings menu (Settings tile), not Projection. */
    private static volatile boolean menuOnly;
    private long lastBackAt;

    static void allowStockHome() { stockHomeAllowed = true; }

    /**
     * Called right before MainActivity opens the stock settings menu. Polls the screen until
     * the menu is up and presses Projection. Polling (not just events) matters: when the menu
     * was already open from an earlier visit, Android brings it back without a new-window event.
     */
    static void requestProjection() {
        menuOnly = false;
        openedProjection = true; // this visit started from the Calibration tile
        startRequest();
    }

    /**
     * Called right before MainActivity opens the stock settings menu for the Settings tile.
     * The owner's rule: Settings must land strictly on the main settings menu. The stock menu
     * reopens wherever it was left (Projection, Picture Mode...), so step back until the menu
     * itself is on screen, and press nothing.
     */
    static void requestSettingsMenu() {
        menuOnly = true;
        openedProjection = false;
        startRequest();
    }

    private static void startRequest() {
        projectionRequestedAt = SystemClock.uptimeMillis();
        backsSent = 0;
        HomeKeyService s = instance;
        if (s != null) {
            s.handler.removeCallbacks(s.poll);
            s.handler.postDelayed(s.poll, POLL_MS);
        }
    }

    /** RoldOS is on screen again: any stock-launcher visit is over. */
    static void onLauncherShown() {
        openedProjection = false;
        stockTop = null;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (projectionRequestedAt == 0) return;
            if (SystemClock.uptimeMillis() - projectionRequestedAt > REQUEST_WINDOW_MS) {
                projectionRequestedAt = 0;
                return;
            }
            if (!tryClickProjectionTile()) handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // The stock settings menu clears the enabled-accessibility list when it opens, which
        // unbinds us mid-Calibration. MainActivity puts us back; pick up where we left off.
        if (projectionRequestedAt != 0) handler.postDelayed(poll, POLL_MS);
        // After a reboot or a RoldOS update the stock home is often already on screen when we
        // bind, so no "window changed" event will come to bounce it. Ask which screen is up.
        new Thread(this::leaveStockHome, "home-check").start();
    }

    /** If the stock home is the resumed screen (and wasn't asked for), bring RoldOS up. */
    private void leaveStockHome() {
        for (long wait : new long[] {1500, 3500}) {
            SystemClock.sleep(wait);
            String top = LocalAdb.shell(this,
                    "dumpsys activity activities | grep -m1 mResumedActivity", 0);
            if (top == null) return; // debug service unavailable: events still bounce later
            if (top.contains(STOCK_PKG + "/.activity.MainActivity") || top.contains(STOCK_HOME)) {
                if (!stockHomeAllowed) handler.post(this::goHome);
                return;
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                stockHomeAllowed = false;
                goHome();
            }
            return true; // swallow both DOWN and UP so the stock launcher never sees it
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            // Back on a Projection page we opened from the Calibration tile returns to RoldOS
            // instead of dropping the user into the stock settings menu underneath.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                backSwallowed = openedProjection && isOnProjectionPage();
                return backSwallowed;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && backSwallowed) {
                backSwallowed = false;
                goHome();
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!STOCK_PKG.contentEquals(String.valueOf(e.getPackageName()))) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String cls = String.valueOf(e.getClassName());
            if (cls.startsWith(STOCK_PKG + ".activity.")) stockTop = cls;
            if (STOCK_HOME.equals(cls)) {
                if (!stockHomeAllowed) goHome();
                return;
            }
            if (STOCK_PROJECTION.equals(cls) && projectionRequestedAt != 0) {
                // The menu reopened straight on Projection (left there last time): done.
                projectionRequestedAt = 0;
                openedProjection = true;
                return;
            }
        }
        if (projectionRequestedAt != 0) tryClickProjectionTile();
    }

    /**
     * True when the stock Projection page itself (not one of its sub-pages) is on screen.
     * Checked from the live screen, not from window events: the stock menu unbinds us now and
     * then, so we can miss the "Projection opened" event.
     */
    private boolean isOnProjectionPage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !STOCK_PKG.contentEquals(String.valueOf(root.getPackageName()))) return false;
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(PROJECTION_PAGE_MARKER);
        return hits != null && !hits.isEmpty();
    }

    /**
     * Gets the user onto the Projection page. Returns true when done.
     * - Already on Projection (the stock menu reopens where it was left): done.
     * - On the settings menu: press Projection.
     * - On some other stock page left open from before (e.g. Picture Mode): step back, a
     *   few times at most, until one of the two above shows up.
     */
    private boolean tryClickProjectionTile() {
        if (projectionRequestedAt == 0) return true;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !STOCK_PKG.contentEquals(String.valueOf(root.getPackageName()))) return false;
        if (menuOnly) {
            // Settings tile: done as soon as the main menu (it has the Projection tile) shows.
            List<AccessibilityNodeInfo> menu = root.findAccessibilityNodeInfosByViewId(PROJECTION_TILE_ID);
            if (menu != null && !menu.isEmpty()) {
                projectionRequestedAt = 0;
                return true;
            }
            stepBack();
            return false;
        }
        List<AccessibilityNodeInfo> onPage = root.findAccessibilityNodeInfosByViewId(PROJECTION_PAGE_MARKER);
        if (onPage != null && !onPage.isEmpty()) {
            projectionRequestedAt = 0;
            return true;
        }
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(PROJECTION_TILE_ID);
        if ((hits == null || hits.isEmpty()) && !STOCK_SETTINGS.equals(stockTop)) {
            stepBack();
            return false;
        }
        // Text fallback only on the settings menu itself, never on the Projection page's title.
        if ((hits == null || hits.isEmpty()) && STOCK_SETTINGS.equals(stockTop)) {
            hits = root.findAccessibilityNodeInfosByText("Projection");
        }
        if (hits == null) return false;
        for (AccessibilityNodeInfo n : hits) {
            AccessibilityNodeInfo target = n;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                projectionRequestedAt = 0;
                openedProjection = true;
                return true;
            }
        }
        return false;
    }

    /** One Back press to climb out of a stale stock page; rate-limited and capped per request. */
    private void stepBack() {
        long now = SystemClock.uptimeMillis();
        if (backsSent < MAX_BACKS && now - lastBackAt > 450) {
            backsSent++;
            lastBackAt = now;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private void goHome() {
        openedProjection = false;
        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
            // Never crash the service: if we can't start, the stock launcher stays usable.
        }
    }

    @Override
    public void onInterrupt() { }
}
