package com.jeevitesh.ultron;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

/** Shows ONLY the hologram circle floating over whatever app is open (for example the home screen). */
final class OverlayController {
    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private HologramView view;
    private boolean shown = false;
    private Runnable onTap;
    private final Runnable hideRunnable = this::hideNow;

    OverlayController(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    void setOnTap(Runnable r) { onTap = r; }

    void show(int mode) {
        ui.post(() -> {
            ui.removeCallbacks(hideRunnable);
            if (!Settings.canDrawOverlays(ctx)) return;
            try {
                if (!shown) {
                    view = new HologramView(ctx);
                    view.setOnClickListener(v -> { if (onTap != null) onTap.run(); });
                    int size = (int) (230 * ctx.getResources().getDisplayMetrics().density);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    lp.y = (int) (70 * ctx.getResources().getDisplayMetrics().density);
                    wm.addView(view, lp);
                    shown = true;
                }
                view.setMode(mode);
            } catch (Exception ignored) { shown = false; }
        });
    }

    void setMode(int mode) { ui.post(() -> { if (view != null && shown) view.setMode(mode); }); }
    void flash() { ui.post(() -> { if (view != null && shown) view.flash(); }); }
    void hideLater(long ms) { ui.removeCallbacks(hideRunnable); ui.postDelayed(hideRunnable, ms); }
    void hide() { ui.post(this::hideNow); }

    private void hideNow() {
        if (shown && view != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        shown = false;
        view = null;
    }
}
