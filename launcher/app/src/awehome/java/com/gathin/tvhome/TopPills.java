package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.List;

/**
 * AweHome：视频里的右上角按钮。每个都是圆形图标，同一时刻只有一个展开成带文字的胶囊：焦点所在的那个，
 * 以及面板正开着的那个；Wi-Fi 按钮的文字是网络名称。焦点离开顶栏时，展开的按钮收回成圆形。没有焦点的按钮都是
 * 一块盖在背后背景上的玻璃（{@link #setBackdrop}），看起来嵌在画面里，而不是浮在上面。
 */
final class TopPills {
    private final List<TextView> pills;
    private final float dp;
    private final ViewGroup bar;
    private TextView open;
    /** 各按钮在 {@link FilmGlass} 里的登记名，建好一次，不再每帧拼字符串。 */
    private final String[] glassKeys;

    TopPills(List<TextView> pills, TextView first) {
        this.pills = pills;
        glassKeys = new String[pills.size()];
        for (int i = 0; i < glassKeys.length; i++) glassKeys[i] = "pill" + i;
        this.bar = (ViewGroup) first.getParent();
        this.dp = first.getResources().getDisplayMetrics().density;
        for (TextView p : pills) {
            View.OnFocusChangeListener previous = p.getOnFocusChangeListener();
            p.setOnFocusChangeListener((v, focused) -> {
                if (previous != null) previous.onFocusChange(v, focused);
                if (focused) {
                    expand((TextView) v, true);
                    KeySounds.focusMoved();
                } else {
                    // 等焦点落到下一个视图后再看：移到另一个按钮时由它自己展开，离开顶栏时收回。
                    bar.post(this::collapseUnlessFocused);
                }
            });
        }
        // AweHome：视频背景下，按钮背后的玻璃由视频层画。按钮展开、收起时其他按钮被挤着挪位置，自身并不重画，
        // 所以每一帧按实际位置重新登记（只有变化时才让视频层重画），选中变白或隐藏的按钮撤掉玻璃。
        bar.getViewTreeObserver().addOnPreDrawListener(glassSync);
        // 起始时都是圆的，只有焦点所在的按钮展开。
        for (TextView p : pills) {
            if (p.isFocused()) expand(p, false);
            else collapse(p);
        }
    }

    /**
     * 焦点已不在任何按钮上：收回展开的那个。按钮打开的面板（偏好、信号源）是另一个窗口，开着时按钮仍保有焦点，
     * 所以面板开着时按钮保持展开；面板关闭后由 MainActivity 再调一次。
     */
    void collapseUnlessFocused() {
        if (open == null) return;
        for (TextView p : pills) if (p.isFocused()) return;
        TransitionManager.beginDelayedTransition(bar, new ChangeBounds().setDuration(200));
        collapse(open);
        open = null;
    }

    /** 按钮展开时显示的文字：它的文本，收成圆形时存在 content description 里。 */
    private static CharSequence label(TextView pill) {
        return pill.getContentDescription();
    }

    void expand(TextView pill, boolean animate) {
        if (pill.getVisibility() != View.VISIBLE) return;
        if (pill == open) {
            refresh();
            return;
        }
        if (animate) TransitionManager.beginDelayedTransition(bar, new ChangeBounds().setDuration(200));
        TextView was = open;
        open = pill;
        if (was != null) collapse(was);
        show(pill);
    }

    /** 有按钮被隐藏或显示后（电视其实没有信号源）：被隐藏的展开按钮收回，焦点所在的按钮展开。 */
    void ensureOpen() {
        if (open != null && open.getVisibility() != View.VISIBLE) {
            collapse(open);
            open = null;
        }
        for (TextView p : pills) {
            if (p.isFocused() && p.getVisibility() == View.VISIBLE) {
                expand(p, false);
                return;
            }
        }
    }

    /** Wi-Fi 按钮的名称变了之后把文字放回去（MainActivity.updateWifiLabel 会清空文本）。 */
    void refresh() {
        for (TextView p : pills) {
            if (p == open) show(p);
            else if (!TextUtils.isEmpty(p.getText())) collapse(p);
        }
    }

