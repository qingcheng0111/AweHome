package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AweHome：「偏好」面板，挂在右上角按钮下面：十六行，每行打开一页选项，做法同视频。所有选择都在面板开着时
 * 原地生效，每行图标数、语言也一样，首页不重建、不闪屏。
 */
final class PrefsPanel {
    /** 选择变了时首页要做的事。 */
    interface Host {
        /** 一项首页原地生效的选择（背景、玻璃、程序坞、天气……）。 */
        void onPrefChanged(String key);

        /** 面板背后模糊的屏幕，给它的玻璃用。 */
        Bitmap glassBackdrop();

        void openTvSettings(Intent intent);
    }

    private static final String ROOT = "root", BACKGROUND = "background", HOT_REGION = "hot_region", PICK = "pick",
            INTERVAL = "interval", SAVER = "saver", PHONE = "phone", BLUR = "blur", SOUNDS = "sounds", LAYOUT = "layout",
            DOCK_BG = "dock_bg", DOCK_TITLE = "dock_title", DOCK_STAYS = "dock_stays", COLUMNS = "columns", LANGUAGE = "language", WEATHER = "weather",
            CITY = "city", BOOT = "boot", INPUTS = "inputs", INPUT = "input:", SAVER_PHOTOS = "saver_photos";
    private static final int[] INTERVALS = {1, 5, 15, 30, 60, 1440, 0};
    private static final String[][] LANGUAGES = {{"zh-CN", "简体中文"}, {"zh-TW", "繁體中文"}, {"en", "English"},
            {"ja", "日本語"}, {"ko", "한국어"}, {"es", "Español"}};

    private final Activity activity;
    private final Host host;
    private final View anchor;
    private final float dp;
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    private PanelUi ui;
    private PanelWindow popup;
    private GlassPanel glass;
    private TextView header;
    private ScrollView scroll;
    private LinearLayout column;
    /** 打开着的各页，根页面在前，以及打开下一页时每页上选中的那一行。 */
    private final List<String> stack = new ArrayList<>();
    private final List<Integer> focusStack = new ArrayList<>();

    PrefsPanel(Activity activity, Host host, View anchor) {
        this.activity = activity;
        this.host = host;
        this.anchor = anchor;
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    void dismiss() {
        if (popup != null) popup.dismiss();
    }

    PanelWindow window() {
        return popup;
    }

    /** 打开根页面，或者打开首页重建前开着的那几页。 */
    void show(List<String> pages, int focus, boolean animate) {
        String mode = HomePrefs.glass(activity);
        ui = new PanelUi(activity, HomePrefs.textShadow(mode));
        glass = new GlassPanel(activity);
        glass.setBackdrop(host.glassBackdrop(), !HomePrefs.GLASS_FROSTED.equals(mode));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        header = ui.header("");
        box.addView(header);
        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setFillViewport(false);
        column = ui.column();
        scroll.addView(column);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        glass.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int width = ui.px(PanelUi.PANEL_WIDTH_DP);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int top = at[1] + anchor.getHeight() + ui.px(11);
        int screenH = activity.getWindow().getDecorView().getHeight();
        int height = screenH - top - ui.px(14);
        popup = new PanelWindow(activity);
        popup.setKeyHandler(this::onKey);
        popup.setOnDismiss(() -> {
            Updater.setListener(null);
            if (onClosed != null) onClosed.run();
        });
        stack.clear();
        focusStack.clear();
        if (pages == null || pages.isEmpty()) pages = Collections.singletonList(ROOT);
        for (int i = 0; i < pages.size(); i++) {
            stack.add(pages.get(i));
            if (i < pages.size() - 1) focusStack.add(0);
        }
        build(focus);
        popup.show(glass, Gravity.TOP | Gravity.END, ui.px(16), top, width, height);
        glass.setPivotY(0);
        if (animate) {
            glass.setAlpha(0f);
            glass.setScaleY(0.94f);
            glass.setTranslationY(-8 * dp);
            glass.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
        glass.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
            if (n != null) KeySounds.focusMoved();
        });
    }

    /** 面板后面的首页变了（换了背景、程序坞底板）：玻璃重新显示它的模糊画面。 */
    void refreshGlass() {
        if (!isShowing() || glass == null) return;
        glass.setBackdrop(host.glassBackdrop(), !HomePrefs.frosted(activity));
    }

    /** 重新显示当前页，用于面板以外的变化（手机传来一张照片）。 */
    void refreshPage() {
        if (isShowing()) refresh();
    }

    private Runnable onClosed;

    /** 面板关闭时运行，不管是怎么关的。 */
    void setOnClosed(Runnable r) {
        onClosed = r;
    }

