package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AweHome：「信号源名称」，给每个 HDMI 端口起名（从列表里选一种设备，或者自己输入），信号源列表把名字显示在端口
 * 标记前面（「Switch · HDMI1」）。也包括视频里小米电视的信号源列表多出来的几行：投屏、TV 和 DTMB、USB，
 * 它们的打开方式来自 Aerial（pengchujin/aerial-tv，MIT，system/SystemState.kt）。
 */
final class InputLooks {
    /** 端口可以用来命名的设备，顺序同视频里的列表。品牌名保持原样。 */
    static final String[] DEVICES = {"apple_tv", "ps5", "ps4", "xbox", "switch", "stb", "bluray", "soundbar",
            "computer", "projector", "karaoke", "camera"};
    static final String CUSTOM = "custom";
    private static final Pattern PORT = Pattern.compile("HDMI\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HW = Pattern.compile("/HW(\\d+)$");

    static final String XIAOMI_PLAYER = "com.xiaomi.mitv.tvplayer";

    private InputLooks() {
    }

    static String deviceLabel(Context ctx, String key) {
        switch (key) {
            case "apple_tv": return "Apple TV";
            case "ps5": return "PlayStation 5";
            case "ps4": return "PlayStation 4";
            case "xbox": return "Xbox";
            case "switch": return "Switch";
            case "stb": return "Set-top box";
            case "bluray": return "Blu-ray";
            case "soundbar": return "Soundbar";
            case "computer": return "Computer";
            case "projector": return "Projector";
            case "karaoke": return "Karaoke";
            case "camera": return "Camera";
            default: return ctx.getString(R.string.input_custom);
        }
    }

    /** 端口号：从标签里取（「HDMI 2」），MediaTek 的 id 则取硬件编号（HW5 是 HDMI 1）；都没有时为 0。 */
    static int portNumber(String label, String id) {
        Matcher m = label == null ? null : PORT.matcher(label);
        if (m != null && m.find()) return Integer.parseInt(m.group(1));
        Matcher h = id == null ? null : HW.matcher(id);
        if (h != null && h.find()) {
            int n = Integer.parseInt(h.group(1)) - 4;
            return n >= 1 && n <= 4 ? n : 0;
        }
        return 0;
    }

    /** 端口名称保存时用的键："hdmi2"，固件更新给输入源重新编号后也不变。 */
    static String portKey(int number, String id) {
        return number > 0 ? "hdmi" + number : id;
    }

    /** 为端口选的名称：设备名或输入的名字；没有（「无」）时为 null。 */
    static String name(Context ctx, String portKey) {
        SharedPreferences p = HomePrefs.of(ctx);
        String device = p.getString(HomePrefs.INPUT_DEVICE_PREFIX + portKey, null);
        if (device == null) return null;
        if (CUSTOM.equals(device)) {
            String typed = p.getString(HomePrefs.INPUT_NAME_PREFIX + portKey, "").trim();
            return typed.isEmpty() ? null : typed;
        }
        return deviceLabel(ctx, device);
    }

    /** 为端口选的设备键、"custom"，没有时为 null。 */
    static String device(Context ctx, String portKey) {
        return HomePrefs.of(ctx).getString(HomePrefs.INPUT_DEVICE_PREFIX + portKey, null);
    }

    static void setDevice(Context ctx, String portKey, String device) {
        HomePrefs.putQuietly(ctx, HomePrefs.INPUT_DEVICE_PREFIX + portKey, device);
    }

    static void setCustom(Context ctx, String portKey, String typed) {
        HomePrefs.of(ctx).edit().putString(HomePrefs.INPUT_DEVICE_PREFIX + portKey, CUSTOM)
                .putString(HomePrefs.INPUT_NAME_PREFIX + portKey, typed.trim()).apply();
    }

    /** 电视的 HDMI 端口，按端口顺序给出 {编号, 键}；电视一个都没列出时用 HDMI 1 到 4。 */
    static List<String[]> hdmiPorts(Context ctx) {
        List<String[]> ports = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            // input_sources 里隐藏的口（"HW8=-"：主板报出了但电视上没有这个接口）也不出现在命名页。
            List<String> sources = Config.load(ctx).inputSources;
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
                if (Integer.valueOf(-1).equals(InputSources.mapped(sources, info.getId()))) continue;
                CharSequence l = info.loadLabel(ctx);
                int n = portNumber(l == null ? null : l.toString(), info.getId());
                String key = portKey(n, info.getId());
                boolean seen = false;
                for (String[] p : ports) if (p[1].equals(key)) seen = true;
                if (!seen) ports.add(new String[]{String.valueOf(n), key});
            }
        }
        if (ports.isEmpty()) for (int n = 1; n <= 4; n++) ports.add(new String[]{String.valueOf(n), "hdmi" + n});
        Collections.sort(ports, (a, b) -> Integer.compare(Integer.parseInt(a[0]), Integer.parseInt(b[0])));
        return ports;
    }

    static int namedCount(Context ctx) {
        int n = 0;
        for (String[] p : hdmiPorts(ctx)) if (name(ctx, p[1]) != null) n++;
        return n;
    }

    // ---- 多出来的几行 ----

    /** 电视自带的投屏界面（小米的「投屏」），没有时为 null。 */
    static Intent castIntent(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : new String[]{"com.xiaomi.mitv.smartshare", "com.tcl.MultiScreenInteraction_TV"}) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    /**
     * 电视自带的 U 盘播放器，同 Aerial 的做法：小米的「高清播放器」（已在 MiTV-MFTR0 上验证）或 TCL 的「媒体中心」
     * （MT5879）。两者都没装时为 null。
     */
    static Intent usbIntent(Context ctx) {
        ComponentName[] players = {
                new ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"),
                new ComponentName("com.tcl.ui_mediaCenter", "com.tcl.ui_mediaCenter.main.MainActivity"),
        };
        for (ComponentName c : players) {
            try {
                ctx.getPackageManager().getActivityInfo(c, 0);
                return new Intent(Intent.ACTION_MAIN).setComponent(c)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            } catch (PackageManager.NameNotFoundException ignored) {
                // 不是这台电视的
            }
        }
        return null;
    }

    /** 小米电视的一行调谐器：标签（TV 或 DTMB）和打开它的播放器 Intent。 */
    static final class Tuner {
        final String label;
        final Intent intent;

        Tuner(String label, Intent intent) {
            this.label = label;
            this.intent = intent;
        }
    }

    /**
     * 小米的调谐器，名字同它自己的信号源列表：模拟的叫「TV」，数字的叫「DTMB」，都在固件的播放器里打开
     * （Aerial 的 xiaomiRoute：DTMB_PLAY 与 ATV_PLAY）。其他电视上为空。
     */
    static List<Tuner> xiaomiTuners(Context ctx) {
        List<Tuner> out = new ArrayList<>();
        if (!installed(ctx, XIAOMI_PLAYER)) return out;
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        boolean atv = false, dtv = false;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_TUNER || info.getParentId() != null || !fromSystem(info)) continue;
            CharSequence l = info.loadLabel(ctx);
            String label = l == null ? "" : l.toString();
            Matcher h = HW.matcher(info.getId());
            int hw = h.find() ? Integer.parseInt(h.group(1)) : -1;
            if (digital(label, hw)) dtv = true;
            else atv = true;
        }
        if (atv) out.add(new Tuner("TV", new Intent("com.xiaomi.mitv.tvplayer.ATV_PLAY")
                .setComponent(new ComponentName(XIAOMI_PLAYER, XIAOMI_PLAYER + ".AtvActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        if (dtv) out.add(new Tuner("DTMB", new Intent("com.xiaomi.mitv.tvplayer.DTMB_PLAY")
                .setComponent(new ComponentName(XIAOMI_PLAYER, XIAOMI_PLAYER + ".dtmb.DTMBActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        return out;
    }

    /**
     * 小米国行固件上的外接输入：没有应用响应标准的 channel URI，只能通过固件自己的播放器切换，即发送
     * com.xiaomi.mitv.tvplayer.EXTSRC_PLAY 并把源编号放进 "input"（MiTV-MFTP0 上该 URI 报 "No activities found"）。
     * 移植自 Aerial 的 xiaomiRoute：HDMI 口的编号优先取固件的 hdmiN_order，没有时用联发科硬件号加 18（HW5 为 23）；
     * AV 为 2。仅在 input_sources 没有映射时使用。加 CLEAR_TOP：否则后台残留的播放器任务只会被调到前台，新的
     * input 会被忽略。没有该播放器或输入既不是 HDMI 也不是 AV 时返回 null。
     */
    static Intent xiaomiRoute(Context ctx, TvInputInfo info, String label) {
        Matcher h = HW.matcher(info.getId());
        if (!h.find()) return null;
        int input;
        switch (info.getType()) {
            case TvInputInfo.TYPE_HDMI:
                Integer order = xiaomiConfiguredSource(label);
                input = order != null ? order : Integer.parseInt(h.group(1)) + 18;
                break;
            case TvInputInfo.TYPE_COMPOSITE:
            case TvInputInfo.TYPE_SVIDEO:
            case TvInputInfo.TYPE_COMPONENT:
                input = 2;
                break;
            default:
                return null;
        }
        Intent intent = new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(new ComponentName(XIAOMI_PLAYER, XIAOMI_PLAYER + ".ExternalSourceActivity"))
                .putExtra("input", input)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent.resolveActivity(ctx.getPackageManager()) != null ? intent : null;
    }

    /**
     * 移植自 Aerial 的 xiaomiConfiguredSource：部分小米固件按主板给信号源编号，其播放器会向
     * mitv.common.ConfigurationManager 查询 hdmiN_order。这里用反射读取；没有该框架的电视返回 null。
     */
    private static Integer xiaomiConfiguredSource(String label) {
        Matcher m = label == null ? null : PORT.matcher(label);
        if (m == null || !m.find()) return null;
        try {
            Class<?> configuration = Class.forName("mitv.common.ConfigurationManager");
            Object manager = configuration.getMethod("getInstance").invoke(null);
            Object order = configuration.getMethod("queryTvConfigByKey", String.class).invoke(manager, "hdmi" + m.group(1) + "_order");
            int n = order instanceof String ? Integer.parseInt(((String) order).trim()) : 0;
            return n > 0 ? n : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Aerial 的 isDigitalTuner：标签能说明时按标签判断，否则 MediaTek 的 HW0 是数字调谐器。 */
    private static boolean digital(String label, int hw) {
        String l = label.toUpperCase(Locale.ROOT);
        if (l.contains("ATV")) return false;
        if (l.contains("DTV") || l.contains("DTMB") || l.contains("DVB")) return true;
        return hw == 0;
    }

    private static boolean fromSystem(TvInputInfo info) {
        try {
            return (info.getServiceInfo().applicationInfo.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