    private void show(TextView p) {
        CharSequence text = label(p);
        if (TextUtils.isEmpty(text)) return;
        if (!TextUtils.equals(p.getText(), text)) p.setText(text);
        p.setPaddingRelative(Math.round(14 * dp), 0, Math.round(18 * dp), 0);
        p.setCompoundDrawablePadding(Math.round(8 * dp));
        p.setMaxWidth(Math.round(220 * dp));
        p.setSingleLine(true);
        p.setEllipsize(TextUtils.TruncateAt.END);
        ViewGroup.LayoutParams lp = p.getLayoutParams();
        if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.setLayoutParams(lp);
        }
    }

    private void collapse(TextView p) {
        if (p.getText().length() > 0) p.setText(null);
        int pad = Math.round(10 * dp);
        p.setPaddingRelative(pad, 0, pad, 0);
        p.setCompoundDrawablePadding(0);
        ViewGroup.LayoutParams lp = p.getLayoutParams();
        int round = Math.round(42 * dp);
        if (lp.width != round) {
            lp.width = round;
            p.setLayoutParams(lp);
        }
    }

    /** AweHome：每个按钮背后的玻璃（{@link GlassLook.Pill}），按钮聚焦时仍是白色。 */
    private final List<GlassLook.Pill> glass = new java.util.ArrayList<>();
    private Bitmap blurred, clear;

    /** 新的背景已经显示：重度模糊图给磨砂用，轻度模糊图给液态用（{@link Backdrop#blurred}、{@link Backdrop#clear}）。 */
    void setBackdrop(Bitmap blurred, Bitmap clear) {
        this.blurred = blurred;
        this.clear = clear;
        apply();
    }

    private final int[] at = new int[2];
    private final ViewTreeObserver.OnPreDrawListener glassSync = () -> {
        syncFilmGlass();
        return true;
    };

    private void syncFilmGlass() {
        // 没有实时玻璃时登记表已被清空（FilmGlass.setActive），不必逐个撤。
        if (!FilmGlass.active()) return;
        for (int i = 0; i < glassKeys.length; i++) {
            TextView p = pills.get(i);
            String key = glassKeys[i];
            // 液态玻璃 2 的玻璃是透明的，视频层不必画。
            if (FilmGlass.clear() || !p.isShown() || p.isFocused() || p.getWidth() == 0 || p.getHeight() == 0) {
                FilmGlass.remove(key);
                continue;
            }
            p.getLocationOnScreen(at);
            float h = p.getHeight();
            FilmGlass.put(key, at[0], at[1], at[0] + p.getWidth(), at[1] + h, h / 2f,
                    FilmGlass.frosted() ? GlassLook.FROSTED_PILL_LOOK : GlassLook.LIQUID_PILL_LOOK, p.getAlpha());
        }
    }

    /** 重画各按钮的玻璃（实时玻璃开始或停止时）。 */
    void invalidate() {
        for (TextView p : pills) p.invalidate();
    }

    /**
     * AweHome「模糊效果」，见 {@link GlassLook}：磨砂是发灰的柔光玻璃，液态是带背后颜色的亮边玻璃珠。
     * 用哪种只记在 {@link FilmGlass#frosted} 一处；改了之后调这里重画。
     */
    void restyle() {
        apply();
    }

    private void apply() {
        if (glass.isEmpty()) {
            for (TextView p : pills) {
                GlassLook.Pill pill = new GlassLook.Pill(p);
                GradientDrawable focused = new GradientDrawable();
                focused.setColor(0xF5FFFFFF);
                focused.setCornerRadius(21 * dp);
                StateListDrawable s = new StateListDrawable();
                s.addState(new int[]{android.R.attr.state_focused}, focused);
                s.addState(new int[]{}, pill);
                p.setBackground(s);
                glass.add(pill);
            }
        }
        boolean frosted = FilmGlass.frosted();
        for (GlassLook.Pill pill : glass) pill.set(frosted ? blurred : (clear != null ? clear : blurred), frosted);
    }
}
