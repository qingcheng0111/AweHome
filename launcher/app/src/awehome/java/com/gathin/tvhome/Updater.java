package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AweHome：「立即检查」，仿照 Aerial 的 update/Updates.kt（pengchujin/aerial-tv，MIT）：从 {@code update_url}
 * （config.xml 或主题）读取最新版本，下载它的 APK，再用电视自带的安装器打开；TCL 的校验程序会拒绝 PackageInstaller
 * 会话，却接受这种方式。APK 必须是同一个包名、签名与当前这份相同且版本更新，否则安装器只会给出一个更难懂的失败提示。
 *
 * {@code update_url} 可以是一个 JSON 文件 {"versionCode": 27, "versionName": "1.0.16", "url": "…/app.apk"}，
 * 也可以是 GitHub 的 "releases/latest" API 地址：用它的 tag 与当前版本名比较，第一个 .apk 附件就是下载地址。
 */
final class Updater {
    private static final String TAG = "TvHome.Updater";

    enum State { IDLE, NO_SOURCE, CHECKING, LATEST, AVAILABLE, NEEDS_PERMISSION, DOWNLOADING, OPENED, FAILED }

    interface Listener {
        void onUpdate(State state, String detail);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static State state = State.IDLE;
    private static String detail = "";
    private static String apkUrl;
    private static Listener listener;

    private Updater() {
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static State state() {
        return state;
    }

    static String detail() {
        return detail;
    }

    private static void set(State s, String d) {
        MAIN.post(() -> {
            state = s;
            detail = d == null ? "" : d;
            if (listener != null) listener.onUpdate(state, detail);
        });
    }

    static String source(Context ctx) {
        return ctx.getString(R.string.update_url).trim();
    }

    /** 查询最新版本；比当前新时直接下载并安装（{@code install}）。 */
    static void check(Context context, boolean install) {
        Context app = context.getApplicationContext();
        String url = source(app);
        if (url.isEmpty()) {
            set(State.NO_SOURCE, null);
            return;
        }
        if (state == State.CHECKING || state == State.DOWNLOADING) return;
        if (install && (state == State.AVAILABLE || state == State.NEEDS_PERMISSION) && apkUrl != null) {
            download(app);
            return;
        }
        set(State.CHECKING, null);
        IO.execute(() -> {
            try {
                JSONObject json = new JSONObject(new String(get(url), StandardCharsets.UTF_8));
                PackageInfo self = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                String name;
                boolean newer;
                String apk;
                if (json.has("tag_name")) {
                    name = json.getString("tag_name").replaceFirst("^v", "");
                    newer = compare(name, self.versionName) > 0;
                    apk = null;
                    JSONArray assets = json.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) {
                            apk = a.optString("browser_download_url");
                            break;
                        }
                    }
                } else {
                    name = json.optString("versionName", "");
                    newer = json.optLong("versionCode", 0) > versionCode(self);
                    apk = json.optString("url", null);
                }
                if (!newer || apk == null || apk.isEmpty()) {
                    set(State.LATEST, null);
                    return;
                }
                apkUrl = apk;
                set(State.AVAILABLE, name);
                if (install) download(app);
            } catch (Exception e) {
                Log.w(TAG, "update check failed: " + e);
                set(State.FAILED, app.getString(R.string.update_failed_check));
            }
        });
    }

    private static void download(Context app) {
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            Intent allow = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                app.startActivity(allow);
                set(State.NEEDS_PERMISSION, null);
                return;
            } catch (ActivityNotFoundException e) {
                // 没有可以允许它的设置页（小米 MiTV-MFTP0，Aerial #284）：由固件的安装器决定。
            }
        }
        set(State.DOWNLOADING, "0%");
        IO.execute(() -> {
            File dir = new File(app.getCacheDir(), "updates");
            dir.mkdirs();
            File apk = new File(dir, "update.apk");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15_000);
                c.setReadTimeout(30_000);
                c.setInstanceFollowRedirects(true);
                long total = c.getContentLengthLong(), got = 0;
                int lastPercent = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int percent = total > 0 ? (int) (got * 100 / total) : -1;
                        if (percent >= 0 && percent != lastPercent && percent % 5 == 0) {
                            lastPercent = percent;
                            set(State.DOWNLOADING, percent + "%");
                        }
                    }
                } finally {
                    c.disconnect();
                }
                PackageManager pm = app.getPackageManager();
                PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
                PackageInfo self = pm.getPackageInfo(app.getPackageName(), 0);
                if (info == null || !app.getPackageName().equals(info.packageName)) {
                    set(State.FAILED, app.getString(R.string.update_failed_package));
                    return;
                }
                if (versionCode(info) <= versionCode(self)) {
                    set(State.LATEST, null);
                    return;
                }
                Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".files", apk);
                Intent install = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                MAIN.post(() -> {
                    try {
                        app.startActivity(install);
                        set(State.OPENED, null);
                    } catch (RuntimeException e) {
                        set(State.FAILED, app.getString(R.string.update_failed_blocked));
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "download failed: " + e);
                set(State.FAILED, app.getString(R.string.update_failed_download));
            }
        });
    }

    /** 某个状态下「立即检查」下面那行文字。 */
    static String status(Context ctx) {
        switch (state) {
            case NO_SOURCE: return ctx.getString(R.string.update_no_source);
            case CHECKING: return ctx.getString(R.string.update_checking);
            case AVAILABLE: return ctx.getString(R.string.update_available, detail);
            case NEEDS_PERMISSION: return ctx.getString(R.string.update_allow_install);
            case DOWNLOADING: return ctx.getString(R.string.update_downloading, detail);
            case OPENED: return ctx.getString(R.string.update_opened);
            case FAILED: return detail;
            case LATEST:
            default: return ctx.getString(R.string.update_latest);
        }
    }

    @SuppressWarnings("deprecation")
    /** 本机安装的版本名（「1.0.17」），偏好面板最底下显示；读不到时为 ""。 */
    static String versionName(Context ctx) {
        try {
            String name = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    /** 「1.0.10」比「1.0.9」新：数字按数值比较，其余按文本比较。 */
    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "", q = i < y.length ? y[i] : "";
            int c;
            if (p.matches("\\d+") && q.matches("\\d+")) c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            else c = p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    private static byte[] get(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Accept", "application/json");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
