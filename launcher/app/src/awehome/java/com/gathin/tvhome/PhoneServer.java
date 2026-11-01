package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

/**
 * AweHome：「手机传图」，手机扫描面板上的二维码后打开的页面：把墙纸传进墙纸文件夹、删除它们、给应用图标换一张
 * 自己的图片、设定天气的城市。移植自 Aerial（pengchujin/aerial-tv，MIT，wallpaper/PhoneServer.kt）：首页运行期间
 * NanoHTTPD 监听 8791 端口，所有地址都藏在一个只显示在电视上的随机令牌后面，网络里的其他设备猜不到。
 */
final class PhoneServer extends NanoHTTPD {
    private static final String TAG = "TvHome.Phone";
    /**
     * Aerial 自己监听 8790，两者可能装在同一台电视上（小米 MiTV-MFTP0 上报 bind failed, EADDRINUSE），
     * 所以从 8791 开始，被占用就顺延到下一个空闲端口。
     */
    static final int PORT = 8791;
    private static final int PORT_TRIES = 8;
    /** 足够一批原尺寸的手机照片；NanoHTTPD 会先把整个请求体放进缓存。 */
    private static final long MAX_UPLOAD = 120L * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 手机改动了什么之后在主线程上通知，让首页显示出来。 */
    interface Listener {
        void onPhoneChange(String what);
    }

    private static volatile PhoneServer running;
    private static volatile Listener listener;
    private final Context ctx;
    private final String token;

    private PhoneServer(Context ctx, String token, int port) {
        super(port);
        this.ctx = ctx;
        this.token = token;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String prefix = "/" + token;
        String uri = session.getUri();
        if (!uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found");
        String path = uri.substring(prefix.length());
        if (path.isEmpty()) path = "/";
        try {
            if (path.equals("/")) return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page());
            if (path.equals("/api/state")) return json(state());
            if (path.startsWith("/thumb/")) return thumb(java.net.URLDecoder.decode(path.substring(7), "UTF-8"));
            if (path.startsWith("/icon/")) return icon(java.net.URLDecoder.decode(path.substring(6), "UTF-8"));
            if (session.getMethod() == Method.POST) {
                switch (path) {
                    case "/api/upload": return upload(session);
                    case "/api/delete": {
                        boolean gone = Backgrounds.deleteUpload(ctx, form(session).get("name"));
                        if (gone) changed("wallpapers");
                        return json(state().put("deleted", gone));
                    }
                    case "/api/use-folder":
                        form(session);
                        HomePrefs.put(ctx, HomePrefs.BG_SOURCE, HomePrefs.SOURCE_FOLDER);
                        HomePrefs.putQuietly(ctx, HomePrefs.BG_PICK, "");
                        changed("background");
                        return json(state());
                    case "/api/icon": return saveIcon(session);
                    case "/api/icon/reset": {
                        String pkg = form(session).get("app");
                        if (installed(pkg) && CustomIcons.clear(ctx, pkg)) changed("icons");
                        return json(state());
                    }
                    case "/api/weather/city": {
                        boolean found = Weather.choose(ctx, form(session).get("name"));
                        if (found) changed("weather");
                        return json(state().put("found", found));
                    }
                    case "/api/weather/auto":
                        form(session);
                        Weather.useNetworkLocation(ctx);
                        changed("weather");
                        return json(state());
                    default:
                        break;
                }
            }
            return text(Response.Status.NOT_FOUND, "Not found");
        } catch (Exception e) {
            Log.w(TAG, "request failed: " + e);
            return text(Response.Status.INTERNAL_ERROR, "error");
        }
    }

