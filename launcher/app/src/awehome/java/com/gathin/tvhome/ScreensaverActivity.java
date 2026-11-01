package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * AweHome：图片屏保（{@link PhotoActivity}）和视频屏保（{@link VideoActivity}）共用的部分，做法同
 * {@link ClockActivity}：用桌面的语言、全屏显示，按任意键或动一下指针就结束，结束时淡出；离开时直接结束，不留在返回栈里。
 */
public abstract class ScreensaverActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    /** 设好内容视图之后调用：全屏并隐藏导航栏。 */
    protected void fullScreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
    }

    /** 屏保停下：在 onPause 里、结束这个 Activity 之前调用。 */
    protected abstract void stopSaver();

    @Override
    protected final void onPause() {
        stopSaver();
        super.onPause();
        finish();   // 不留在返回栈里
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 结束屏保的这次按键不能再传给首页，所以在这里吃掉。
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
