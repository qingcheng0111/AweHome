package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * AweHome：视频屏保（「屏幕保护」→ 视频）：全屏播放航拍影片（{@link AerialFilm}），角上显示时间，底部显示
 * 当前地名，接着首页背景上次播到的影片继续放。在首页按返回键时打开（{@link Screensavers}），按任意键回到首页。
 * 影片放不出来时改为显示图片（{@link PhotoScene}）。
 */
public class VideoActivity extends ScreensaverActivity {
    private AerialFilm film;
    private PhotoScene photos;
    private TextView caption;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        film = new AerialFilm(this, root, 0, false, new AerialFilm.Host() {
            @Override
            public void onCaption(String text) {
                caption.setText(text);
            }

            @Override
            public void onFrame(Bitmap small) {
            }

            @Override
            public void onFailed() {
                Toast.makeText(VideoActivity.this, R.string.aerial_failed, Toast.LENGTH_LONG).show();
                photos = new PhotoScene(VideoActivity.this);
                setContentView(photos.view());
                photos.start();
            }

            @Override
            public void onShowing(boolean on) {
            }
        });
        PhotoScene.addClock(this, root);
        caption = PhotoScene.addCaption(this, root);
        setContentView(root);
        fullScreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        film.play();
    }

    @Override
    protected void stopSaver() {
        film.stop();
        if (photos != null) photos.stop();
    }

    @Override
    protected void onDestroy() {
        film.close();
        if (photos != null) photos.close();
        super.onDestroy();
    }
}
