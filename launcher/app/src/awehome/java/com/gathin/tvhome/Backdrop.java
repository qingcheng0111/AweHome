package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AweHome：首页的背景，取代 {@link WallpaperPlayer}。显示「背景」页选中的来源（{@link Backgrounds}）：
 * 「选择墙纸」里选中的那张，或者每隔「更换背景」的间隔换一张，交叉淡入；图片名称显示在右上角按钮下面；焦点在应用网格里时，
 * 上面再盖一层模糊、提亮的副本。每换一张新图，都把它的颜色告诉首页，供右上角按钮取色。
 * AweHome：背景选「航拍视频」时改为播放影片（{@link AerialFilm}）：第一帧出来前先保留上一张图片，
 * 影片放不出来时退回图片。
 */
final class Backdrop {
    interface Listener {
        /**
         * 新图片已经显示：它的模糊副本、按钮在它上面的底色，以及它最鲜艳的颜色（0 为没有）。
         * AweHome：{@code clear} 是轻度模糊的副本，供液态玻璃使用（{@link GlassLook}）。
         */
        void onBackdrop(Bitmap blurred, Bitmap clear, int tint, int vivid);
    }

    private static final int FADE_MS = 700;
    private final Context ctx;
    private final FrameLayout root;
    private final ImageView[] layers = new ImageView[2];
    private final ImageView blurLayer;
    private final View veil;
    private final TextView caption;
    private final int width, height;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final SharedPreferences state;
    private final Listener listener;
    private Bitmap shown, blurred, clear;
    /** AweHome：两个图片层各自显示的图，用来判断哪张可以回收。 */
    private final Bitmap[] held = new Bitmap[2];
    private int front;
    private boolean active, closed, loading;
    private String displayedId = "";
    private boolean gridMode;
    private String source;
    private AerialFilm film;
    /** 本次显示首页时影片放不出来：在首页下次显示前先用内置照片代替。 */
    private boolean filmFailed;

    Backdrop(Context ctx, FrameLayout root, Listener listener) {
        this.ctx = ctx;
        this.root = root;
        this.listener = listener;
        DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        width = m.widthPixels;
        height = m.heightPixels;
        state = ctx.getSharedPreferences("backdrop", Context.MODE_PRIVATE);
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(ctx);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            v.setAlpha(i == 0 ? 1f : 0f);
            layers[i] = v;
            root.addView(v, i, match());
        }
        // 网格的磨砂背景：图片模糊成几百像素宽再拉伸回来，上面盖一层白色薄纱。
        blurLayer = new ImageView(ctx);
        blurLayer.setScaleType(ImageView.ScaleType.FIT_XY);
        blurLayer.setAlpha(0f);
        blurLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(blurLayer, 2, match());
        veil = new View(ctx);
        veil.setBackgroundColor(0x40D8D8DC);
        veil.setAlpha(0f);
        root.addView(veil, 3, match());
        caption = makeCaption(ctx, root);
        source = HomePrefs.bgSource(ctx);
        showFirst();
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** 首页打开时显示的那张图在这里、在主线程上解码，这样第一帧就已经有图。 */
    private void showFirst() {
        Backgrounds.Item item = next(false);
        if (item == null) return;
        Bitmap b = Backgrounds.decode(ctx, item, width, height, Bitmap.Config.ARGB_8888);
        if (b == null) return;
        present(item, b, false);
    }

    void start() {
        active = true;
        filmFailed = false;
        if (filmSource()) {
            film().play();
            return;
        }
        // 网络来源：先取新的内容，取到后如果首页还没有显示这个来源的图片，就显示出来。
        Backgrounds.refresh(ctx, source, false, () -> {
            if (closed) return;
            if (shown == null || !displayedId.startsWith(prefix(source))) update(true);
        });
        update(false);
    }

    void stop() {
        active = false;
        if (film != null) film.stop();
    }

