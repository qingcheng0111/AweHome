package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * AweHome：时钟上方的天气行（「☀ 15° 東京 · 晴 · 最高 18° 最低 11°」）。
 *
 * 移植自 Aerial（pengchujin/aerial-tv，MIT，weather/Weather.kt 与 Cities.kt）：用 Open-Meteo，它不需要密钥，国内也能访问；
 * 地点是用户在手机页面上设定的城市，没有的话按网络的公网地址猜一个（ipinfo.io）。首页回到前台时获取，每小时最多一次。
 * 地名用桌面的语言显示：先查 Aerial 自带的中国城市表，再用 Open-Meteo 的地理编码按这种语言查，东京就是这样变成「東京」的。
 */
final class Weather {
    private static final String TAG = "TvHome.Weather";
    private static final String PREFS = "weather";
    private static final long REFRESH_MS = 60 * 60 * 1000L;
    private static final long RETRY_MS = 10 * 60 * 1000L;
    private static final long RELOCATE_MS = 24 * 60 * 60 * 1000L;
    /** 比这更旧的天气数据完全不显示。 */
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;
    private static final int NONE = Integer.MIN_VALUE;

    enum Kind { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDER }

    static final class Now {
        final int celsius;
        final Kind kind;
        final boolean day;
        final Integer high, low;

        Now(int celsius, Kind kind, boolean day, Integer high, Integer low) {
            this.celsius = celsius;
            this.kind = kind;
            this.day = day;
            this.high = high;
            this.low = low;
        }
    }

    interface Listener {
        void onWeather();
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastAttempt;
    private static boolean failed;
    private static Listener listener;

    private Weather() {
    }

