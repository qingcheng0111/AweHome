package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AweHome：首页背景的来源，即偏好面板里的「背景」页。
 * <ul>
 * <li>主题壁纸：主题自带的 wallpaper.jpg 或 wallpapers/ 合集（{@link #themeItems}）；</li>
 * <li>内置照片：assets/backdrops 里自带的七张照片（来自 Wikimedia Commons，署名显示在屏幕上，并写在 CREDITS.md）；</li>
 * <li>必应精选：必应的每日一图和前一周的图，取法同 Aerial（cn.bing.com）；</li>
 * <li>热门影视：正在热播的剧集海报（{@link HotShows}）；</li>
 * <li>墙纸文件夹：从手机传来的图片（{@link PhoneServer}）；</li>
 * <li>颜色：在电视上画的柔和渐变，如「午夜蓝」「海洋蓝」。</li>
 * </ul>
 * 下载都在一个后台线程上进行，存进应用的 files 目录，所以离线时首页仍显示最近下载的那些。
 */
final class Backgrounds {
    private static final String TAG = "TvHome.Backgrounds";
    private static final long BING_REFRESH_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_UPLOAD_SIDE = 2560;

    /** 一张图：一个文件、一个 asset 或一种颜色预设，以及显示在右上角按钮下面的那行文字。 */
    static final class Item {
        final String id;
        final File file;
        final String asset;
        final Preset preset;
        final String caption;
        /** 解码后盖上的一层黑色（ARGB），0 为不变暗：主题的 wallpaper_dim。 */
        final int dim;

        Item(String id, File file, String asset, Preset preset, String caption) {
            this(id, file, asset, preset, caption, 0);
        }

        Item(String id, File file, String asset, Preset preset, String caption, int dim) {
            this.id = id;
            this.file = file;
            this.asset = asset;
            this.preset = preset;
            this.caption = caption;
            this.dim = dim;
        }
    }

    /** 一种颜色背景：它的键、名称和渐变用的颜色（左上角的光晕、中间、底部）。 */
    static final class Preset {
        final String key;
        final int name;
        final int glow, middle, deep;

        Preset(String key, int name, int glow, int middle, int deep) {
            this.key = key;
            this.name = name;
            this.glow = glow;
            this.middle = middle;
            this.deep = deep;
        }
    }

    static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("midnight", R.string.bg_color_midnight, 0xFF2B4C9B, 0xFF14254F, 0xFF060B1C),
            new Preset("ocean", R.string.bg_color_ocean, 0xFF2AA3B6, 0xFF0F5A6E, 0xFF04222E),
            new Preset("dusk", R.string.bg_color_dusk, 0xFFB45AA8, 0xFF4A2370, 0xFF140A26),
            new Preset("forest", R.string.bg_color_forest, 0xFF5FA36E, 0xFF22573A, 0xFF07180F),
            new Preset("sunset", R.string.bg_color_sunset, 0xFFF29A55, 0xFF9C3B2E, 0xFF2A0D10),
            new Preset("graphite", R.string.bg_color_graphite, 0xFF5B5F6B, 0xFF2A2C33, 0xFF0B0B0D)));

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Backgrounds() {
    }

    static Preset preset(String key) {
        for (Preset p : PRESETS) if (p.key.equals(key)) return p;
        return null;
    }

    /** 某个来源目前在电视上有的图（之后的下载可能会再添一些）。 */
    static List<Item> items(Context ctx, String source) {
        List<Item> out = new ArrayList<>();
        boolean zh = Locale.getDefault().getLanguage().equals("zh");
        if (source.startsWith(HomePrefs.COLOR_PREFIX)) {
            Preset p = preset(source.substring(HomePrefs.COLOR_PREFIX.length()));
            if (p != null) out.add(new Item(source, null, null, p, null));
            return out;
        }
        switch (source) {
            case HomePrefs.SOURCE_BING:
                for (JSONObject o : index(new File(dir(ctx, "bing"), "index.json"))) {
                    File f = new File(dir(ctx, "bing"), o.optString("file"));
                    String title = o.optString("title", ""), caption = o.optString("caption", "");
                    String line = caption.isEmpty() ? title : title.isEmpty() ? caption : title + " · " + caption;
                    if (f.isFile()) out.add(new Item("bing:" + f.getName(), f, null, null, line));
                }
                break;
            case HomePrefs.SOURCE_HOT:
                File hot = HotShows.dir(ctx, HomePrefs.hotRegion(ctx));
                for (JSONObject o : index(new File(hot, "index.json"))) {
                    File f = new File(hot, o.optString("file"));
                    if (f.isFile()) out.add(new Item("hot:" + f.getName(), f, null, null, o.optString("title", "")));
                }
                break;
            case HomePrefs.SOURCE_FOLDER:
                for (File f : folderFiles(ctx)) out.add(new Item("folder:" + f.getName(), f, null, null, null));
                break;
            case HomePrefs.SOURCE_THEME:
                out.addAll(themeItems(ctx, Config.load(ctx)));
                break;
            default:
                try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("backdrops/captions.tsv"), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("#") || line.trim().isEmpty()) continue;
                        String[] c = line.split("\t");
                        if (c.length < 3) continue;
                        out.add(new Item("builtin:" + c[0], null, "backdrops/" + c[0], null, zh ? c[1] : c[2]));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "no built-in photos: " + e);
                }
                break;
        }
        return out;
    }

    /** 来源需要的网络内容过期或缺失时去下载，完成后在主线程上运行 {@code done}。 */
    static void refresh(Context context, String source, boolean force, Runnable done) {
        Context ctx = context.getApplicationContext();
        if (!source.equals(HomePrefs.SOURCE_BING) && !source.equals(HomePrefs.SOURCE_HOT)) return;
        String region = HomePrefs.hotRegion(ctx);
        IO.execute(() -> {
            boolean changed = false;
            try {
                if (source.equals(HomePrefs.SOURCE_BING)) changed = refreshBing(ctx, force);
                else changed = HotShows.refresh(ctx, region, force);
            } catch (Exception e) {
                Log.w(TAG, source + " unavailable: " + e);
            }
            if (changed && done != null) MAIN.post(done);
        });
    }

    // ---- 必应精选，仿照 Aerial 的 Wallpapers.refreshDaily ----

    private static boolean refreshBing(Context ctx, boolean force) throws Exception {
        File dir = dir(ctx, "bing");
        File index = new File(dir, "index.json");
        // 由 market 决定图片说明的语言：桌面是中文时用中文，否则用英文。
        String market = Locale.getDefault().getLanguage().equals("zh") ? "zh-CN" : "en-US";
        File marketFile = new File(dir, "market");
        String before = marketFile.isFile() ? read(marketFile) : "";
        if (!force && market.equals(before) && System.currentTimeMillis() - index.lastModified() < BING_REFRESH_MS && index.isFile()) return false;
        JSONObject json = new JSONObject(new String(get("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=" + market), StandardCharsets.UTF_8));
        JSONArray images = json.getJSONArray("images");
        JSONArray list = new JSONArray();
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < images.length(); i++) {
            JSONObject img = images.getJSONObject(i);
            String date = img.getString("startdate");
            String name = date + "-" + market + ".jpg";
            File f = new File(dir, name);
            if (!f.isFile()) write(f, get("https://cn.bing.com" + img.getString("url")));
            String copyright = img.optString("copyright", "");
            String title = img.optString("title", "");
            if (title.isEmpty()) {
                int paren = copyright.indexOf(" (");
                title = paren > 0 ? copyright.substring(0, paren) : copyright;
            }
            // 「沙丘鹤，博斯克德尔阿帕奇…（© 摄影师）」：地名，以及括号里的署名。
            list.put(new JSONObject().put("file", name).put("title", title).put("caption", copyright));
            keep.add(name);
        }
        write(index, list.toString().getBytes(StandardCharsets.UTF_8));
        write(marketFile, market.getBytes(StandardCharsets.UTF_8));
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files != null) for (File f : files) if (!keep.contains(f.getName())) f.delete();
        return true;
    }

    // ---- 主题壁纸 ----

    /**
     * 主题的壁纸（tools/theme.sh 推送的 wallpaper.jpg 或 wallpapers/ 合集，Launcher settings 里的壁纸选择优先），
     * 即原来 WallpaperPlayer 播放的那些：按 wallpaper_moods 只取当前时段的一组，带 captions.txt 里的名字和
     * wallpaper_dim 的变暗。主题没有壁纸或关掉了壁纸时为空。
     */
    static List<Item> themeItems(Context ctx, Config cfg) {
        List<Item> out = new ArrayList<>();
        List<File> files = cfg.wallpapers;
        if (files.isEmpty()) return out;
        if (!cfg.wallpaperMoods.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            int[] sun = WallpaperMood.sun(now.toLocalDate(), ctx.getString(R.string.latitude), ctx.getString(R.string.longitude),
                    ZoneId.systemDefault());
            files = WallpaperMood.select(files, cfg.wallpaperMoods,
                    WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
        }
        Map<String, String> names = cfg.wallpaperCaptions
                ? WallpaperCaptions.read(cfg.wallpapers.get(0).getParentFile()) : Collections.emptyMap();
        int dim = cfg.wallpaperDim > 0 ? (cfg.wallpaperDim * 255 / 100) << 24 : 0;
        for (File f : files) out.add(new Item("theme:" + f.getName(), f, null, null, names.get(f.getName()), dim));
        return out;
    }

    // ---- 墙纸文件夹 ----

    /** 手机传来的图片，新的在前（主题自己的合集归「主题壁纸」，见 {@link #themeItems}）。 */
    static List<File> folderFiles(Context ctx) {
        List<File> out = new ArrayList<>();
        File[] files = dir(ctx, "wallpapers").listFiles((d, n) -> ThemeFiles.collectionImage(n));
        if (files != null) out.addAll(Arrays.asList(files));
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /**
     * 保存一张从手机传来的图片：长边缩到不超过 2560 像素，存成 JPEG。返回保存后的文件；不是图片时返回 null。
     * 不在主线程上调用。
     */
    static File addUpload(Context ctx, File upload) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(upload.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_UPLOAD_SIDE) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(upload.getPath(), o);
            if (b == null) return null;
            int side = Math.max(b.getWidth(), b.getHeight());
            if (side > MAX_UPLOAD_SIDE) {
                float k = MAX_UPLOAD_SIDE / (float) side;
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
                if (s != b) b.recycle();
                b = s;
            }
            File out = new File(dir(ctx, "wallpapers"), "photo-" + System.currentTimeMillis() + ".jpg");
            try (OutputStream os = new FileOutputStream(out)) {
                b.compress(Bitmap.CompressFormat.JPEG, 88, os);
            }
            b.recycle();
            return out;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not keep an upload: " + e);
            return null;
        }
    }

    /** 删除一张从手机传来的图片（只删这些，从不删主题的）。 */
    static boolean deleteUpload(Context ctx, String name) {
        File dir = dir(ctx, "wallpapers");
        File f = new File(dir, name);
        return f.getParentFile() != null && f.getParentFile().equals(dir) && f.delete();
    }

    static File uploadsDir(Context ctx) {
        return dir(ctx, "wallpapers");
    }

    // ---- pictures ----

    /** 把这张图解码成约 {@code width} × {@code height} 像素（铺满这个尺寸），失败时返回 null。不在主线程上调用。 */
    static Bitmap decode(Context ctx, Item item, int width, int height, Bitmap.Config config) {
        try {
            if (item.preset != null) return gradient(item.preset, width, height);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            if (item.file != null) BitmapFactory.decodeFile(item.file.getPath(), bounds);
            else try (InputStream in = ctx.getAssets().open(item.asset)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = WallpaperSize.sampleSize(bounds.outWidth, bounds.outHeight, width, height);
            o.inPreferredConfig = config;
            // 变暗直接画进图里（同原来的 WallpaperPlayer），不必在整个屏幕上多盖一层。
            o.inMutable = item.dim != 0;
            Bitmap b;
            if (item.file != null) {
                b = BitmapFactory.decodeFile(item.file.getPath(), o);
            } else {
                try (InputStream in = ctx.getAssets().open(item.asset)) {
                    b = BitmapFactory.decodeStream(in, null, o);
                }
            }
            if (b != null && item.dim != 0) new Canvas(b).drawColor(item.dim);
            return b;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not decode " + item.id + ": " + e);
            return null;
        }
    }

    /** 颜色背景：由上到下的深色渐变，左上角有一团柔和的光晕，右边一团更淡的。 */
    static Bitmap gradient(Preset p, int w, int h) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new LinearGradient(0, 0, w * 0.35f, h, p.middle, p.deep, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * 0.22f, h * 0.12f, Math.max(w, h) * 0.62f,
                (p.glow & 0x00FFFFFF) | 0xB3000000, p.glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w * 0.88f, h * 0.78f, Math.max(w, h) * 0.45f,
                (p.glow & 0x00FFFFFF) | 0x40000000, p.glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        return b;
    }

    // ---- files ----

    static File dir(Context ctx, String name) {
        File d = new File(ctx.getFilesDir(), name);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static List<JSONObject> index(File file) {
        List<JSONObject> out = new ArrayList<>();
        if (!file.isFile()) return out;
        try {
            JSONArray a = new JSONArray(read(file));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "unreadable " + file + ": " + e);
        }
        return out;
    }

    static String read(File f) throws java.io.IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    static void write(File f, byte[] bytes) throws java.io.IOException {
        File tmp = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("could not save " + f);
    }

    static byte[] get(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) AweHome");
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
