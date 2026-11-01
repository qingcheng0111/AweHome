package com.gathin.tvhome;

import android.os.Bundle;

/**
 * AweHome：图片屏保（{@link PhotoScene}），在首页按返回键时打开（{@link Screensavers}），按任意键回到首页
 * （{@link ScreensaverActivity}）。
 */
public class PhotoActivity extends ScreensaverActivity {
    private PhotoScene scene;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scene = new PhotoScene(this);
        setContentView(scene.view());
        fullScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
    }

    @Override
    protected void stopSaver() {
        scene.stop();
    }

    @Override
    protected void onDestroy() {
        scene.close();
        super.onDestroy();
    }
}
