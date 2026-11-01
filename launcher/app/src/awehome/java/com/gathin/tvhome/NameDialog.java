package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * AweHome：「输入名称」，输入名字用的玻璃小对话框（HDMI 端口、文件夹、城市的名字）：一个标题、
 * 一个由电视键盘输入的文本框，以及「保存」「取消」。
 */
final class NameDialog {
    private NameDialog() {
    }

    static void show(Activity activity, Bitmap backdrop, CharSequence title, String initial, Consumer<String> onSave) {
        float dp = activity.getResources().getDisplayMetrics().density;
        PanelUi ui = new PanelUi(activity, false);
        PanelWindow window = new PanelWindow(activity);
        GlassPanel glass = new GlassPanel(activity);
        glass.setRadius(18 * dp);
        glass.setBackdrop(backdrop, false);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.px(18), ui.px(16), ui.px(18), ui.px(14));
        TextView t = ui.text(title, 15, 0xF2FFFFFF, true);
        box.addView(t);
        EditText field = new EditText(activity);
        field.setText(initial == null ? "" : initial);
        field.setSelectAllOnFocus(true);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setTextColor(0xFFFFFFFF);
        field.setHighlightColor(0x664C8DFF);
        field.setPadding(ui.px(12), ui.px(8), ui.px(12), ui.px(8));
        field.setBackground(fieldBackground(dp));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(40));
        flp.setMargins(0, ui.px(12), 0, ui.px(12));
        box.addView(field, flp);
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.END);
        TextView save = button(activity, ui, activity.getString(R.string.dialog_save));
        TextView cancel = button(activity, ui, activity.getString(R.string.dialog_cancel));
        buttons.addView(save);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(32));
        clp.setMarginStart(ui.px(8));
        buttons.addView(cancel, clp);
        box.addView(buttons);
        glass.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable done = () -> {
            window.dismiss();
            onSave.accept(field.getText().toString());
        };
        save.setOnClickListener(v -> done.run());
        cancel.setOnClickListener(v -> window.dismiss());
        field.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                save.requestFocus();
                return true;
            }
            return false;
        });
        window.setKeyHandler(event -> {
            KeySounds.onKey(event);
            // 在文本框上按下键到「保存」（否则输入法可能把这个键留给自己）。
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && field.isFocused()) {
                save.requestFocus();
                return true;
            }
            return false;
        });
        window.show(glass, Gravity.CENTER, 0, -ui.px(40), ui.px(420), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (window.window() != null) {
            window.window().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        field.requestFocus();
        field.post(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private static GradientDrawable fieldBackground(float dp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x52000000);
        d.setCornerRadius(10 * dp);
        d.setStroke(Math.round(dp), 0x33FFFFFF);
        return d;
    }

    private static TextView button(Context ctx, PanelUi ui, String text) {
        TextView b = ui.text(text, 13, 0, false);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF1C1C1E, 0xF2FFFFFF}));
        b.setGravity(Gravity.CENTER);
        b.setPadding(ui.px(16), 0, ui.px(16), 0);
        b.setMinWidth(ui.px(64));
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xF7FFFFFF);
        on.setCornerRadius(16 * ui.dp);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x26FFFFFF);
        off.setCornerRadius(16 * ui.dp);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, off);
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.px(32)));
        return b;
    }
}