    /**
     * 返回键回到上一页（在根页面上关闭面板）；左右键不动；上下键停在第一行和最后一行，不离开面板。
     */
    private boolean onKey(KeyEvent event) {
        KeySounds.onKey(event);
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (!down && !event.isCanceled()) back();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) KeySounds.limit();
            return true;
        }
        if (down && (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP)) {
            List<View> all = focusables();
            if (all.isEmpty()) return true;
            View edge = all.get(code == KeyEvent.KEYCODE_DPAD_DOWN ? all.size() - 1 : 0);
            if (edge.hasFocus() || edge.isFocused()) {
                KeySounds.limit();
                return true;
            }
        }
        return false;
    }

    /** 重建当前页（它的各行），焦点放在第 {@code focus} 行。 */
    private void build(int focus) {
        column.removeAllViews();
        String page = stack.get(stack.size() - 1);
        header.setText(title(page));
        fill(page);
        View target = focusable(focus);
        if (target != null) {
            target.requestFocus();
            if (focus == 0) scroll.scrollTo(0, 0);
        }
    }

    private List<View> focusables() {
        List<View> out = new ArrayList<>();
        collect(column, out);
        return out;
    }

    private static void collect(View v, List<View> out) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) {
            out.add(v);
            return;
        }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
    }

    private View focusable(int index) {
        List<View> all = focusables();
        if (all.isEmpty()) return null;
        View last = all.get(all.size() - 1);
        // 到最后一行时，把它下面的品牌行也一起滚进来，同视频里的面板。
        last.setOnFocusChangeListener((v, focused) -> {
            if (focused) scroll.post(() -> scroll.smoothScrollTo(0, column.getHeight()));
        });
        return all.get(Math.max(0, Math.min(index, all.size() - 1)));
    }

    private int focusedIndex() {
        List<View> all = focusables();
        for (int i = 0; i < all.size(); i++) if (all.get(i).hasFocus() || all.get(i).isFocused()) return i;
        return 0;
    }

    private void open(String page) {
        focusStack.add(focusedIndex());
        stack.add(page);
        swap(0);
    }

    private void back() {
        if (stack.size() <= 1) {
            dismiss();
            return;
        }
        stack.remove(stack.size() - 1);
        int focus = focusStack.isEmpty() ? 0 : focusStack.remove(focusStack.size() - 1);
        swap(focus);
    }

    /** 页面之间快速的交叉淡入。 */
    private void swap(int focus) {
        column.animate().cancel();
        column.setAlpha(0.2f);
        build(focus);
        header.setAlpha(0.2f);
        column.animate().alpha(1f).setDuration(160).start();
        header.animate().alpha(1f).setDuration(160).start();
    }

    /** 做了选择之后重新显示当前页，焦点留在原处。 */
    private void refresh() {
        build(focusedIndex());
        // AweHome：重建的行直接是聚焦后的样子，不再让白底从透明淡入（否则每选一项，这一行都会闪一下）。
        column.jumpDrawablesToCurrentState();
    }

    private void changed(String key) {
        host.onPrefChanged(key);
    }

    // ---- pages ----

    private CharSequence title(String page) {
        if (page.startsWith(INPUT)) return "HDMI " + page.substring(INPUT.length()).replace("hdmi", "");
        switch (page) {
            case BACKGROUND: return s(R.string.pref_background);
            case HOT_REGION: return s(R.string.bg_hot);
            case PICK: return s(R.string.pref_pick);
            case INTERVAL: return s(R.string.pref_interval);
            case SAVER: return s(R.string.pref_saver);
            case SAVER_PHOTOS: return s(R.string.pref_saver_photo);
            case PHONE: return s(R.string.pref_phone);
            case BLUR: return s(R.string.pref_blur);
            case SOUNDS: return s(R.string.pref_sounds);
            case LAYOUT: return s(R.string.pref_layout);
            case DOCK_BG: return s(R.string.pref_dock_bg);
            case DOCK_TITLE: return s(R.string.pref_dock_title);
            case DOCK_STAYS: return s(R.string.pref_dock_stays);
            case COLUMNS: return s(R.string.pref_columns);
            case LANGUAGE: return s(R.string.pref_language);
            case WEATHER: return s(R.string.pref_weather);
            case CITY: return s(R.string.pref_weather_city);
            case BOOT: return s(R.string.pref_boot);
            case INPUTS: return s(R.string.pref_inputs);
            default: return s(R.string.pref_title);
        }
    }

    private void fill(String page) {
        if (page.startsWith(INPUT)) {
            inputPage(page.substring(INPUT.length()));
            return;
        }
        switch (page) {
            case BACKGROUND: backgroundPage(); break;
            case HOT_REGION: hotRegionPage(); break;
            case PICK: pickPage(); break;
            case INTERVAL: intervalPage(); break;
            case SAVER: saverPage(); break;
            case SAVER_PHOTOS: saverPhotosPage(); break;
            case PHONE: phonePage(); break;
            case BLUR: blurPage(); break;
            case SOUNDS: twoChoices(HomePrefs.KEY_SOUNDS, HomePrefs.keySounds(activity), R.string.pref_sounds_on, R.string.pref_sounds_off, true); break;
            case LAYOUT: twoChoices(HomePrefs.SECOND_ROW, secondRow(), R.string.pref_layout_two, R.string.pref_layout_one, true); break;
            case DOCK_BG: twoChoices(HomePrefs.DOCK_BAND, dockBand(), R.string.pref_dock_bg_on, R.string.pref_dock_bg_off, true); break;
            case DOCK_TITLE: twoChoices(HomePrefs.DOCK_TITLE, dockTitle(), R.string.pref_dock_title_off, R.string.pref_dock_title_on, false); break;
            case DOCK_STAYS: twoChoices(HomePrefs.DOCK_STAYS, HomePrefs.dockStays(activity), R.string.pref_dock_stays_on, R.string.pref_dock_stays_off, true); break;
            case COLUMNS: columnsPage(); break;
            case LANGUAGE: languagePage(); break;
            case WEATHER: weatherPage(); break;
            case CITY: cityPage(); break;
            case BOOT: bootPage(); break;
            case INPUTS: inputsPage(); break;
            default: rootPage(); break;
        }
    }

    private void rootPage() {
        add(ui.navRow(R.drawable.ic_p_image, s(R.string.pref_background), sourceLabel(HomePrefs.bgSource(activity)), true, () -> open(BACKGROUND)));
        add(ui.navRow(R.drawable.ic_p_aperture, s(R.string.pref_pick), pickLabel(), true, () -> open(PICK)));
        add(ui.navRow(R.drawable.ic_p_clock, s(R.string.pref_interval), intervalLabel(HomePrefs.interval(activity)), true, () -> open(INTERVAL)));
        add(ui.navRow(R.drawable.ic_p_moon, s(R.string.pref_saver), saverLabel(HomePrefs.screensaver(activity)), true, () -> open(SAVER)));
        add(ui.navRow(R.drawable.ic_p_phone, s(R.string.pref_phone), s(R.string.pref_phone_value), true, () -> open(PHONE)));
        add(ui.navRow(R.drawable.ic_p_drop, s(R.string.pref_blur), blurLabel(HomePrefs.glass(activity)), true, () -> open(BLUR)));
        add(ui.navRow(R.drawable.ic_p_wave, s(R.string.pref_sounds), s(HomePrefs.keySounds(activity) ? R.string.pref_sounds_on : R.string.pref_sounds_off), true, () -> open(SOUNDS)));
        add(ui.navRow(R.drawable.ic_p_layout, s(R.string.pref_layout), s(secondRow() ? R.string.pref_layout_two : R.string.pref_layout_one), true, () -> open(LAYOUT)));
        add(ui.navRow(R.drawable.ic_p_dock, s(R.string.pref_dock_bg), s(dockBand() ? R.string.pref_dock_bg_on : R.string.pref_dock_bg_off), true, () -> open(DOCK_BG)));
        add(ui.navRow(R.drawable.ic_p_title, s(R.string.pref_dock_title), s(dockTitle() ? R.string.pref_dock_title_on : R.string.pref_dock_title_off), true, () -> open(DOCK_TITLE)));
        add(ui.navRow(R.drawable.ic_p_dock_hide, s(R.string.pref_dock_stays),
                s(HomePrefs.dockStays(activity) ? R.string.pref_dock_stays_on : R.string.pref_dock_stays_off), true, () -> open(DOCK_STAYS)));
        add(ui.navRow(R.drawable.ic_p_grid, s(R.string.pref_columns), activity.getString(R.string.pref_columns_value, MainActivity.COLUMNS), true, () -> open(COLUMNS)));
        add(ui.navRow(R.drawable.ic_p_globe, s(R.string.pref_language), languageLabel(), true, () -> open(LANGUAGE)));
        add(ui.navRow(R.drawable.ic_p_weather, s(R.string.pref_weather), s(HomePrefs.weather(activity) ? R.string.pref_weather_on : R.string.pref_weather_off), true, () -> open(WEATHER)));
        add(ui.navRow(R.drawable.ic_p_bolt, s(R.string.pref_boot), bootLabel(), true, () -> open(BOOT)));
        add(ui.navRow(R.drawable.ic_p_input, s(R.string.pref_inputs), activity.getString(R.string.pref_inputs_value, InputLooks.namedCount(activity)), true, () -> open(INPUTS)));
        LinearLayout update = ui.navRow(R.drawable.ic_p_download, s(R.string.pref_update), Updater.status(activity), false, null);
        update.setOnClickListener(v -> Updater.check(activity, Updater.state() == Updater.State.AVAILABLE
                || Updater.state() == Updater.State.NEEDS_PERMISSION));
        okOnRelease(update, () -> Updater.check(activity, Updater.state() == Updater.State.AVAILABLE
                || Updater.state() == Updater.State.NEEDS_PERMISSION));
        updateRow = update;
        add(update);
        column.addView(ui.divider());
        column.addView(ui.footer(s(R.string.brand_name), s(R.string.brand_tagline),
                activity.getString(R.string.pref_version, Updater.versionName(activity))));
        Updater.setListener((state, detail) -> {
            if (updateRow != null) PanelUi.setValue(updateRow, Updater.status(activity));
        });
        if (Updater.state() == Updater.State.IDLE) Updater.check(activity, false);
    }

    private View updateRow;

    private void backgroundPage() {
        String current = HomePrefs.bgSource(activity);
        // 主题带了壁纸（或当前正用着）时才列出「主题壁纸」。
        if (HomePrefs.SOURCE_THEME.equals(current) || !Config.load(activity).wallpapers.isEmpty()) {
            add(iconRadio(R.drawable.ic_p_palette, s(R.string.bg_theme), null, HomePrefs.SOURCE_THEME.equals(current),
                    () -> setSource(HomePrefs.SOURCE_THEME)));
        }
        add(iconRadio(R.drawable.ic_camera, s(R.string.bg_builtin), null, HomePrefs.SOURCE_BUILTIN.equals(current), () -> setSource(HomePrefs.SOURCE_BUILTIN)));
        add(iconRadio(R.drawable.ic_globe_photo, s(R.string.bg_bing), null, HomePrefs.SOURCE_BING.equals(current), () -> setSource(HomePrefs.SOURCE_BING)));
        add(iconRadio(R.drawable.ic_tv, s(R.string.bg_hot), s(HotShows.label(HomePrefs.hotRegion(activity))), HomePrefs.SOURCE_HOT.equals(current), () -> {
            // 热门影视已经是当前来源时再按确认：选择地区。
            if (HomePrefs.SOURCE_HOT.equals(HomePrefs.bgSource(activity))) open(HOT_REGION);
            else setSource(HomePrefs.SOURCE_HOT);
        }));
        add(iconRadio(R.drawable.ic_p_image, s(R.string.bg_folder), null, HomePrefs.SOURCE_FOLDER.equals(current), () -> setSource(HomePrefs.SOURCE_FOLDER)));
        // AweHome：Aerial 的航拍影片；已经选中时再按确认，进入分类选择。
        add(iconRadio(R.drawable.ic_film, s(R.string.bg_aerial), aerialLabel(HomePrefs.aerialCategory(activity)),
                HomePrefs.SOURCE_AERIAL.equals(current), () -> {
                    if (HomePrefs.SOURCE_AERIAL.equals(HomePrefs.bgSource(activity))) open(PICK);
                    else setSource(HomePrefs.SOURCE_AERIAL);
                }));
        for (Backgrounds.Preset p : Backgrounds.PRESETS) {
            String key = HomePrefs.COLOR_PREFIX + p.key;
            add(ui.colorRow(p.middle, s(p.name), key.equals(current), () -> setSource(key)));
        }
    }

    private LinearLayout iconRadio(int icon, CharSequence title, CharSequence detail, boolean checked, Runnable action) {
        LinearLayout row = ui.radioRow(title, detail, checked, action);
        ImageView i = new ImageView(activity);
        i.setImageResource(icon);
        i.setDuplicateParentStateEnabled(true);
        i.setImageTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF1C1C1E, 0xE6FFFFFF}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(20), ui.px(20));
        lp.setMarginEnd(ui.px(14));
        row.addView(i, 0, lp);
        return row;
    }

    private void setSource(String source) {
        HomePrefs.putQuietly(activity, HomePrefs.BG_SOURCE, source);
        HomePrefs.putQuietly(activity, HomePrefs.BG_PICK, "");
        changed(HomePrefs.BG_SOURCE);
        refresh();
    }

    private void hotRegionPage() {
        String current = HomePrefs.hotRegion(activity);
        for (String r : HotShows.REGIONS) {
            add(ui.radioRow(s(HotShows.label(r)), null, r.equals(current), () -> {
                HomePrefs.putQuietly(activity, HomePrefs.HOT_REGION, r);
                HomePrefs.putQuietly(activity, HomePrefs.BG_PICK, "");
                changed(HomePrefs.BG_SOURCE);
                refresh();
            }));
        }
    }

    private void pickPage() {
        if (HomePrefs.SOURCE_AERIAL.equals(HomePrefs.bgSource(activity))) {
            aerialPage();
            return;
        }
        String pick = HomePrefs.of(activity).getString(HomePrefs.BG_PICK, "");
        add(ui.radioRow(s(R.string.pref_pick_rotate), intervalLabel(HomePrefs.interval(activity)), pick.isEmpty(), () -> setPick("")));
        List<Backgrounds.Item> items = Backgrounds.items(activity, HomePrefs.bgSource(activity));
        int n = 1;
        for (Backgrounds.Item item : items) {
            String name = item.caption != null && !item.caption.isEmpty() ? item.caption
                    : activity.getString(R.string.pref_pick_photo, n);
            n++;
            LinearLayout row = ui.radioRow(name, null, item.id.equals(pick), () -> setPick(item.id));
            ImageView thumb = new ImageView(activity);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setClipToOutline(true);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(6 * dp);
            shape.setColor(0x33FFFFFF);
            thumb.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(64), ui.px(36));
            lp.setMarginEnd(ui.px(12));
            row.addView(thumb, 0, lp);
            loadThumb(item, thumb);
            add(row);
        }
    }

    /** 航拍视频的「选择墙纸」：选择轮流播放哪一类影片。 */
    private void aerialPage() {
        String current = HomePrefs.aerialCategory(activity);
        for (String c : AerialFilm.CATEGORIES) {
            int n = AerialFilm.of(activity, c).size();
            add(ui.radioRow(aerialLabel(c), activity.getResources().getQuantityString(R.plurals.aerial_count, n, n), c.equals(current), () -> {
                HomePrefs.putQuietly(activity, HomePrefs.AERIAL_CATEGORY, c);
                changed(HomePrefs.AERIAL_CATEGORY);
                refresh();
            }));
        }
        column.addView(ui.note(s(R.string.bg_aerial), s(R.string.aerial_note)));
    }

    private String aerialLabel(String category) {
        switch (category) {
            case "landscapes": return s(R.string.aerial_landscapes);
            case "cities": return s(R.string.aerial_cities);
            case "space": return s(R.string.aerial_space);
            case "underwater": return s(R.string.aerial_underwater);
            default: return s(R.string.aerial_all);
        }
    }

    private void loadThumb(Backgrounds.Item item, ImageView into) {
        thumbs.execute(() -> {
            Bitmap b = Backgrounds.decode(activity, item, 256, 144, Bitmap.Config.RGB_565);
            into.post(() -> into.setImageBitmap(b));
        });
    }

    private void setPick(String id) {
        HomePrefs.putQuietly(activity, HomePrefs.BG_PICK, id);
        changed(HomePrefs.BG_PICK);
        refresh();
    }

    private void intervalPage() {
        int current = HomePrefs.interval(activity);
        for (int minutes : INTERVALS) {
            add(ui.radioRow(intervalLabel(minutes), null, minutes == current, () -> {
                HomePrefs.putQuietly(activity, HomePrefs.BG_INTERVAL, minutes);
                changed(HomePrefs.BG_INTERVAL);
                refresh();
            }));
        }
    }

    private void saverPage() {
        String current = HomePrefs.screensaver(activity);
        String[][] choices = {{HomePrefs.SAVER_CLOCK, s(R.string.pref_saver_clock), s(R.string.pref_saver_clock_hint)},
                {HomePrefs.SAVER_PHOTO, s(R.string.pref_saver_photo), s(R.string.pref_saver_photo_hint)},
                {HomePrefs.SAVER_VIDEO, s(R.string.pref_saver_video), s(R.string.pref_saver_video_hint)}};
        for (String[] c : choices) {
            boolean photo = HomePrefs.SAVER_PHOTO.equals(c[0]);
            // 选中「图片」时副标题显示所选分类；选「图片」直接进入下一级，选择用哪个分类的照片。
            CharSequence detail = photo && c[0].equals(current) ? photoSourceLabel(HomePrefs.saverPhotoSource(activity)) : c[2];
            add(ui.radioRow(c[1], detail, c[0].equals(current), () -> {
                HomePrefs.putQuietly(activity, HomePrefs.SCREENSAVER, c[0]);
                if (photo) open(SAVER_PHOTOS);
                else refresh();
            }));
        }
    }

    /** 「屏幕保护 → 图片」的下一级：用哪个分类的照片，每项后面是照片数量。 */
    private void saverPhotosPage() {
        String current = HomePrefs.saverPhotoSource(activity);
        for (String source : PhotoScene.SOURCES) {
            int n = PhotoScene.items(activity, source).size();
            // 主题没有壁纸时不列出这一项。
            if (n == 0 && HomePrefs.SOURCE_THEME.equals(source) && !source.equals(current)) continue;
            add(ui.radioRow(photoSourceLabel(source), activity.getResources().getQuantityString(R.plurals.pref_pick_count, n, n),
                    source.equals(current), () -> {
                        HomePrefs.putQuietly(activity, HomePrefs.SAVER_PHOTO_SOURCE, source);
                        refresh();
                    }));
        }
    }

    private String photoSourceLabel(String source) {
        return source.isEmpty() ? s(R.string.saver_photo_all) : sourceLabel(source);
    }

    private void phonePage() {
        String address = PhoneServer.address(activity);
        if (address != null) {
            Bitmap code = PhoneServer.qr(address, 8);
            ImageView qr = new ImageView(activity);
            qr.setImageBitmap(code);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout card = ui.card(qr, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(196));
            lp.setMargins(ui.px(10), ui.px(4), ui.px(10), ui.px(10));
            card.setFocusable(true);
            card.setLayoutParams(lp);
            add(card);
            column.addView(ui.note(s(R.string.pref_phone_scan), s(R.string.pref_phone_same_network)));
            TextView url = ui.text(address, 11, 0x80FFFFFF, false);
            url.setPadding(ui.px(18), 0, ui.px(18), ui.px(6));
            column.addView(url);
        } else {
            column.addView(ui.note(s(R.string.pref_phone_scan), s(R.string.pref_phone_offline)));
        }
        column.addView(ui.divider());
        column.addView(ui.note(s(R.string.bg_folder), s(R.string.pref_phone_delete_hint)));
        List<File> files = new ArrayList<>();
        for (File f : Backgrounds.folderFiles(activity)) if (Backgrounds.uploadsDir(activity).equals(f.getParentFile())) files.add(f);
        if (files.isEmpty()) {
            TextView none = ui.text(s(R.string.pref_phone_none), 12, 0x80FFFFFF, false);
            none.setPadding(ui.px(18), ui.px(4), ui.px(18), ui.px(12));
            column.addView(none);
        }
        for (File f : files) add(photoCard(f));
    }

    /** 「手机传图」上一张传来的图片：按确认删除，同视频（「点击图片就能删除」）。 */
    private View photoCard(File f) {
        FrameLayout card = new FrameLayout(activity);
        ImageView img = new ImageView(activity);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setClipToOutline(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(12 * dp);
        shape.setColor(0x33FFFFFF);
        img.setBackground(shape);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView trash = new ImageView(activity);
        trash.setImageResource(R.drawable.ic_trash);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0x99000000);
        trash.setBackground(dot);
        int pad = ui.px(4);
        trash.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ui.px(24), ui.px(24), Gravity.TOP | Gravity.END);
        tl.setMargins(0, ui.px(8), ui.px(8), 0);
        card.addView(trash, tl);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(12 * dp);
        ring.setStroke(ui.px(3), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        android.graphics.drawable.StateListDrawable focus = new android.graphics.drawable.StateListDrawable();
        focus.addState(new int[]{android.R.attr.state_focused}, ring);
        card.setForeground(focus);
        card.setFocusable(true);
        card.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(118));
        lp.setMargins(ui.px(10), ui.px(6), ui.px(10), ui.px(6));
        card.setLayoutParams(lp);
        Runnable delete = () -> {
            if (Backgrounds.deleteUpload(activity, f.getName())) {
                PanelUi.toast(activity, s(R.string.pref_phone_deleted));
                changed(HomePrefs.BG_PICK);
                refresh();
            }
        };
        card.setOnClickListener(v -> delete.run());
        okOnRelease(card, delete);
        thumbs.execute(() -> {
            Bitmap b = Backgrounds.decode(activity, new Backgrounds.Item(f.getName(), f, null, null, null), 420, 236, Bitmap.Config.RGB_565);
            img.post(() -> img.setImageBitmap(b));
        });
        return card;
    }

    private void blurPage() {
        String current = HomePrefs.glass(activity);
        String[][] modes = {{HomePrefs.GLASS_FROSTED, s(R.string.pref_blur_frosted), s(R.string.pref_blur_frosted_hint)},
                {HomePrefs.GLASS_LIQUID, s(R.string.pref_blur_liquid), s(R.string.pref_blur_liquid_hint)},
                {HomePrefs.GLASS_LIQUID_PLAIN, s(R.string.pref_blur_plain), s(R.string.pref_blur_plain_hint)},
                {HomePrefs.GLASS_LIQUID2, s(R.string.pref_blur_liquid2), s(R.string.pref_blur_liquid2_hint)}};
        for (String[] m : modes) {
            add(ui.radioRow(m[1], m[2], m[0].equals(current), () -> {
                HomePrefs.putQuietly(activity, HomePrefs.GLASS, m[0]);
                changed(HomePrefs.GLASS);
                ui = new PanelUi(activity, HomePrefs.textShadow(m[0]));
                glass.setBackdrop(host.glassBackdrop(), !HomePrefs.GLASS_FROSTED.equals(m[0]));
                String h = header.getText().toString();
                int at = ((LinearLayout) header.getParent()).indexOfChild(header);
                LinearLayout box = (LinearLayout) header.getParent();
                box.removeView(header);
                header = ui.header(h);
                box.addView(header, at);
                refresh();
            }));
        }
    }

    /** 开关类偏好的两选项页面：{@code first} 是代表 {@code firstValue} 的那个选项。 */
    private void twoChoices(String key, boolean value, int first, int second, boolean firstValue) {
        add(ui.radioRow(s(first), null, value == firstValue, () -> setBool(key, firstValue)));
        add(ui.radioRow(s(second), null, value != firstValue, () -> setBool(key, !firstValue)));
    }

    private void setBool(String key, boolean on) {
        HomePrefs.putQuietly(activity, key, on);
        changed(key);
        refresh();
    }

    private void columnsPage() {
        for (int n : new int[]{6, 5, 4}) {
            add(ui.radioRow(activity.getString(R.string.pref_columns_value, n), null, n == MainActivity.COLUMNS, () -> {
                if (n == MainActivity.COLUMNS) return;
                // AweHome：首页原地按新列数重排（MainActivity.applyColumns），不再重建。
                HomePrefs.putQuietly(activity, HomePrefs.COLUMNS, n);
                changed(HomePrefs.COLUMNS);
                refresh();
            }));
        }
    }

    private void languagePage() {
        String current = currentLanguage();
        for (String[] l : LANGUAGES) {
            add(ui.radioRow(l[1], null, l[0].equals(current), () -> {
                if (l[0].equals(currentLanguage())) return;
                // AweHome：首页原地换语言（MainActivity.applyLocale），不再重建；面板随后用新语言重画这一页。
                HomePrefs.putQuietly(activity, HomePrefs.LOCALE, l[0]);
                changed(HomePrefs.LOCALE);
                refresh();
            }));
        }
    }

    private void weatherPage() {
        boolean on = HomePrefs.weather(activity);
        add(ui.radioRow(s(R.string.pref_weather_off), null, !on, () -> setBool(HomePrefs.WEATHER, false)));
        add(ui.radioRow(s(R.string.pref_weather_on), null, on, () -> setBool(HomePrefs.WEATHER, true)));
        String place = Weather.place(activity);
        add(ui.navRow(R.drawable.ic_search, s(R.string.pref_weather_city),
                Weather.chosen(activity) && place != null ? place : s(R.string.pref_weather_auto), true, () -> open(CITY)));
    }

    private void cityPage() {
        boolean chosen = Weather.chosen(activity);
        String place = Weather.place(activity);
        add(ui.radioRow(s(R.string.pref_weather_auto), chosen || place == null ? null : place, !chosen, () -> {
            Weather.useNetworkLocation(activity);
            refresh();
        }));
        add(ui.radioRow(s(R.string.pref_weather_type), chosen ? place : null, chosen, () ->
                NameDialog.show(activity, host.glassBackdrop(), s(R.string.pref_weather_type), chosen ? place : "", typed -> {
                    new Thread(() -> {
                        boolean found = Weather.choose(activity, typed);
                        activity.runOnUiThread(() -> {
                            PanelUi.toast(activity, s(found ? R.string.pref_weather_found : R.string.pref_weather_not_found));
                            if (isShowing()) refresh();
                        });
                    }).start();
                })));
        column.addView(ui.note(null, s(R.string.pref_weather_phone_hint)));
    }

    private void bootPage() {
        String current = HomePrefs.of(activity).getString(OpenOnStart.KEY, "");
        add(ui.radioRow(s(R.string.pref_none), null, current.isEmpty(), () -> setBoot("")));
        for (String[] app : launchableApps()) {
            add(ui.radioRow(app[1], null, app[0].equals(current), () -> setBoot(app[0])));
        }
    }

    private void setBoot(String pkg) {
        if (pkg.isEmpty()) {
            HomePrefs.of(activity).edit().remove(OpenOnStart.KEY).apply();
        } else {
            // 选择从下次开机起生效：选完之后的这次首页仍是首页。
            OpenOnStart.skipThisBoot(activity);
            HomePrefs.of(activity).edit().putString(OpenOnStart.KEY, pkg).apply();
        }
        refresh();
    }

    /** 电视上的应用，按名称排序，同首页显示的；加进应用库的手机应用排在后面。 */
    private List<String[]> launchableApps() {
        PackageManager pm = activity.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || !seen.add(pkg)) continue;
            out.add(new String[]{pkg, String.valueOf(ri.loadLabel(pm))});
        }
        for (String pkg : LibraryApps.load(activity)) {
            if (!seen.add(pkg)) continue;
            try {
                out.add(new String[]{pkg, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)))});
            } catch (PackageManager.NameNotFoundException ignored) {
                // gone
            }
        }
        return out;
    }

    private void inputsPage() {
        for (String[] port : InputLooks.hdmiPorts(activity)) {
            String name = InputLooks.name(activity, port[1]);
            add(ui.navRow(R.drawable.ic_p_input, "HDMI " + port[0], name == null ? s(R.string.pref_none) : name, true,
                    () -> open(INPUT + port[1])));
        }
        column.addView(ui.note(s(R.string.pref_inputs), s(R.string.pref_inputs_hint)));
    }

    private void inputPage(String portKey) {
        String device = InputLooks.device(activity, portKey);
        add(ui.radioRow(s(R.string.pref_none), null, device == null, () -> {
            InputLooks.setDevice(activity, portKey, null);
            refresh();
        }));
        for (String d : InputLooks.DEVICES) {
            add(ui.radioRow(InputLooks.deviceLabel(activity, d), null, d.equals(device), () -> {
                InputLooks.setDevice(activity, portKey, d);
                refresh();
            }));
        }
        String typed = InputLooks.CUSTOM.equals(device) ? InputLooks.name(activity, portKey) : null;
        add(ui.radioRow(s(R.string.input_custom), typed, InputLooks.CUSTOM.equals(device), () ->
                NameDialog.show(activity, host.glassBackdrop(), s(R.string.input_name_title),
                        typed != null ? typed : InputLooks.name(activity, portKey), name -> {
                            if (name.trim().isEmpty()) return;
                            InputLooks.setCustom(activity, portKey, name);
                            if (isShowing()) refresh();
                        })));
        column.addView(ui.note(title(INPUT + portKey), s(R.string.input_name_hint)));
    }

    // ---- values ----

    private boolean secondRow() {
        return HomePrefs.of(activity).getBoolean(HomePrefs.SECOND_ROW, true);
    }

    private boolean dockBand() {
        return HomePrefs.of(activity).getBoolean(HomePrefs.DOCK_BAND, activity.getResources().getBoolean(R.bool.dock_band));
    }

    private boolean dockTitle() {
        return HomePrefs.of(activity).getBoolean(HomePrefs.DOCK_TITLE, activity.getResources().getBoolean(R.bool.dock_title));
    }

    private String sourceLabel(String source) {
        if (source.startsWith(HomePrefs.COLOR_PREFIX)) {
            Backgrounds.Preset p = Backgrounds.preset(source.substring(HomePrefs.COLOR_PREFIX.length()));
            return p == null ? "" : s(p.name);
        }
        switch (source) {
            case HomePrefs.SOURCE_BING: return s(R.string.bg_bing);
            case HomePrefs.SOURCE_HOT: return s(R.string.bg_hot);
            case HomePrefs.SOURCE_FOLDER: return s(R.string.bg_folder);
            case HomePrefs.SOURCE_THEME: return s(R.string.bg_theme);
            case HomePrefs.SOURCE_AERIAL: return s(R.string.bg_aerial);
            default: return s(R.string.bg_builtin);
        }
    }

    private String pickLabel() {
        if (HomePrefs.SOURCE_AERIAL.equals(HomePrefs.bgSource(activity))) return aerialLabel(HomePrefs.aerialCategory(activity));
        int n = Backgrounds.items(activity, HomePrefs.bgSource(activity)).size();
        return activity.getResources().getQuantityString(R.plurals.pref_pick_count, n, n);
    }

    private String intervalLabel(int minutes) {
        switch (minutes) {
            case 0: return s(R.string.interval_never);
            case 1: return s(R.string.interval_minute);
            case 60: return s(R.string.interval_hour);
            case 1440: return s(R.string.interval_day);
            default: return activity.getString(R.string.interval_minutes, minutes);
        }
    }

    private String saverLabel(String choice) {
        if (HomePrefs.SAVER_CLOCK.equals(choice)) return s(R.string.pref_saver_clock);
        if (HomePrefs.SAVER_VIDEO.equals(choice)) return s(R.string.pref_saver_video);
        return s(R.string.pref_saver_photo);
    }

    private String blurLabel(String mode) {
        if (HomePrefs.GLASS_FROSTED.equals(mode)) return s(R.string.pref_blur_frosted);
        if (HomePrefs.GLASS_LIQUID_PLAIN.equals(mode)) return s(R.string.pref_blur_plain);
        if (HomePrefs.GLASS_LIQUID2.equals(mode)) return s(R.string.pref_blur_liquid2);
        return s(R.string.pref_blur_liquid);
    }

    private String currentLanguage() {
        String chosen = HomePrefs.of(activity).getString(HomePrefs.LOCALE, null);
        if (chosen != null && !chosen.isEmpty()) return chosen;
        java.util.Locale l = activity.getResources().getConfiguration().getLocales().get(0);
        if (l.getLanguage().equals("zh")) {
            String script = l.getScript(), country = l.getCountry();
            return "Hant".equals(script) || "TW".equals(country) || "HK".equals(country) ? "zh-TW" : "zh-CN";
        }
        return l.getLanguage();
    }

    private String languageLabel() {
        String current = currentLanguage();
        for (String[] l : LANGUAGES) if (l[0].equals(current)) return l[1];
        return activity.getResources().getConfiguration().getLocales().get(0).getDisplayLanguage();
    }

    private String bootLabel() {
        String pkg = HomePrefs.of(activity).getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return s(R.string.pref_none);
        try {
            PackageManager pm = activity.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return s(R.string.pref_none);
        }
    }

    private String s(int res) {
        return activity.getString(res);
    }

    private void add(View row) {
        column.addView(row);
    }

    /** 不是 PanelUi 行的视图（更新那一行、一张照片）上，确认键在松开时生效。 */
    private static void okOnRelease(View v, Runnable action) {
        final boolean[] pressed = {false};
        v.setOnKeyListener((view, code, event) -> {
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
}
