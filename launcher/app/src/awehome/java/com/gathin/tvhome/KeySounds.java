package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * AweHome：「按键声音」。移植自 Aerial（pengchujin/aerial-tv，MIT，system/Sounds.kt），连同它的音效
 * （res/raw/ui_*.wav）：遥控器移动焦点时「嗒」一声，确认键和返回键各一声，按到头没处可去时一声闷响。
 * 因为面板打开而落下的焦点不出声；每按一次键最多响一声。
 */
final class KeySounds {
    private static SoundPool pool;
    private static int move, select, back, limit;
    private static boolean on;
    private static long lastDpadAt, tickedFor = -1;

    private KeySounds() {
    }

    /** 用户打开按键声音时加载音效；加载要花一点时间，所以在第一次按键之前就做好。 */
    static void init(Context context) {
        on = HomePrefs.keySounds(context);
        if (!on || pool != null) return;
        Context app = context.getApplicationContext();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
        move = pool.load(app, R.raw.ui_move, 1);
        select = pool.load(app, R.raw.ui_select, 1);
        back = pool.load(app, R.raw.ui_back, 1);
        limit = pool.load(app, R.raw.ui_limit, 1);
    }

    static void setOn(Context context, boolean enabled) {
        on = enabled;
        if (enabled) init(context);
    }

    /** Activity 收到的每个按键，在各视图处理之前。 */
    static void onKey(KeyEvent event) {
        if (!on || event.getAction() != KeyEvent.ACTION_DOWN) return;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                lastDpadAt = event.getEventTime();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (event.getRepeatCount() == 0) play(select, 1f);
                break;
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0) play(back, 0.9f);
                break;
            default:
                break;
        }
    }

    /** 某个控件得到了焦点：如果是刚才的方向键要它的，就「嗒」一声。 */
    static void focusMoved() {
        long at = lastDpadAt;
        if (!on || at == tickedFor || SystemClock.uptimeMillis() - at > 250) return;
        tickedFor = at;
        play(move, 0.7f);
    }

    /** 按了方向键却没处可去。 */
    static void limit() {
        if (!on || lastDpadAt == tickedFor) return;
        tickedFor = lastDpadAt;
        play(limit, 0.9f);
    }

    private static void play(int id, float volume) {
        if (pool != null && id != 0) pool.play(id, volume, volume, 1, 0, 1f);
    }
}