    void close() {
        closed = true;
        active = false;
        decoder.shutdownNow();
        if (film != null) film.close();
    }

    private boolean filmSource() {
        return HomePrefs.SOURCE_AERIAL.equals(source) && !filmFailed;
    }

    /** 影片视图，第一次用时才创建：位于图片层之上、网格磨砂层之下。 */
    private AerialFilm film() {
        if (film == null) film = new AerialFilm(ctx, root, root.indexOfChild(blurLayer), true, filmHost);
        return film;
    }

    private final AerialFilm.Host filmHost = new AerialFilm.Host() {
        @Override
        public void onCaption(String text) {
            showCaption(text, true);
        }

        @Override
        public void onFrame(Bitmap small) {
            if (closed) return;
            // 模糊和取色在后台线程上做，主线程只换图，不卡顿。
            decoder.execute(() -> {
                Bitmap soft = Blur.of(small, 240, 10, 1.15f);
                Bitmap sharp = Blur.of(small, LiquidGlass.SOURCE_WIDTH, LiquidGlass.SOURCE_RADIUS, 1f);
                int tint = Blur.tint(small, 0.55f, 0f, 1f, 0.22f);
                int vivid = Blur.vivid(small);
                if (soft == null) return;
                root.post(() -> {
                    if (closed) return;
                    blurred = soft;
                    clear = sharp;
                    blurLayer.setImageBitmap(soft);
                    if (listener != null) listener.onBackdrop(soft, sharp, tint, vivid);
                });
            });
        }

        @Override
        public void onShowing(boolean on) {
            // 影片盖满屏幕时隐藏下面的图片层：面板玻璃是用软件方式绘制屏幕的，那里影片视图是透明的，
            // 不隐藏的话，玻璃里透出来的会是下面那张旧图片而不是影片。
            int v = on ? View.INVISIBLE : View.VISIBLE;
            layers[0].setVisibility(v);
            layers[1].setVisibility(v);
        }

        @Override
        public void onFailed() {
            if (closed) return;
            filmFailed = true;
            applyGridFrost();
            android.widget.Toast.makeText(ctx, R.string.aerial_failed, android.widget.Toast.LENGTH_LONG).show();
            displayedId = "";
            update(true);
        }
    };

    /**
     * AweHome：换语言后，标题按新语言重新显示：内置照片和航拍影片的地名有中英两种；必应精选随市场（中文用
     * zh-CN，其余用 en-US）在后台重新取一批，取到后渐变到新的一张。
     */
    void relocalize() {
        AerialFilm.resetCatalogue();
        if (film != null && filmSource()) {
            film.recaption();
            return;
        }
        recaption();
        // 必应换了市场会重新下一批图（文件名带市场），当前这张若已不在新的一批里，就交叉淡入到新市场的最新一张。
        Backgrounds.refresh(ctx, source, false, () -> {
            if (closed || recaption()) return;
            displayedId = "";
            update(true);
        });
    }

    /** 当前这张按新语言重显标题；它已不在当前来源里时返回 false。 */
    private boolean recaption() {
        for (Backgrounds.Item i : Backgrounds.items(ctx, source)) {
            if (i.id.equals(displayedId)) {
                showCaption(i.caption, true);
                return true;
            }
        }
        return false;
    }


    /** 当前是否在播影片（此时程序坞底板直接透出影片，而不是叠一张静止的模糊帧）。 */
    boolean filmShowing() {
        return film != null && film.showing();
    }

    /** 影片当前画面，供面板玻璃使用；没有影片时为 null。 */
    Bitmap filmFrame(int w, int h) {
        return film == null ? null : film.frame(w, h);
    }

    /** 电视自己的屏保盖住了首页，或已经离开。 */
    void setAway(boolean away) {
        if (film != null) film.setAway(away);
    }

