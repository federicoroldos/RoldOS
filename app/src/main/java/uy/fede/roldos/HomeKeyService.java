package uy.fede.roldos;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
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
 */
public class HomeKeyService extends AccessibilityService {
    static final String STOCK_PKG = "com.htc.luminaos";
    private static final String STOCK_HOME = "com.htc.luminaos.activity.MainActivity";
    /** The "Projection" tile inside the stock settings menu (its page can't be opened directly). */
    private static final String PROJECTION_TILE_ID = STOCK_PKG + ":id/rl_project";
    private static final long REQUEST_WINDOW_MS = 4000;

    /** Set by MainActivity right before it opens the stock settings menu. */
    private static volatile long projectionRequestedAt;

    static void requestProjection() { projectionRequestedAt = SystemClock.uptimeMillis(); }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) goHome();
        return true; // swallow both DOWN and UP so the stock launcher never sees it
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!STOCK_PKG.contentEquals(String.valueOf(e.getPackageName()))) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && STOCK_HOME.contentEquals(String.valueOf(e.getClassName()))) {
            goHome();
            return;
        }
        if (projectionRequestedAt != 0) clickProjectionTile();
    }

    /** Completes the "Calibration" shortcut: presses Projection once the settings menu is on screen. */
    private void clickProjectionTile() {
        if (SystemClock.uptimeMillis() - projectionRequestedAt > REQUEST_WINDOW_MS) {
            projectionRequestedAt = 0;
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(PROJECTION_TILE_ID);
        if (hits == null || hits.isEmpty()) hits = root.findAccessibilityNodeInfosByText("Projection");
        if (hits == null) return;
        for (AccessibilityNodeInfo n : hits) {
            AccessibilityNodeInfo target = n;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                projectionRequestedAt = 0;
                return;
            }
        }
    }

    private void goHome() {
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
