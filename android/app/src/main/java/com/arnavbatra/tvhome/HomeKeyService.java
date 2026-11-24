package com.arnavbatra.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's Home button open this launcher, without disabling Google TV.
 *
 *  - Home (short press): opens this launcher.
 *  - Home (long press):  opens Google TV's own home, the escape hatch.
 *  - Google TV's home appearing by itself (power-on, waking from standby, an app exiting to it) is handed
 *    over to this launcher, unless you asked for Google TV on purpose (long press, or the All apps tile).
 *
 * Redirects are rate-limited so a crash here can never trap the TV in a loop.
 * Turn on:  adb shell settings put secure enabled_accessibility_services com.arnavbatra.tvhome/com.arnavbatra.tvhome.HomeKeyService
 *           adb shell settings put secure accessibility_enabled 1
 * Turn off: Settings > Accessibility on the TV, or tv-backup/restore-home.sh
 */
public class HomeKeyService extends AccessibilityService {

    static final String STOCK_HOME_PKG = "com.google.android.apps.tv.launcherx";
    private static final long LONG_PRESS_MS = 600;

    /** Set when the person asked for Google TV's home on purpose; cleared when this launcher is back. */
    static volatile boolean stockRequested = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean homeDown = false;
    private boolean longFired = false;
    private final long[] recent = new long[3];      // last redirect times, for the rate limit
    private int recentIdx = 0;

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            longFired = true;
            stockRequested = true;
            performGlobalAction(GLOBAL_ACTION_HOME);   // the system's Home = Google TV; not routed back through here
        }
    };

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;      // every other key passes straight through
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                homeDown = true;
                longFired = false;
                main.postDelayed(longPress, LONG_PRESS_MS);
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            main.removeCallbacks(longPress);
            if (homeDown && !longFired) openLauncher();
            homeDown = false;
            return true;
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (ev.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = ev.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName())) { stockRequested = false; return; }   // we're in front again
        if (!p.equals(STOCK_HOME_PKG) || stockRequested) return;
        CharSequence cls = ev.getClassName();
        if (cls == null || !cls.toString().endsWith(".HomeActivity")) return;   // only Google TV's home screen itself
        if (rateLimited()) return;
        openLauncher();                                                       // power-on / wake / fell back to it
    }

    /** At most 3 automatic hand-overs in 30 s; after that Google TV is left alone. */
    private boolean rateLimited() {
        long now = SystemClock.elapsedRealtime();
        long oldest = recent[recentIdx];
        if (oldest != 0 && now - oldest < 30_000) return true;
        recent[recentIdx] = now;
        recentIdx = (recentIdx + 1) % recent.length;
        return false;
    }

    private void openLauncher() {
        stockRequested = false;
        try {
            Intent in = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(in);
        } catch (Exception ignored) {
            performGlobalAction(GLOBAL_ACTION_HOME);    // never leave the remote's Home doing nothing
        }
    }

    @Override public void onInterrupt() { }
}
