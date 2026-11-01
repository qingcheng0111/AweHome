package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AweHome：在偏好面板（「偏好」按钮）里做的选择。和 Launcher settings 存在同一个 {@link Config#SETTINGS_NAME}
 * 文件里，所以改动后首页的变更计数（{@link Config#SETTINGS_REVISION_KEY}）会像那些设置一样让首页重建；
 * {@link Config#load} 再把其中与布局有关的选择盖在 config.xml 和主题之上。
 */
final class HomePrefs {
    /** 背景从哪里来：SOURCE_* 之一，或者 "color:" 加上 {@link Backgrounds} 里某种颜色的键。 */
    static final String BG_SOURCE = "bg_source";
    static final String SOURCE_BUILTIN = "builtin", SOURCE_BING = "bing", SOURCE_HOT = "hot", SOURCE_FOLDER = "folder";
    /** 「主题壁纸」：主题自带的壁纸（{@link Backgrounds#themeItems}）。没选过背景且主题带壁纸时默认用它。 */
    static final String SOURCE_THEME = "theme";
    /** 「航拍视频」：在线播放 Apple TV 的航拍影片（{@link AerialFilm}）。 */
    static final String SOURCE_AERIAL = "aerial";
    /** 航拍影片的分类（{@link AerialFilm#CATEGORIES}），"" 为全部。 */
    static final String AERIAL_CATEGORY = "aerial_category";
    static final String COLOR_PREFIX = "color:";
    /** 「选择墙纸」里选中的那张（当前来源中某张图的 id），"" 表示在全部图片之间轮换。 */
    static final String BG_PICK = "bg_pick";
    /** 两次更换背景之间的分钟数；0 为从不更换。 */
    static final String BG_INTERVAL = "bg_interval";
    /** 热门影视的地区（{@link HotShows}）。 */
    static final String HOT_REGION = "hot_region";
    /** 在首页按返回键打开的屏保（{@link Screensavers}）：SAVER_* 之一。 */
    static final String SCREENSAVER = "screensaver";
    static final String SAVER_CLOCK = "clock", SAVER_PHOTO = "photo", SAVER_VIDEO = "video";
    /** 图片屏保用哪个分类的照片：SOURCE_THEME / SOURCE_BUILTIN / SOURCE_BING / SOURCE_HOT / SOURCE_FOLDER 之一，"" 为全部分类。 */
    static final String SAVER_PHOTO_SOURCE = "saver_photo_source";
    /** The panels' glass: GLASS_*. */
    static final String GLASS = "glass";
    static final String GLASS_FROSTED = "frosted", GLASS_LIQUID = "liquid", GLASS_LIQUID_PLAIN = "liquid_plain";
    /** 「液态玻璃 2」：照纯 CSS 液态玻璃模板做的清透玻璃（{@link ClearGlass}）。 */
    static final String GLASS_LIQUID2 = "liquid2";
    static final String KEY_SOUNDS = "key_sounds";
    /** true：程序坞下面露出第二行；false：首页只显示第一行。 */
    static final String SECOND_ROW = "second_row";
    static final String DOCK_BAND = "dock_band_on";
    static final String DOCK_TITLE = "dock_title_on";
    /** 偏好「程序坞常驻」：「开启」时为 true，程序坞不会隐藏；「关闭」（默认，false）时按上键可以隐藏程序坞。 */
    static final String DOCK_STAYS = "dock_stays";
    /** 与 Launcher settings 共用（"columns"）；面板里可选 4、5 或 6。 */
    static final String COLUMNS = "columns";
    /** 语言标签，"" 为跟随电视。 */
    static final String LOCALE = "ui_locale";
    static final String WEATHER = "weather_on";
    /** 每个 HDMI 端口（按硬件 id 或输入源 id）选的设备：{@link InputLooks} 里的键，或 "custom"。 */
    static final String INPUT_DEVICE_PREFIX = "input_device:";
    /** 每个 HDMI 端口：为它输入的自定义名称。 */
    static final String INPUT_NAME_PREFIX = "input_name:";

    static final int DEFAULT_INTERVAL = 1;

    private HomePrefs() {
    }

    static SharedPreferences of(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static String bgSource(Context ctx) {
        String chosen = of(ctx).getString(BG_SOURCE, null);
        if (chosen != null) return chosen;
        // 没在偏好里选过背景：主题带了壁纸就显示主题的（与换成 Backdrop 之前一样），否则用内置照片。
        return Config.load(ctx).wallpapers.isEmpty() ? SOURCE_BUILTIN : SOURCE_THEME;
    }

    static String glass(Context ctx) {
        return of(ctx).getString(GLASS, GLASS_LIQUID);
    }

    /** 「模糊效果」是否为磨砂（否则是液态）。 */
    static boolean frosted(Context ctx) {
        return GLASS_FROSTED.equals(glass(ctx));
    }

    /** 「模糊效果」是否为液态玻璃 2（清透玻璃）。 */
    static boolean clearGlass(Context ctx) {
        return GLASS_LIQUID2.equals(glass(ctx));
    }

    /** 面板文字是否加柔和阴影：液态玻璃和液态玻璃 2 的玻璃更透、更亮，加阴影才看得清；「普通字体」与磨砂不加。 */
    static boolean textShadow(String mode) {
        return GLASS_LIQUID.equals(mode) || GLASS_LIQUID2.equals(mode);
    }

    static boolean keySounds(Context ctx) {
        return of(ctx).getBoolean(KEY_SOUNDS, true);
    }

    static boolean dockStays(Context ctx) {
        return of(ctx).getBoolean(DOCK_STAYS, false);
    }

    static boolean weather(Context ctx) {
        return of(ctx).getBoolean(WEATHER, true);
    }

    static String screensaver(Context ctx) {
        String s = of(ctx).getString(SCREENSAVER, SAVER_PHOTO);
        // 旧版本还有 "system"（电视系统屏保）这个选项，现在一律按「图片」处理。
        return SAVER_CLOCK.equals(s) || SAVER_VIDEO.equals(s) ? s : SAVER_PHOTO;
    }

    static String saverPhotoSource(Context ctx) {
        return of(ctx).getString(SAVER_PHOTO_SOURCE, "");
    }

    static String aerialCategory(Context ctx) {
        return of(ctx).getString(AERIAL_CATEGORY, "");
    }

    static int interval(Context ctx) {
        return of(ctx).getInt(BG_INTERVAL, DEFAULT_INTERVAL);
    }

    static String hotRegion(Context ctx) {
        return of(ctx).getString(HOT_REGION, HotShows.REGION_CN);
    }

    /** 保存一项并推进变更计数，让首页按新值重建（马上，或回到首页时）。 */
    static void put(Context ctx, String key, Object value) {
        SharedPreferences prefs = of(ctx);
        SharedPreferences.Editor e = prefs.edit();
        if (value == null) e.remove(key);
        else if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) e.putInt(key, (Integer) value);
        else e.putString(key, String.valueOf(value));
        e.putLong(Config.SETTINGS_REVISION_KEY, prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
    }

    /** 保存一项但不重建首页：用于首页能原地生效的选择。 */
    static void putQuietly(Context ctx, String key, Object value) {
        SharedPreferences.Editor e = of(ctx).edit();
        if (value == null) e.remove(key);
        else if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) e.putInt(key, (Integer) value);
        else e.putString(key, String.valueOf(value));
        e.apply();
    }

    /** 首页的变更计数：悄悄保存之后重新读一次，首页就不会为它已经显示出来的改动再重建。 */
    static long revision(Context ctx) {
        return of(ctx).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }
}
