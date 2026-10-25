package com.jeevitesh.ultron;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * Lets Ultron really close apps. Android does not allow a normal app to kill another app that is on screen,
 * so (with the user's permission) this opens the app's info page and presses "Force stop", or presses "Clear all" in recents.
 * Every method blocks (sleeps) so call them from a background thread only.
 */
public class UltronAccess extends AccessibilityService {
    static volatile UltronAccess instance;

    private static final String[] FORCE_WORDS = {"force stop", "force close", "ఫోర్స్ స్టాప్", "బలవంతంగా"};
    private static final String[] CONFIRM_WORDS = {"ok", "force stop", "ఓకే", "సరే", "అవును"};
    private static final String[] CANCEL_WORDS = {"cancel", "రద్దు", "క్యాన్సిల్"};
    private static final String[] CLEAR_WORDS = {"clear all", "close all", "clear everything", "అన్నీ క్లియర్", "అన్నింటినీ", "అన్నీ మూసివేయి"};

    @Override protected void onServiceConnected() { instance = this; super.onServiceConnected(); }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    /** Package name of the app currently on screen, or null. */
    String foregroundPackage() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return null;
        CharSequence p = r.getPackageName();
        return p == null ? null : p.toString();
    }

    void goHome() { performGlobalAction(GLOBAL_ACTION_HOME); }

    private static AccessibilityNodeInfo findContains(AccessibilityNodeInfo root, String[] words) {
        for (String w : words) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(w);
            if (l != null && !l.isEmpty()) return l.get(0);
        }
        return null;
    }

    private static AccessibilityNodeInfo findExact(AccessibilityNodeInfo root, String[] words) {
        for (String w : words) {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByText(w);
            if (l == null) continue;
            for (AccessibilityNodeInfo n : l) {
                CharSequence t = n.getText();
                if (t != null && t.toString().trim().toLowerCase(Locale.ROOT).equals(w)) return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n;
        for (int d = 0; p != null && d < 7; d++) {
            if (p.isClickable()) return p;
            p = p.getParent();
        }
        return null;
    }

    /**
     * Force-stops an app through its App Info page.
     * Returns: "closed", "not_running" (button was disabled, the app was not running), or "failed".
     */
    String forceStop(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { return "failed"; }
        sleep(1200);
        int clicks = 0, waitedForDialog = 0;
        for (int n = 0; n < 16; n++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { sleep(400); continue; }
            if (clicks == 0) {
                AccessibilityNodeInfo fs = findContains(root, FORCE_WORDS);
                if (fs != null) {
                    AccessibilityNodeInfo btn = clickable(fs);
                    if (btn == null) { sleep(400); continue; }
                    if (!btn.isEnabled()) { sleep(300); goHome(); return "not_running"; }
                    if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { clicks = 1; sleep(900); continue; }
                }
            } else {
                boolean dialog = findContains(root, CANCEL_WORDS) != null;
                AccessibilityNodeInfo ok = findExact(root, CONFIRM_WORDS);
                if (ok != null && (dialog || waitedForDialog >= 3)) {
                    AccessibilityNodeInfo btn = clickable(ok);
                    if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        sleep(600);
                        goHome();
                        return "closed";
                    }
                }
                waitedForDialog++;
            }
            sleep(500);
        }
        goHome();
        return "failed";
    }

    /** Opens recents and presses "Clear all" / "Close all". Returns true only if that button was found and pressed. */
    boolean clearRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
        sleep(1400);
        for (int n = 0; n < 4; n++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo c = findContains(root, CLEAR_WORDS);
                AccessibilityNodeInfo btn = c == null ? null : clickable(c);
                if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    sleep(700);
                    goHome();
                    return true;
                }
            }
            sleep(500);
        }
        goHome();
        return false;
    }
}
