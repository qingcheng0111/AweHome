package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * AweHome：「航拍视频」，把首页背景换成动态画面：Apple TV 屏保里的航拍影片，做法移植自 Aerial
 * （pengchujin/aerial-tv，MIT，wallpaper/Aerials.kt 与 ui/home/VideoWallpaper.kt）。APK 里只带片单（Aerial 的
 * assets/aerials.json：Apple 公开的 resources-16.tar 里的地址和地名）；首页显示时从 Apple 服务器逐部在线播放
 * 1080p H.264 影片，顶栏下方的地名随画面变化。用系统自带的 MediaPlayer 播到 TextureView 上，不引入播放器库；
 * 隔一段时间抓一帧小图，代替图片用于需要模糊或取色的地方：网格的磨砂层、面板玻璃和胶囊颜色。
 * <p>
 * 画面由 {@link FilmRenderer} 用 OpenGL 画到 TextureView 上，顺带每帧把程序坞和按钮背后的画面实时模糊（{@link FilmGlass}）；
 * GL 不可用时退回让播放器直接输出到 TextureView，玻璃再改用每隔几秒抓的一帧。
 * <p>
 * 所有对播放器的调用都在独立线程上。固件的播放器可能同步等网络：在小米 MiTV-MFTP0（Android 9，厂商播放器）上，
 * 一次调用把首页主线程卡住超过 10 秒，系统判定首页无响应并结束它、清掉了默认桌面，最近用过的应用随之被切到前台。
 */
final class AerialFilm implements TextureView.SurfaceTextureListener {
    interface Host {
        /** 当前地名；"" 表示隐藏。 */
        void onCaption(String text);

        /** 一帧约 240 像素宽的新画面，用于模糊层和胶囊取色。 */
        void onFrame(Bitmap small);

        /** 没有影片能播放（离线或连不上 Apple 服务器）：首页改回显示图片。 */
        void onFailed();

        /** 影片已盖满屏幕（淡入完成后为 {@code true}），或已经消失。 */
        void onShowing(boolean on);
    }

    /** "" 表示全部影片；其余为 aerials.json 的分类，顺序与「选择墙纸」页一致。 */
    static final String[] CATEGORIES = {"", "landscapes", "cities", "space", "underwater"};

    static final class Film {
        final String id, label, category, url;
        /** 每个地名开始的秒数，以及地名本身。 */
        final int[] at;
        final String[] text;

        Film(String id, String label, String category, String url, int[] at, String[] text) {
            this.id = id;
            this.label = label;
            this.category = category;
            this.url = url;
            this.at = at;
            this.text = text;
        }

        String captionAt(int seconds) {
            String now = label;
            for (int i = 0; i < at.length; i++) if (at[i] <= seconds) now = text[i];
            return now;
        }
    }

    private static final String TAG = "TvHome.Aerial";
    /** 抓帧的尺寸：屏幕的 1/4，液态玻璃从这么清晰的图里取样（{@link LiquidGlass#SOURCE_WIDTH}）。 */
    private static final int FRAME_W = 480, FRAME_H = 270;
    /** 没有 GL 时（退回直接输出），每隔这么多秒抓一帧给程序坞和按钮的玻璃用。有 GL 时玻璃每帧实时画，不抓帧。 */
    private static final int FRAME_EVERY_S = 4;
    /** 连续失败这么多部后放弃，直到首页下次显示时再试。 */
    private static final int MAX_FAILURES = 3;
    /** 一部影片要在这么久内准备好，否则当作放不出来（连不上服务器时 MediaPlayer 自己要等很久才报错）。 */
    private static final long OPEN_TIMEOUT_MS = 12_000;
    /** 播放中缓冲超过这么久：换一部，不停在冻住的画面上。 */
    private static final long STALL_TIMEOUT_MS = 15_000;
    private static List<Film> catalogue;

    private final Context ctx;
    private final Host host;
    private final TextureView view;
    private final SharedPreferences state;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("aerial-film");
    /** 播放器线程：下面标注「播放器线程」的代码只在这里运行。 */
    private final Handler io;
    private final Random random = new Random();
    /** 首页的影片：负责程序坞和按钮的实时玻璃（{@link FilmGlass}）。视频屏保为 false，不碰首页的玻璃状态。 */
    private final boolean glass;