    /** 清空一个图片层并回收它的图（正在显示的那张除外）。 */
    private void release(int at) {
        layers[at].setImageDrawable(null);
        Bitmap b = held[at];
        held[at] = null;
        if (b != null && b != shown && b != held[1 - at]) b.recycle();
    }

    void releaseForMemoryPressure() {
        for (ImageView v : layers) v.animate().cancel();
        layers[0].setImageDrawable(null);
        layers[1].setImageDrawable(null);
        held[0] = held[1] = null;
        blurLayer.setImageDrawable(null);
        shown = null;
        displayedId = "";
    }

    /** 每分钟一次，以及每次回到首页时：间隔到了或选择变了，就换一张。 */
    void update() {
        update(false);
    }

    private void update(boolean now) {
        if (!active || closed || loading || filmSource()) return;
        Backgrounds.Item item = next(now);
        if (item == null || item.id.equals(displayedId)) return;
        loading = true;
        decoder.execute(() -> {
            Bitmap b = Backgrounds.decode(ctx, item, width, height, Bitmap.Config.ARGB_8888);
            Bitmap soft = b == null ? null : Blur.of(b, 240, 10, 1.15f);
            Bitmap sharp = b == null ? null : Blur.of(b, LiquidGlass.SOURCE_WIDTH, LiquidGlass.SOURCE_RADIUS, 1f);
            int tint = b == null ? 0 : Blur.tint(b, 0.55f, 0f, 1f, 0.22f);
            int vivid = b == null ? 0 : Blur.vivid(b);
            root.post(() -> {
                loading = false;
                if (closed || b == null) return;
                present(item, b, true, soft, sharp, tint, vivid);
            });
        });
    }