    private static void changed(String what) {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onPhoneChange(what);
        });
    }

    private String page() throws java.io.IOException {
        try (InputStream in = ctx.getAssets().open("web/phone.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8").replace("{{DEVICE}}", Build.MODEL.replace('_', ' '));
        }
    }

    private JSONObject state() throws Exception {
        JSONArray walls = new JSONArray();
        for (File f : Backgrounds.folderFiles(ctx)) {
            if (!f.getParentFile().equals(Backgrounds.uploadsDir(ctx))) continue;
            walls.put(new JSONObject().put("name", f.getName()));
        }
        JSONArray apps = new JSONArray();
        for (String[] a : apps()) {
            apps.put(new JSONObject().put("pkg", a[0]).put("label", a[1]).put("custom", CustomIcons.has(ctx, a[0])));
        }
        Weather.Now now = Weather.now(ctx);
        JSONObject weather = new JSONObject().put("place", Weather.place(ctx) == null ? "" : Weather.place(ctx))
                .put("chosen", Weather.chosen(ctx));
        // 用桌面的语言（偏好里可单独设置），而不是电视的系统语言。
        if (now != null) weather.put("celsius", now.celsius).put("kind", LauncherLocale.wrap(ctx).getString(Weather.label(now.kind)));
        return new JSONObject().put("wallpapers", walls).put("apps", apps).put("weather", weather)
                .put("source", HomePrefs.bgSource(ctx));
    }

    /** 首页显示的应用，按名称排序：包名和名称。 */
    private List<String[]> apps() {
        PackageManager pm = ctx.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || !seen.add(pkg)) continue;
                if (category.equals(Intent.CATEGORY_LAUNCHER) && !LibraryApps.load(ctx).contains(pkg)) continue;
                out.add(new String[]{pkg, String.valueOf(ri.loadLabel(pm))});
            }
        }
        Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    private boolean installed(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String[] a : apps()) if (a[0].equals(pkg)) return true;
        return false;
    }

    private Response upload(IHTTPSession session) throws Exception {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large");
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        int added = 0;
        // NanoHTTPD 把每个部分存在一个临时文件里；多张照片依次叫 photo、photo1、photo2……
        for (String path : files.values()) {
            File f = new File(path);
            if (f.isFile() && f.length() > 0 && Backgrounds.addUpload(ctx, f) != null) added++;
        }
        if (added > 0) changed("wallpapers");
        return json(state().put("added", added));
    }

    private Response saveIcon(IHTTPSession session) throws Exception {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large");
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        List<String> app = session.getParameters().get("app");
        String pkg = app == null || app.isEmpty() ? "" : app.get(0);
        if (!installed(pkg)) return text(Response.Status.BAD_REQUEST, "unknown app");
        boolean saved = false;
        for (String path : files.values()) {
            File f = new File(path);
            if (f.isFile() && f.length() > 0) {
                saved = CustomIcons.save(ctx, pkg, f);
                break;
            }
        }
        if (saved) changed("icons");
        return json(state().put("saved", saved));
    }

    private Response thumb(String name) {
        File f = new File(Backgrounds.uploadsDir(ctx), name);
        if (!f.isFile() || !f.getParentFile().equals(Backgrounds.uploadsDir(ctx))) return text(Response.Status.NOT_FOUND, "");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 8;
        return jpeg(BitmapFactory.decodeFile(f.getPath(), o));
    }

    private Response icon(String pkg) {
        File f = CustomIcons.file(ctx, pkg);
        if (!f.isFile()) return text(Response.Status.NOT_FOUND, "");
        return jpeg(BitmapFactory.decodeFile(f.getPath()));
    }

    private Response jpeg(Bitmap bmp) {
        if (bmp == null) return text(Response.Status.NOT_FOUND, "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
        bmp.recycle();
        byte[] bytes = out.toByteArray();
        Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(bytes), bytes.length);
        r.addHeader("Cache-Control", "max-age=60");
        return r;
    }

    private static boolean tooLarge(IHTTPSession session) {
        String length = session.getHeaders().get("content-length");
        try {
            return length != null && Long.parseLong(length) > MAX_UPLOAD;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static Map<String, String> form(IHTTPSession session) throws Exception {
        session.parseBody(new HashMap<>());
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : session.getParameters().entrySet()) {
            out.put(e.getKey(), e.getValue().isEmpty() ? "" : e.getValue().get(0));
        }
        return out;
    }

    private static Response json(JSONObject o) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString());
    }

    private static Response text(Response.Status status, String msg) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", msg);
    }

    // ---- 启动、地址与二维码 ----

    /** 从首页启动起一直监听到首页结束，手机什么时候打开页面都有回应。 */
    static synchronized PhoneServer listen(Context context) {
        PhoneServer s = running;
        if (s != null && s.isAlive()) return s;
        String token = token(context);
        for (int i = 0; i < PORT_TRIES; i++) {
            try {
                s = new PhoneServer(context.getApplicationContext(), token, PORT + i);
                s.start(SOCKET_READ_TIMEOUT, true);
                running = s;
                return s;
            } catch (java.net.BindException e) {
                s.stop();
                Log.i(TAG, "port " + (PORT + i) + " taken, trying the next");
            } catch (Exception e) {
                Log.w(TAG, "could not listen: " + e);
                return null;
            }
        }
        Log.w(TAG, "could not listen: ports " + PORT + "-" + (PORT + PORT_TRIES - 1) + " all taken");
        return null;
    }

    /** 二维码上的地址（"http://192.168.1.20:8791/abcd2345/"），需要时先开始监听；没联网时为 null。 */
    static String address(Context context) {
        String ip = lanAddress(context);
        if (ip == null) return null;
        PhoneServer s = listen(context);
        return s == null ? null : "http://" + ip + ":" + s.getListeningPort() + "/" + s.token + "/";
    }

    /** 每次启动都沿用，收藏过的地址一直可用。 */
    private static String token(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("phone_page", Context.MODE_PRIVATE);
        String t = prefs.getString("token", null);
        if (t != null && t.length() == 8) return t;
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        prefs.edit().putString("token", sb.toString()).apply();
        return sb.toString();
    }

    /**
     * 电视在家庭网络里的地址。代理软件开着 VPN 时，默认网络是那条隧道，手机连不到（Aerial #667），
     * 所以优先取隧道下面的 Wi-Fi 或以太网。
     */
    static String lanAddress(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        List<Network> lan = new ArrayList<>();
        Network active = cm.getActiveNetwork();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                if (n.equals(active)) lan.add(0, n);
                else lan.add(n);
            }
        }
        if (active != null && !lan.contains(active)) lan.add(active);
        for (Network n : lan) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress() && !benchmark(a)) {
                    return a.getHostAddress();
                }
            }
        }
        return null;
    }

    /** 198.18.0.0/15：代理软件放隧道的网段，不会是家庭网络。 */
    private static boolean benchmark(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 198 && (b[1] & 0xfe) == 18;
    }

    /** 二维码：白底黑块，每块 {@code scale} 像素见方，没有留边。 */
    static Bitmap qr(String text, int scale) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int n = m.getWidth();
            int[] pixels = new int[n * scale * n * scale];
            for (int y = 0; y < n * scale; y++) {
                for (int x = 0; x < n * scale; x++) {
                    pixels[y * n * scale + x] = m.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, n * scale, n * scale, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Log.w(TAG, "no QR code: " + e);
            return null;
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
