package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * AweHome：把图片屏保（{@link PhotoScene}）包装成 Android 屏保（DreamService），供支持 Android 屏保的电视
 * 在系统设置里选用。
 */
public class PhotoDream extends DreamService {
    private PhotoScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        scene = new PhotoScene(this);
        setContentView(scene.view());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        scene.stop();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (scene != null) scene.close();
    }
}
