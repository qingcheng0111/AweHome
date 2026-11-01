package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * AweHome：拼成各个玻璃面板的行，尺寸照搬视频里的面板：238 dp 宽，每行 64 dp，选中时是一张白色圆角卡片，
 * 20 dp 的线条图标，15 sp 的标题下面一行 12 sp 的灰字，右边是箭头或单选圈。
 */
final class PanelUi {
    static final int PANEL_WIDTH_DP = 238;
    private static final int ROW_DP = 64;
    private static final int[] FOCUSED = {android.R.attr.state_focused};
    private static final int[] PLAIN = {};

    final Context ctx;
    final float dp;
    /** 液态玻璃的文字带一层柔和阴影，放在更透、更亮的玻璃上也看得清。 */
    private final boolean liquidText;

    PanelUi(Context ctx, boolean liquidText) {
        this.ctx = ctx;
        this.dp = ctx.getResources().getDisplayMetrics().density;
        this.liquidText = liquidText;
    }

    int px(float dips) {
        return Math.round(dips * dp);
    }

    /** 面板的标题，居中，如「偏好」或「背景」。 */
    TextView header(CharSequence title) {
        TextView t = text(title, 16, 0xF2FFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(16), px(12), px(16), px(10));
        return t;
    }

    /** 打开一页或执行一个操作的行：图标、标题、下面一行当前的值，以及右边的箭头。 */
    LinearLayout navRow(int icon, CharSequence title, CharSequence value, boolean chevron, Runnable action) {
        LinearLayout row = row(action);
        if (icon != 0) row.addView(icon(icon), iconParams());
        row.addView(titles(title, value), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (chevron) {
            ImageView c = icon(R.drawable.ic_chevron);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(16), px(16));
            lp.setMarginStart(px(6));
            row.addView(c, lp);
        }
        return row;
    }

    /** 页面里的一个选项：标题（以及下面一行），和一个选中时填满的单选圈。 */
    LinearLayout radioRow(CharSequence title, CharSequence detail, boolean checked, Runnable action) {
        LinearLayout row = row(action);
        row.addView(titles(title, detail), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Radio radio = new Radio(ctx, dp);
        radio.setChecked(checked);
        radio.setDuplicateParentStateEnabled(true);
        radio.setTag("radio");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(18), px(18));
        lp.setMarginStart(px(8));
        row.addView(radio, lp);
        return row;
    }

    /** 前面带一个色点的选项，用于颜色背景（「午夜蓝」）。 */
    LinearLayout colorRow(int color, CharSequence title, boolean checked, Runnable action) {
        LinearLayout row = radioRow(title, null, checked, action);
        View dot = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(px(1), 0x40FFFFFF);
        dot.setBackground(d);
        row.addView(dot, 0, iconParams());
        return row;
    }

    static void setChecked(View row, boolean checked) {
        View radio = row.findViewWithTag("radio");
        if (radio instanceof Radio) ((Radio) radio).setChecked(checked);
    }