    static void setListener(Listener l) {
        listener = l;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 最近一次的天气数据；没有或太旧不该显示时为 null。 */
    static Now now(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (System.currentTimeMillis() - p.getLong("fetched_at", 0) >= STALE_MS) return null;
        Kind kind;
        try {
            kind = Kind.valueOf(p.getString("kind", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        int high = p.getInt("high", NONE), low = p.getInt("low", NONE);
        return new Now(p.getInt("celsius", 0), kind, p.getBoolean("day", true),
                high == NONE ? null : high, low == NONE ? null : low);
    }

    /** 天气所属的地点，用于显示；还不知道时为 null。 */
    static String place(Context ctx) {
        return prefs(ctx).getString("place", null);
    }

    /** 地点是用户指定的时为 true；按网络猜测（「自动」）时为 false。 */
    static boolean chosen(Context ctx) {
        return prefs(ctx).getBoolean("chosen", false);
    }

    /** 上次的数据满一小时就重新获取；失败过的话，网络恢复后会更早再取。 */
    static void refreshIfStale(Context ctx, boolean networkCameUp) {
        long since = System.currentTimeMillis() - lastAttempt;
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) refresh(ctx);
    }

    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        lastAttempt = System.currentTimeMillis();
        IO.execute(() -> {
            Now reading = null;
            try {
                reading = fetch(app);
            } catch (Exception e) {
                Log.w(TAG, "weather unavailable: " + e);
            }
            failed = reading == null;
            if (reading != null) {
                prefs(app).edit()
                        .putLong("fetched_at", System.currentTimeMillis())
                        .putInt("celsius", reading.celsius)
                        .putString("kind", reading.kind.name())
                        .putBoolean("day", reading.day)
                        .putInt("high", reading.high == null ? NONE : reading.high)
                        .putInt("low", reading.low == null ? NONE : reading.low)
                        .apply();
            }
            MAIN.post(() -> {
                if (listener != null) listener.onWeather();
            });
        });
    }

    /** 不在主线程上调用。 */
    private static Now fetch(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        float lat = p.getFloat("lat", Float.NaN), lon = p.getFloat("lon", Float.NaN);
        boolean chosen = p.getBoolean("chosen", false);
        String language = language();
        boolean guessedLongAgo = System.currentTimeMillis() - p.getLong("located_at", 0) > RELOCATE_MS;
        if (chosen && !Float.isNaN(lat) && !language.equals(p.getString("place_lang", ""))) {
            // 用户选的地点，按桌面的新语言重新取名：上海变成 Shanghai。
            // 地理编码只认中国城市的拼音：上海要用 Shanghai 去查。
            String query = p.getString("typed", null);
            if (query == null) {
                City c = Cities.find(ctx, p.getString("place", ""));
                query = c != null ? c.ascii : p.getString("place", "");
            }
            String renamed = language.equals("zh") ? chineseName(ctx, query, lat, lon) : null;
            if (renamed == null) renamed = nearestName(query, lat, lon, language);
            SharedPreferences.Editor e = p.edit().putString("place_lang", language);
            if (renamed != null) e.putString("place", renamed);
            e.apply();
        }
        if (!chosen && (Float.isNaN(lat) || guessedLongAgo || !language.equals(p.getString("place_lang", "")))) {
            // ipinfo.io：不需要密钥，它的 "loc" 是 "纬度,经度"。
            JSONObject ip = new JSONObject(get("https://ipinfo.io/json"));
            String[] loc = ip.getString("loc").split(",");
            lat = Float.parseFloat(loc[0]);
            lon = Float.parseFloat(loc[1]);
            String city = ip.optString("city", "").trim();
            String name = localName(ctx, city, ip.optString("country", ""), lat, lon, language);
            p.edit().putFloat("lat", lat).putFloat("lon", lon)
                    .putString("place", name != null ? name : city.isEmpty() ? null : city)
                    .putString("place_lang", language)
                    .putLong("located_at", System.currentTimeMillis()).apply();
        }
        JSONObject json = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"));
        JSONObject current = json.getJSONObject("current");
        JSONObject daily = json.optJSONObject("daily");
        return new Now((int) Math.round(current.getDouble("temperature_2m")), kindOf(current.getInt("weather_code")),
                current.optInt("is_day", 1) == 1, today(daily, "temperature_2m_max"), today(daily, "temperature_2m_min"));
    }

    private static Integer today(JSONObject daily, String key) {
        JSONArray a = daily == null ? null : daily.optJSONArray(key);
        if (a == null || a.length() == 0) return null;
        double v = a.optDouble(0);
        return Double.isNaN(v) ? null : (int) Math.round(v);
    }

    /**
     * ipinfo 用英文给出的城市，换成 {@code language} 的说法：在中国查 Aerial 的城市表（Open-Meteo 的地理编码认识的中文名很少），
     * 其他地方用地理编码，取离网络所在位置最近的结果。
     */
    private static String localName(Context ctx, String city, String country, float lat, float lon, String language) {
        if (city.isEmpty() || language.equals("en")) return null;
        if ("CN".equals(country) && language.startsWith("zh")) {
            City c = Cities.near(ctx, city, lat, lon, 80, true);
            if (c != null) return c.name;
        }
        try {
            String q = URLEncoder.encode(city, "UTF-8");
            JSONArray results = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=" + q
                    + "&count=10&language=" + language + "&format=json")).optJSONArray("results");
            String best = null;
            double nearest = 80;
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                double d = Cities.distanceKm(r.getDouble("latitude"), r.getDouble("longitude"), lat, lon);
                if (d < nearest) {
                    nearest = d;
                    best = r.optString("name", null);
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** 这个点附近、拼音名相符的 Aerial 城市表里的城市：返回它的中文名；没有时为 null。 */
    private static String chineseName(Context ctx, String ascii, float lat, float lon) {
        City c = Cities.near(ctx, ascii, lat, lon, 60, true);
        return c == null ? null : c.name;
    }

    /** 地理编码对 {@code query} 在 {@code language} 下的名称，取离这个点最近的结果；没有时为 null。 */
    private static String nearestName(String query, float lat, float lon, String language) {
        if (query == null || query.isEmpty()) return null;
        try {
            String q = URLEncoder.encode(query, "UTF-8");
            JSONArray results = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=" + q
                    + "&count=10&language=" + language + "&format=json")).optJSONArray("results");
            String best = null;
            double nearest = 60;
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                double d = Cities.distanceKm(r.getDouble("latitude"), r.getDouble("longitude"), lat, lon);
                if (d < nearest) {
                    nearest = d;
                    best = r.optString("name", null);
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 指定地点（来自手机页面或面板）并获取它的天气。中文名先在城市表里查，其他的用地理编码查。
     * 没有这个地点时返回 false。不在主线程上调用。
     */
    static boolean choose(Context ctx, String name) {
        String typed = name == null ? "" : name.trim();
        if (typed.isEmpty()) return false;
        double la, lo;
        String shown;
        City c = Cities.find(ctx, typed);
        if (c != null) {
            la = c.lat;
            lo = c.lon;
            shown = c.name;
        } else {
            try {
                String q = URLEncoder.encode(typed, "UTF-8");
                JSONArray results = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=" + q
                        + "&count=10&language=" + language() + "&format=json")).optJSONArray("results");
                if (results == null || results.length() == 0) return false;
                JSONObject best = results.getJSONObject(0);
                // 同名时城镇排在村庄前面。
                for (int i = 0; i < results.length(); i++) {
                    String code = results.getJSONObject(i).optString("feature_code", "");
                    if (code.startsWith("PPLA") || code.equals("PPLC")) {
                        best = results.getJSONObject(i);
                        break;
                    }
                }
                la = best.getDouble("latitude");
                lo = best.getDouble("longitude");
                shown = best.optString("name", typed);
            } catch (Exception e) {
                Log.w(TAG, "could not look up " + typed + ": " + e);
                return false;
            }
        }
        // 连同输入时的名字和显示用的语言一起保存，换成另一种语言时可以重新取名。
        prefs(ctx).edit().putFloat("lat", (float) la).putFloat("lon", (float) lo).putString("place", shown)
                .putString("typed", c != null ? c.ascii : typed).putString("place_lang", language())
                .putBoolean("chosen", true).apply();
        refresh(ctx);
        return true;
    }

    /** 回到按网络猜测地点（「自动」）。 */
    static void useNetworkLocation(Context ctx) {
        prefs(ctx).edit().putBoolean("chosen", false).remove("lat").remove("lon").remove("located_at").apply();
        refresh(ctx);
    }

    /** WMO 天气代码，即 Open-Meteo 报告的那些。 */
    private static Kind kindOf(int code) {
        if (code == 0) return Kind.CLEAR;
        if (code == 1 || code == 2) return Kind.PARTLY_CLOUDY;
        if (code == 3) return Kind.CLOUDY;
        if (code == 45 || code == 48) return Kind.FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Kind.SNOW;
        if (code >= 95 && code <= 99) return Kind.THUNDER;
        return Kind.RAIN;
    }

    static int icon(Now now) {
        switch (now.kind) {
            case CLEAR: return now.day ? R.drawable.ic_sun : R.drawable.ic_moon_small;
            case PARTLY_CLOUDY: return R.drawable.ic_partly;
            case CLOUDY: return R.drawable.ic_cloud;
            case FOG: return R.drawable.ic_fog;
            case SNOW: return R.drawable.ic_snow;
            case THUNDER: return R.drawable.ic_thunder;
            default: return R.drawable.ic_rain;
        }
    }

    static int label(Kind kind) {
        switch (kind) {
            case CLEAR: return R.string.weather_clear;
            case PARTLY_CLOUDY: return R.string.weather_partly_cloudy;
            case CLOUDY: return R.string.weather_cloudy;
            case FOG: return R.string.weather_fog;
            case SNOW: return R.string.weather_snow;
            case THUNDER: return R.string.weather_thunder;
            default: return R.string.weather_rain;
        }
    }

    /** 「15° 東京 · 晴 · 最高 18° 最低 11°」；没有数据时为 null。 */
    static String line(Context ctx) {
        Now now = now(ctx);
        if (now == null) return null;
        StringBuilder s = new StringBuilder().append(now.celsius).append('°');
        String place = place(ctx);
        if (place != null && !place.isEmpty()) s.append("  ").append(place);
        s.append(" · ").append(ctx.getString(label(now.kind)));
        if (now.high != null && now.low != null) {
            s.append(" · ").append(ctx.getString(R.string.weather_high_low, now.high, now.low));
        }
        return s.toString();
    }

    /** 地名用的桌面语言：zh、ja、ko、es 或 en。 */
    private static String language() {
        String l = Locale.getDefault().getLanguage();
        return l.equals("zh") || l.equals("ja") || l.equals("ko") || l.equals("es") ? l : "en";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---- Aerial 的中国城市表（assets/cities-cn.tsv，GeoNames，CC BY 4.0） ----

    static final class City {
        final String name, province, ascii;
        final List<String> aliases;
        final double lat, lon;

        City(String name, List<String> aliases, String province, double lat, double lon, String ascii) {
            this.name = name;
            this.aliases = aliases;
            this.province = province;
            this.lat = lat;
            this.lon = lon;
            this.ascii = ascii;
        }
    }

    static final class Cities {
        private static List<City> all;
        /** 与 Aerial 的 tools/make-cities.py 去掉的后缀相同。 */
        private static final Pattern SUFFIX = Pattern.compile("(壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|自治州|自治县|地区|新区|省|市|县|区|盟|旗)$");

        private Cities() {
        }

        static synchronized List<City> load(Context ctx) {
            if (all != null) return all;
            List<City> list = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("cities-cn.tsv"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#") || line.trim().isEmpty()) continue;
                    String[] c = line.split("\t", -1);
                    if (c.length < 7) continue;
                    List<String> aliases = new ArrayList<>();
                    for (String a : c[1].split(",")) if (!a.isEmpty()) aliases.add(a);
                    list.add(new City(c[0], aliases, c[2], Double.parseDouble(c[3]), Double.parseDouble(c[4]), c[6]));
                }
            } catch (Exception e) {
                Log.w(TAG, "could not read the city list: " + e);
            }
            all = list;
            return all;
        }

        /** 「商丘」「商丘市」「河南商丘」或「北京朝阳」：城市排在县之前，表本身就是这个顺序。 */
        static City find(Context ctx, String typed) {
            String q = typed.replaceAll("\\s", "");
            if (q.startsWith("中国")) q = q.substring(2);
            if (q.isEmpty()) return null;
            for (int i = 0; i < q.length(); i++) {
                char ch = q.charAt(i);
                if (ch < 0x4e00 || ch > 0x9fff) return null;
            }
            List<City> cities = load(ctx);
            String s = shortName(q);
            for (City c : cities) if (c.name.equals(s)) return c;
            for (City c : cities) if (inProvince(c, q) && c.name.equals(rest(c, q))) return c;
            for (City c : cities) if (c.aliases.contains(s)) return c;
            for (City c : cities) if (inProvince(c, q) && c.aliases.contains(rest(c, q))) return c;
            return null;
        }

        static City near(Context ctx, String name, double lat, double lon, double km, boolean ascii) {
            City best = null;
            double bestD = Double.MAX_VALUE;
            String s = shortName(name);
            for (City c : load(ctx)) {
                boolean match = ascii ? c.ascii.equalsIgnoreCase(name) : c.name.equals(s);
                if (!match) continue;
                double d = distanceKm(c.lat, c.lon, lat, lon);
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            return best != null && bestD <= km ? best : null;
        }

        private static boolean inProvince(City c, String q) {
            return !c.province.isEmpty() && q.startsWith(c.province);
        }

        private static String rest(City c, String q) {
            String r = q.substring(c.province.length());
            while (r.startsWith("省") || r.startsWith("市")) r = r.substring(1);
            if (r.startsWith("壮族自治区")) r = r.substring(5);
            else if (r.startsWith("自治区")) r = r.substring(3);
            return shortName(r);
        }

        private static String shortName(String name) {
            String s = SUFFIX.matcher(name).replaceAll("");
            return s.length() >= 2 ? s : name;
        }

        static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
            double x = (lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
            double y = lat2 - lat1;
            return Math.sqrt(x * x + y * y) * 111.2;
        }
    }
}