    // 主线程。
    private boolean wanted;
    // 主线程写、播放器线程读。
    private volatile boolean live, away;
    private volatile Surface surface;
    /** GL 渲染器；null 或 {@link #direct} 为 true 时，播放器直接输出到视图。 */
    private volatile FilmRenderer renderer;
    private volatile boolean direct;
    // 播放器线程。
    private MediaPlayer player;
    private boolean prepared;
    private Film film;
    private int failures, ticks, position;
    /** 当前画面的地名：播放器线程写，主线程在影片出现时读。 */
    private volatile String caption = "";

    AerialFilm(Context ctx, FrameLayout root, int index, boolean glass, Host host) {
        this.ctx = ctx;
        this.glass = glass;
        this.host = host;
        if (glass) LiquidGlass.load(ctx);
        state = ctx.getSharedPreferences("aerial_film", Context.MODE_PRIVATE);
        thread.start();
        io = new Handler(thread.getLooper());
        view = new TextureView(ctx);
        view.setAlpha(0f);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setSurfaceTextureListener(this);
        root.addView(view, index, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 片单，只读一次：桌面语言为中文时用中文地名，否则用英文。 */
    static synchronized List<Film> all(Context ctx) {
        if (catalogue != null) return catalogue;
        List<Film> out = new ArrayList<>();
        boolean zh = Locale.getDefault().getLanguage().equals("zh");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("aerials.json"), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray videos = new JSONObject(sb.toString()).getJSONArray("videos");
            for (int i = 0; i < videos.length(); i++) {
                JSONObject o = videos.getJSONObject(i);
                JSONArray c = o.optJSONArray("captions");
                int n = c == null ? 0 : c.length();
                int[] at = new int[n];
                String[] text = new String[n];
                for (int k = 0; k < n; k++) {
                    JSONArray e = c.getJSONArray(k);
                    at[k] = e.getInt(0);
                    text[k] = e.getString(zh ? 1 : 2).replace(' ', ' ');
                }
                String label = n > 0 ? text[0] : o.getString("label");
                out.add(new Film(o.getString("id"), label, o.getString("category"), o.getString("url"), at, text));
            }
        } catch (Exception e) {
            Log.w(TAG, "no aerial catalogue: " + e);
        }
        catalogue = Collections.unmodifiableList(out);
        return catalogue;
    }

    /** 换语言后：片单按新语言重新读（地名有中英两种）。 */
    static synchronized void resetCatalogue() {
        catalogue = null;
    }

    /** 换语言后：正在播的影片按新语言重新显示地名。 */
    void recaption() {
        io.post(() -> {
            if (film == null) return;
            for (Film f : all(ctx)) {
                if (f.id.equals(film.id)) {
                    film = f;
                    caption = f.captionAt(position / 1000);
                    caption(caption);
                    return;
                }
            }
        });
    }

    /** 某个分类的影片（"" 为全部）。 */
    static List<Film> of(Context ctx, String category) {
        List<Film> out = new ArrayList<>();
        for (Film f : all(ctx)) if (category.isEmpty() || f.category.equals(category)) out.add(f);
        return out;
    }

    // ---- 主线程 ----

    /** 首页以此为背景显示中：从上次停下的影片和位置接着播。 */
    void play() {
        if (wanted) return;
        wanted = true;
        live = true;
        io.post(() -> {
            failures = 0;
            open();
        });
    }

    /** 离开首页或换了背景：释放解码器和网络流，记住播到的影片和位置。 */
    void stop() {
        wanted = false;
        live = false;
        main.removeCallbacksAndMessages(null);
        io.post(() -> release(true));
        hide();
    }

    void close() {
        stop();
        // 移除视图时纹理交给播放器线程释放（见 onSurfaceTextureDestroyed），之后线程结束。
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        io.post(thread::quitSafely);
    }

    /** 电视自己的界面（如屏保）盖在首页上：暂停，直到首页重新获得焦点。 */
    void setAway(boolean on) {
        away = on;
        io.post(this::applyHold);
    }

    /** 当前是否有影片显示在屏幕上。 */
    boolean showing() {
        return wanted && view.getAlpha() > 0f;
    }

    /** 影片当前画面，尺寸 {@code w}×{@code h}；没有影片显示时为 null。 */
    Bitmap frame(int w, int h) {
        if (!showing() || !view.isAvailable()) return null;
        try {
            return view.getBitmap(w, h);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** 立即抓一帧，用于模糊层和取色。 */
    void grab() {
        Bitmap b = frame(FRAME_W, FRAME_H);
        if (b != null) host.onFrame(b);
    }

    private void hide() {
        view.animate().cancel();
        if (view.getAlpha() > 0f) {
            view.setAlpha(0f);
            if (glass) FilmGlass.setActive(false);
            host.onShowing(false);
        }
    }

    private void fadeIn() {
        if (!wanted) return;
        // 影片的画面出来了才换成它的地名；准备中或放不出来时，仍显示下面那张图片的名称。
        host.onCaption(caption);
        if (view.getAlpha() < 1f) {
            view.animate().alpha(1f).setDuration(600).withEndAction(() -> {
                if (!wanted) return;
                // 视频已盖满屏幕：程序坞和按钮的玻璃从这时起由视频层每帧实时画。
                if (glass) FilmGlass.setActive(!direct && renderer != null);
                host.onShowing(true);
            }).start();
        }
        // 抓一帧给网格的磨砂层和面板玻璃备用（主线程上只抓这一次）。
        main.postDelayed(this::grab, 700);
    }

    private void caption(String text) {
        main.post(() -> {
            if (showing()) host.onCaption(text);
        });
    }

    // ---- 播放器线程 ----

    private void applyHold() {
        if (player == null || !prepared) return;
        try {
            if (away) {
                if (player.isPlaying()) player.pause();
            } else if (!player.isPlaying()) {
                player.start();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void open() {
        if (!live || surface == null || player != null) return;
        // 根本没有网络：不必一部部去试，直接退回图片。
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null || cm.getActiveNetwork() == null) {
            Log.w(TAG, "no network, no film");
            fail();
            return;
        }
        List<Film> films = of(ctx, HomePrefs.aerialCategory(ctx));
        if (films.isEmpty()) films = all(ctx);
        if (films.isEmpty()) {
            fail();
            return;
        }
        String last = state.getString("id", "");
        Film resume = null;
        for (Film f : films) if (f.id.equals(last)) resume = f;
        if (resume != null) start(resume, state.getInt("pos", 0));
        else start(films.get(random.nextInt(films.size())), 0);
    }

    private void start(Film f, int fromMs) {
        Surface s = surface;
        if (!live || s == null) return;
        film = f;
        prepared = false;
        position = Math.max(0, fromMs);
        MediaPlayer p = new MediaPlayer();
        player = p;
        p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        p.setVolume(0f, 0f);
        p.setOnVideoSizeChangedListener((mp, w, h) -> {
            FilmRenderer r = renderer;
            if (r != null && !direct) r.setVideoSize(w, h);
            else main.post(() -> fit(w, h));
        });
        p.setOnPreparedListener(mp -> {
            if (mp != player) return;
            prepared = true;
            io.removeCallbacks(openTimeout);
            try {
                if (fromMs > 0 && fromMs < mp.getDuration() - 5000) mp.seekTo(fromMs);
                if (!away) mp.start();
            } catch (IllegalStateException ignored) {
            }
            io.removeCallbacks(tick);
            io.post(tick);
        });
        p.setOnInfoListener((mp, what, extra) -> {
            if (mp != player) return false;
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                failures = 0;
                main.post(this::fadeIn);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                io.removeCallbacks(stallTimeout);
                io.postDelayed(stallTimeout, STALL_TIMEOUT_MS);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                io.removeCallbacks(stallTimeout);
            }
            return false;
        });
        p.setOnCompletionListener(mp -> {
            if (mp == player) next();
        });
        p.setOnErrorListener((mp, what, extra) -> {
            if (mp == player) {
                Log.w(TAG, "film " + f.id + " failed: " + what + "/" + extra);
                failed();
            }
            return true;
        });
        state.edit().putString("id", f.id).putInt("pos", position).apply();
        caption = f.captionAt(position / 1000);
        caption(caption);
        try {
            p.setSurface(s);
            p.setDataSource(f.url);
            p.prepareAsync();
            io.postDelayed(openTimeout, OPEN_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "film " + f.id + " would not open: " + e);
            io.post(this::failed);
        }
    }

    private void failed() {
        release(false);
        if (!live) return;
        if (++failures >= MAX_FAILURES) {
            fail();
            return;
        }
        io.postDelayed(() -> {
            if (live && player == null && surface != null) start(pick(), 0);
        }, 1500);
    }

    private void fail() {
        live = false;
        main.post(() -> {
            wanted = false;
            hide();
            host.onFailed();
        });
    }

    /** 准备超时：网络慢到放不了，当作这一部失败（见 {@link #OPEN_TIMEOUT_MS}）。 */
    private final Runnable openTimeout = () -> {
        if (player == null || prepared) return;
        Log.w(TAG, "film " + (film == null ? "" : film.id) + " timed out");
        failed();
    };

    /** 缓冲太久：同上，换一部。 */
    private final Runnable stallTimeout = () -> {
        if (player == null) return;
        Log.w(TAG, "film " + (film == null ? "" : film.id) + " stalled");
        failed();
    };

    /** 下一部：同分类里的另一部。 */
    private void next() {
        release(false);
        if (live && surface != null) start(pick(), 0);
    }

    private Film pick() {
        List<Film> films = of(ctx, HomePrefs.aerialCategory(ctx));
        if (films.isEmpty()) films = all(ctx);
        if (films.size() > 1 && film != null) {
            List<Film> others = new ArrayList<>(films);
            for (int i = others.size() - 1; i >= 0; i--) if (others.get(i).id.equals(film.id)) others.remove(i);
            films = others;
        }
        return films.get(random.nextInt(films.size()));
    }

    /** 播放时每秒一次：更新地名；没有 GL 时每 {@link #FRAME_EVERY_S} 秒抓一帧。 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player == null || !prepared) return;
            try {
                if (player.isPlaying()) {
                    position = player.getCurrentPosition();
                    String now = film.captionAt(position / 1000);
                    if (!now.equals(caption)) {
                        caption = now;
                        caption(now);
                    }
                    if (direct && ++ticks % FRAME_EVERY_S == 0) main.post(AerialFilm.this::grab);
                }
            } catch (IllegalStateException ignored) {
            }
            io.postDelayed(this, 1000);
        }
    };

    /** 释放播放器；{@code keepPlace} 为 true 时记住影片和播放位置，下次从这里接着播。 */
    private void release(boolean keepPlace) {
        io.removeCallbacks(tick);
        io.removeCallbacks(openTimeout);
        io.removeCallbacks(stallTimeout);
        MediaPlayer p = player;
        player = null;
        if (p == null) return;
        if (keepPlace && prepared && film != null) state.edit().putString("id", film.id).putInt("pos", position).apply();
        prepared = false;
        try {
            p.release();
        } catch (RuntimeException ignored) {
        }
    }

    // ---- 视图，主线程 ----

    /** 影片铺满屏幕；比例与屏幕不同时裁切，不拉伸。 */
    private void fit(int w, int h) {
        float vw = view.getWidth(), vh = view.getHeight();
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return;
        float scale = Math.max(vw / w, vh / h);
        Matrix m = new Matrix();
        m.setScale(w * scale / vw, h * scale / vh, vw / 2f, vh / 2f);
        view.setTransform(m);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        FilmRenderer r = new FilmRenderer(glass);
        renderer = r;
        r.start(texture, width, height, videoSurface -> {
            if (videoSurface != null) {
                direct = false;
                surface = videoSurface;
            } else {
                // 没有 GL：播放器直接画到视图上。
                direct = true;
                renderer = null;
                r.release(null);
                surface = new Surface(texture);
            }
            io.post(this::open);
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        FilmRenderer r = renderer;
        if (r != null) r.resize(width, height);
    }

    /** 先在播放器线程上释放播放器，再释放 GL 和纹理：返回 false，纹理由我们自己释放。 */
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        Surface s = surface;
        surface = null;
        FilmRenderer r = renderer;
        renderer = null;
        boolean straight = direct;
        if (glass) FilmGlass.setActive(false);
        io.post(() -> {
            release(true);
            if (r != null) {
                r.release(texture::release);
            } else {
                if (s != null && straight) s.release();
                texture.release();
            }
        });
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }
}