    /** 页面里的说明文字：一行标题和一行灰字，不能获得焦点。 */
    LinearLayout note(CharSequence title, CharSequence body) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(18), px(12), px(18), px(10));
        if (title != null) box.addView(text(title, 14, 0xE6FFFFFF, true));
        if (body != null) {
            TextView b = text(body, 12, 0x99FFFFFF, false);
            b.setSingleLine(false);
            b.setPadding(0, px(4), 0, 0);
            box.addView(b);
        }
        return box;
    }

    View divider() {
        View line = new View(ctx);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(0.7f)));
        lp.setMargins(px(16), px(6), px(16), px(6));
        line.setLayoutParams(lp);
        return line;
    }

    /** 根页面末尾的品牌行：「AweHome」下面一行「简洁、无广告的电视桌面。」，再下面是版本号。 */
    LinearLayout footer(CharSequence brand, CharSequence tagline, CharSequence version) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(22), px(14), px(18), px(18));
        box.addView(text(brand, 13, 0xD9FFFFFF, true));
        TextView t = text(tagline, 12, 0x8CFFFFFF, false);
        t.setPadding(0, px(4), 0, 0);
        box.addView(t);
        // AweHome：最底下一行版本号（「版本 1.0.17」），更新后一眼看出装的是哪一版。
        if (!TextUtils.isEmpty(version)) {
            TextView v = text(version, 11, 0x73FFFFFF, false);
            v.setPadding(0, px(6), 0, 0);
            box.addView(v);
        }
        return box;
    }

    TextView text(CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (medium) t.getPaint().setFakeBoldText(false);
        t.setTypeface(android.graphics.Typeface.create(medium ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
        if (liquidText) t.setShadowLayer(3 * dp, 0, 0.6f * dp, 0x59000000);
        return t;
    }

    private LinearLayout titles(CharSequence title, CharSequence value) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setDuplicateParentStateEnabled(true);
        TextView t = text(title, 15, 0, false);
        t.setTextColor(colors(0xFF1C1C1E, 0xF2FFFFFF));
        t.setDuplicateParentStateEnabled(true);
        box.addView(t);
        if (!TextUtils.isEmpty(value)) {
            TextView v = text(value, 12, 0, false);
            v.setTextColor(colors(0xFF6E6E73, 0x99FFFFFF));
            v.setDuplicateParentStateEnabled(true);
            v.setPadding(0, px(5), 0, 0);
            v.setTag("value");
            box.addView(v);
        }
        return box;
    }

    /** 更新某行标题下面的那行灰字（它所在的页面开着时值变了）。 */
    static void setValue(View row, CharSequence value) {
        View v = row.findViewWithTag("value");
        if (v instanceof TextView) ((TextView) v).setText(value);
    }

    private ImageView icon(int res) {
        ImageView i = new ImageView(ctx);
        i.setImageResource(res);
        i.setImageTintList(colors(0xFF1C1C1E, 0xE6FFFFFF));
        i.setDuplicateParentStateEnabled(true);
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return i;
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(20), px(20));
        lp.setMarginEnd(px(14));
        return lp;
    }

    private static ColorStateList colors(int focused, int plain) {
        return new ColorStateList(new int[][]{FOCUSED, PLAIN}, new int[]{focused, plain});
    }

    /**
     * 可获得焦点的 64 dp 行。确认键在松开时才生效，而且只对在这一行上按下的那次：打开菜单的那次长按松开时，
     * 不能选中菜单的第一行。
     */
    private LinearLayout row(Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(px(ROW_DP));
        // 先设背景：带 inset 的 drawable 会把视图的 padding 设成它的 inset，覆盖掉我们自己的。
        row.setBackground(rowBackground());
        row.setPadding(px(18), px(8), px(16), px(8));
        row.setFocusable(true);
        row.setClickable(true);
        row.setSoundEffectsEnabled(false);
        if (action != null) {
            row.setOnClickListener(v -> action.run());
            final boolean[] pressed = {false};
            row.setOnKeyListener((v, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) pressed[0] = true;
                    return true;
                }
                boolean go = pressed[0];
                pressed[0] = false;
                if (go) action.run();
                return true;
            });
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private Drawable rowBackground() {
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF7FFFFFF);
        card.setCornerRadius(16 * dp);
        StateListDrawable s = new StateListDrawable();
        s.addState(FOCUSED, new android.graphics.drawable.InsetDrawable(card, px(2), px(3), px(2), px(3)));
        s.addState(PLAIN, new ColorDrawable(Color.TRANSPARENT));
        // AweHome：白底随焦点立即出现、立即消失。文字颜色是瞬间切换的，白底若淡入淡出，中间会有一帧
        // 黑字压在深色玻璃上（或白字压在白底上），看起来像闪了一下。
        s.setExitFadeDuration(0);
        s.setEnterFadeDuration(0);
        return s;
    }

    /** 屏幕下方的深色小胶囊提示，如「已删除这张图片」。 */
    static void toast(Context ctx, CharSequence message) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        TextView t = new TextView(ctx);
        t.setText(message);
        t.setTextColor(0xF2FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int h = Math.round(14 * dp), v = Math.round(8 * dp);
        t.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE62A2A2E);
        bg.setCornerRadius(18 * dp);
        bg.setStroke(Math.round(dp), 0x33FFFFFF);
        t.setBackground(bg);
        Toast toast = new Toast(ctx.getApplicationContext());
        toast.setView(t);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, Math.round(48 * dp));
        toast.show();
    }

    /** 单选圈：未选中是空心的，选中时中间填满；在白色的选中卡片上是深色。 */
    static final class Radio extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private boolean checked;

        Radio(Context ctx, float dp) {
            super(ctx);
            this.dp = dp;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.6f * dp);
        }

        void setChecked(boolean on) {
            checked = on;
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean focused = false;
            for (int s : getDrawableState()) if (s == android.R.attr.state_focused) focused = true;
            int color = focused ? 0xFF1C1C1E : 0xB3FFFFFF;
            ring.setColor(color);
            dot.setColor(color);
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - ring.getStrokeWidth();
            canvas.drawCircle(cx, cy, r, ring);
            if (checked) canvas.drawCircle(cx, cy, r * 0.48f, dot);
        }
    }

    /** 简单的一列竖排的行。 */
    LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(px(8), 0, px(8), px(8));
        return l;
    }

    /** 装着一张图的白色圆角卡片，如「手机传图」页上的二维码。 */
    FrameLayout card(View content, int padding) {
        FrameLayout f = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(16 * dp);
        f.setBackground(bg);
        f.setPadding(px(padding), px(padding), px(padding), px(padding));
        f.addView(content);
        return f;
    }
}
