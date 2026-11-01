package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * AweHome：玻璃面板浮在里面的窗口。用 Dialog 而不是 PopupWindow：PopupWindow 在各行收到返回键之前就会关掉，
 * 而偏好面板需要用返回键回到上一页。
 */
final class PanelWindow {
    interface KeyHandler {
        /** 面板收到的每个按键，在各行处理之前；处理了返回 true。 */
        boolean onKey(KeyEvent event);
    }

    private final Dialog dialog;
    private KeyHandler keys;
    private Runnable onDismiss;

    PanelWindow(Activity activity) {
        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (keys != null && keys.onKey(event)) return true;
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) dismiss();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setOnDismissListener(d -> {
            if (onDismiss != null) onDismiss.run();
        });
    }

    void setKeyHandler(KeyHandler handler) {
        keys = handler;
    }

    void setOnDismiss(Runnable r) {
        onDismiss = r;
    }

    /** 按给定的 gravity 和偏移显示 {@code content}，大小 {@code width} × {@code height} 像素（或 WRAP_CONTENT）。 */
    void show(View content, int gravity, int x, int y, int width, int height) {
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setWindowAnimations(0);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.gravity = gravity;
            lp.x = x;
            lp.y = y;
            lp.width = width;
            lp.height = height;
            w.setAttributes(lp);
            w.getDecorView().setPadding(0, 0, 0, 0);
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            // 电视的全屏首页：没有状态栏要让位。
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
        dialog.show();
    }

    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    Window window() {
        return dialog.getWindow();
    }
}
