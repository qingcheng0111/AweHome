package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * AweHome：「热门影视」，正在热播的剧集海报，同视频里的首页（魅影神捕、小城良方）。
 *
 * 爱奇艺的公开片单（pcw-api.iqiyi.com）不需要密钥、在国内不用代理就能列出热门剧集；每部的图片是 1920×1080 的 16:9，
 * 上面印着剧名，所以看起来像一面海报墙。地区按爱奇艺的分类 id 把列表缩小到港剧、韩剧、美剧或泰剧；「中国」就是普通的
 * 热门榜，即内地的。每六小时刷新一次，每个地区保留十张。
 */
final class HotShows {
    private static final String TAG = "TvHome.HotShows";
    static final String REGION_CN = "cn", REGION_HK = "hk", REGION_KR = "kr", REGION_US = "us", REGION_TH = "th", REGION_MOVIE = "movie";
    static final String[] REGIONS = {REGION_CN, REGION_HK, REGION_KR, REGION_US, REGION_TH, REGION_MOVIE};
    private static final long REFRESH_MS = 6 * 60 * 60 * 1000L;
    private static final int KEEP = 10;

    private HotShows() {
    }

    static int label(String region) {
        switch (region) {
            case REGION_HK: return R.string.hot_region_hk;
            case REGION_KR: return R.string.hot_region_kr;
            case REGION_US: return R.string.hot_region_us;
            case REGION_TH: return R.string.hot_region_th;
            case REGION_MOVIE: return R.string.hot_region_movie;
            default: return R.string.hot_region_cn;
        }
    }

    static File dir(Context ctx, String region) {
        return Backgrounds.dir(ctx, "hot-" + region);
    }

    private static String listUrl(String region) {
        String base = "https://pcw-api.iqiyi.com/search/recommend/list?data_type=1&mode=11&page_id=1&ret_num=24";
        switch (region) {
            case REGION_HK: return base + "&channel_id=2&three_category_id=16;must";
            case REGION_KR: return base + "&channel_id=2&three_category_id=17;must";
            case REGION_US: return base + "&channel_id=2&three_category_id=18;must";
            case REGION_TH: return base + "&channel_id=2&three_category_id=1114;must";
            case REGION_MOVIE: return base + "&channel_id=1";
            default: return base + "&channel_id=2";
        }
    }

    /** 不在主线程上调用；电视上的图片有变化时返回 true。 */
    static boolean refresh(Context ctx, String region, boolean force) throws Exception {
        File dir = dir(ctx, region);
        File index = new File(dir, "index.json");
        if (!force && index.isFile() && System.currentTimeMillis() - index.lastModified() < REFRESH_MS) return false;
        JSONObject json = new JSONObject(new String(Backgrounds.get(listUrl(region)), StandardCharsets.UTF_8));
        JSONArray list = json.getJSONObject("data").getJSONArray("list");
        JSONArray kept = new JSONArray();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < list.length() && kept.length() < KEEP; i++) {
            JSONObject show = list.getJSONObject(i);
            String image = show.optString("imageUrl", "");
            String title = show.optString("name", "").trim();
            if (image.isEmpty() || title.isEmpty() || !image.endsWith(".jpg")) continue;
            // 「…/a_100692840_m_601_m7.jpg」是小海报；同名换成 _1920_1080 就是 16:9 的大图。
            String art = image.replaceFirst("^http://", "https://").replaceFirst("\\.jpg$", "_1920_1080.jpg");
            String name = "show-" + show.optLong("albumId", i) + ".jpg";
            File f = new File(dir, name);
            try {
                if (!f.isFile()) Backgrounds.write(f, Backgrounds.get(art));
            } catch (Exception e) {
                Log.w(TAG, "no picture for " + title + ": " + e);
                continue;
            }
            String about = show.optString("description", "").trim();
            kept.put(new JSONObject().put("file", name).put("title", title).put("about", about));
            names.add(name);
        }
        if (kept.length() == 0) return false;
        Backgrounds.write(index, kept.toString().getBytes(StandardCharsets.UTF_8));
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (files != null) for (File f : files) if (!names.contains(f.getName())) f.delete();
        return true;
    }
}