    /**
     * 现在该显示的图：「选择墙纸」里选中的那张（还在的话）；否则间隔到了（或 {@code advance}）时取这个来源的下一张；
     * 否则是上次显示的那张。
     */
    private Backgrounds.Item next(boolean advance) {
        List<Backgrounds.Item> items = Backgrounds.items(ctx, source);
        if (items.isEmpty()) {
            // 网络来源还什么都没下载到：先用内置照片，等有了再换。
            items = Backgrounds.items(ctx, HomePrefs.SOURCE_BUILTIN);
            if (items.isEmpty()) return null;
        }
        String pick = HomePrefs.of(ctx).getString(HomePrefs.BG_PICK, "");
        for (Backgrounds.Item i : items) if (i.id.equals(pick)) return i;
        String last = state.getString("id", "");
        int at = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(last)) at = i;
        long interval = HomePrefs.interval(ctx) * 60_000L;
        long changed = state.getLong("changed", 0);
        boolean due = advance || (interval > 0 && WallpaperRotation.due(changed, System.currentTimeMillis(), interval));
        if (at >= 0 && !due) return items.get(at);
        return items.get((at + 1) % items.size());
    }

    private void present(Backgrounds.Item item, Bitmap b, boolean fade) {
        Bitmap soft = Blur.of(b, 240, 10, 1.15f);
        Bitmap sharp = Blur.of(b, LiquidGlass.SOURCE_WIDTH, LiquidGlass.SOURCE_RADIUS, 1f);
        present(item, b, fade, soft, sharp, Blur.tint(b, 0.55f, 0f, 1f, 0.22f), Blur.vivid(b));
    }

    private void present(Backgrounds.Item item, Bitmap b, boolean fade, Bitmap soft, Bitmap sharp, int tint, int vivid) {
        shown = b;
        blurred = soft;
        clear = sharp;
        root.setBackground(null);
        // AweHome：上一次淡入还没结束就来了新图（换背景源、从屏保回来时常见）：先停掉它。否则两段淡入的结束回调
        // 会先后清空两个图片层，背景整个消失。被停掉的那张直接完全显示，新图从它上面淡入。
        for (ImageView v : layers) v.animate().cancel();
        int inAt = 1 - front, outAt = front;
        ImageView in = layers[inAt], out = layers[outAt];
        out.setAlpha(1f);
        Bitmap replaced = held[inAt];
        held[inAt] = b;
        in.setImageBitmap(b);
        if (replaced != null && replaced != b && replaced != held[outAt]) replaced.recycle();
        // 图片始终在最底层，在天空、环境光和图标之下。
        root.removeView(in);
        root.addView(in, 1, match());
        root.removeView(out);
        root.addView(out, 0, match());
        if (fade) {
            in.setAlpha(0f);
            // 只在淡入正常结束时运行（被下一张打断时不会运行），这时新图已完全盖住，旧图可以放掉。
            in.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> release(outAt)).start();
        } else {
            in.setAlpha(1f);
            release(outAt);
        }
        front = inAt;
        blurLayer.setImageBitmap(soft);
        displayedId = item.id;
        if (!item.id.equals(state.getString("id", ""))) {
            state.edit().putString("id", item.id).putLong("changed", System.currentTimeMillis()).apply();
            // 主题壁纸：和原来的 WallpaperPlayer 一样记下正在显示的那张，时钟屏保（clock_wallpaper）从这张接着放。
            if (item.id.startsWith(HomePrefs.SOURCE_THEME + ":") && item.file != null) {
                ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).edit()
                        .putString("image", item.file.getName()).putLong("changed", System.currentTimeMillis()).apply();
            }
        }
        showCaption(item.caption, fade);
        if (listener != null) listener.onBackdrop(soft, sharp, tint, vivid);
    }

    /** 当前屏幕内容的模糊副本，给面板的玻璃用；第一张图出来之前为 null。 */
    Bitmap blurred() {
        return blurred;
    }

    /** AweHome：当前画面的轻度模糊副本，液态玻璃用；还没有图片时为 null。 */
    Bitmap clear() {
        return clear;
    }

    /**
     * 焦点在程序坞下面的网格里时：背景是图片就变成磨砂，和视频里的首页一样；背景是航拍影片时照常播放、清晰显示，
     * 不盖磨砂层（用户的选择）。
     */
    void setGridMode(boolean on) {
        if (on == gridMode) return;
        gridMode = on;
        applyGridFrost();
        // 图片名称属于标题区，网格会把标题区藏起来。
        caption.animate().cancel();
        caption.animate().alpha(on || TextUtils.isEmpty(caption.getText()) ? 0f : 1f).setDuration(on ? 150 : 260).start();
    }

    /** 网格里要不要盖磨砂层：只有图片背景才盖（影片放不出来、退回图片时也盖）。 */
    private void applyGridFrost() {
        float to = gridMode && !filmSource() ? 1f : 0f;
        blurLayer.animate().alpha(to).setDuration(320).start();
        veil.animate().alpha(to).setDuration(320).start();
    }

    /** 「背景」页选了新的来源：马上切换。 */
    void reload() {
        source = HomePrefs.bgSource(ctx);
        filmFailed = false;
        applyGridFrost();
        if (film != null) film.stop();
        if (filmSource()) {
            // 叠在当前图片之上；影片第一帧出来之前，这张图片一直保留。
            if (active) film().play();
            return;
        }
        displayedId = "";
        Backgrounds.refresh(ctx, source, false, () -> update(true));
        boolean was = active;
        active = true;
        update(true);
        active = was;
    }

    private static String prefix(String source) {
        if (source.startsWith(HomePrefs.COLOR_PREFIX)) return source;
        return source + ":";
    }

    /** 右上角按钮下面的一行浅色小字，同 WallpaperPlayer 的图片名称。 */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(460 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(84 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    private void showCaption(String name, boolean fade) {
        caption.animate().cancel();
        if (TextUtils.isEmpty(name)) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (gridMode) {
            caption.setText(name);
            caption.setAlpha(0f);
            return;
        }
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** 在网格里时，图片名称在网格的磨砂层下面；回到程序坞时重新出现。 */
    View captionView() {
        return caption;
    }
}
