package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;

/**
 * AweHome：「屏幕保护」三选一：时钟（{@link ClockActivity}）、图片（{@link PhotoActivity}）、
 * 视频（{@link VideoActivity}），都由桌面自己显示。在首页按返回键打开选中的那个。
 */
final class Screensavers {
    private Screensavers() {
    }

    static void startNow(Context ctx) {
        String choice = HomePrefs.screensaver(ctx);
        Class<?> saver = HomePrefs.SAVER_CLOCK.equals(choice) ? ClockActivity.class
                : HomePrefs.SAVER_VIDEO.equals(choice) ? VideoActivity.class : PhotoActivity.class;
        ctx.startActivity(new Intent(ctx, saver));
    }
}
