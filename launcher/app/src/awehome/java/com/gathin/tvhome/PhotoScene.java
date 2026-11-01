package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AweHome：图片屏保（「屏幕保护」→ 图片）：背景设置里各分类（内置照片、必应精选、热门影视、墙纸文件夹）中
 * 选定那一类（或全部分类）的照片打乱后轮播，每 30 秒慢慢交叉淡入一张，角上显示时间，底部显示图片名称。时间和名称每次换图时轻微挪动位置，
 * 避免烧屏。由 {@link PhotoActivity} 显示；电视自己的设置里选它作为 Android 屏保时，由 {@link PhotoDream} 显示。
 */
final class PhotoScene {
    private static final long TURN_MS = 30_000L;
    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final ImageView[] layers = new ImageView[2];
    private final FrameLayout root;
    private final TextView caption;
    private final TextClock clock;
    private int front, index;
    private List<Backgrounds.Item> items;
    private final Runnable turn = new Runnable() {
        @Override
        public void run() {
            next();
            handler.postDelayed(this, TURN_MS);
        }
    };

    PhotoScene(Context ctx) {
        this.ctx = ctx;
        float dp = ctx.getResources().getDisplayMetrics().density;
        root = new FrameLayout(ctx);
        root.setBackgroundColor(0xFF000000);
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(ctx);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            layers[i] = v;
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        clock = addClock(ctx, root);
        caption = addCaption(ctx, root);
    }

    /** 右上角的时间，几个屏保共用。 */
    static TextClock addClock(Context ctx, FrameLayout root) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        TextClock clock = new TextClock(ctx);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        clock.setTextColor(0xF2FFFFFF);
        clock.setShadowLayer(10 * dp, 0, 2 * dp, 0x99000000);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clp.setMargins(0, Math.round(36 * dp), Math.round(56 * dp), 0);
        root.addView(clock, clp);
        return clock;
    }

    /** 底部的图片名称（视频屏保里是地名）。 */
    static TextView addCaption(Context ctx, FrameLayout root) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        TextView caption = new TextView(ctx);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        caption.setTextColor(0xD9FFFFFF);
        caption.setShadowLayer(8 * dp, 0, dp, 0xCC000000);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        tlp.setMargins(Math.round(56 * dp), 0, Math.round(56 * dp), Math.round(40 * dp));
        root.addView(caption, tlp);
        return caption;
    }

    View view() {
        return root;
    }

    void start() {
        items = items(ctx, HomePrefs.saverPhotoSource(ctx));
        // 选中的分类还没有照片（比如墙纸文件夹是空的）时，用全部分类。
        if (items.isEmpty()) items = items(ctx, "");
        Collections.shuffle(items);
        index = 0;
        handler.removeCallbacks(turn);
        handler.post(turn);
    }

    void stop() {
        handler.removeCallbacks(turn);
    }

    /** 屏保可选的照片分类，顺序与「屏幕保护 → 图片」的下一级菜单一致（"" 为全部分类）。 */
    static final String[] SOURCES = {"", HomePrefs.SOURCE_THEME, HomePrefs.SOURCE_BUILTIN, HomePrefs.SOURCE_BING, HomePrefs.SOURCE_HOT,
            HomePrefs.SOURCE_FOLDER};

    /** 某个分类的照片；"" 为全部分类合在一起。 */
    static List<Backgrounds.Item> items(Context ctx, String source) {
        List<Backgrounds.Item> out = new ArrayList<>();
        for (String s : SOURCES) {
            if (s.isEmpty() || !(source.isEmpty() || source.equals(s))) continue;
            out.addAll(Backgrounds.items(ctx, s));
        }
        return out;
    }

    void close() {
        stop();
        decoder.shutdownNow();
    }

    private void next() {
        if (items == null || items.isEmpty()) return;
        Backgrounds.Item item = items.get(index++ % items.size());
        DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        decoder.execute(() -> {
            Bitmap b = Backgrounds.decode(ctx, item, m.widthPixels, m.heightPixels, Bitmap.Config.ARGB_8888);
            handler.post(() -> {
                if (b == null) return;
                ImageView in = layers[1 - front], out = layers[front];
                in.setImageBitmap(b);
                in.bringToFront();
                in.animate().alpha(1f).setDuration(1500).withEndAction(() -> {
                    out.setAlpha(0f);
                    out.setImageDrawable(null);
                }).start();
                front = 1 - front;
                clock.bringToFront();
                caption.bringToFront();
                caption.setText(item.caption == null ? "" : item.caption);
                // 每次换图轻微挪动，防止烧屏。
                float dp = ctx.getResources().getDisplayMetrics().density;
                float dx = (float) (Math.random() * 16 - 8) * dp, dy = (float) (Math.random() * 12 - 6) * dp;
                clock.setTranslationX(dx);
                clock.setTranslationY(dy);
                caption.setTranslationX(-dx);
                caption.setTranslationY(-dy);
            });
        });
    }
}
