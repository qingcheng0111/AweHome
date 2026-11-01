package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;

import java.io.InputStream;
import java.lang.ref.WeakReference;

/**
 * AweHome：液态玻璃，移植自 liquid-glass-react（rdev/liquid-glass-react，MIT，见
 * assets/liquid-glass/LICENSE.txt）。它在浏览器里先用 backdrop-filter 把背后的画面轻度模糊、提高饱和度，
 * 再用 SVG 的 feDisplacementMap 按一张位移贴图（它的 standard 模式，即 assets/liquid-glass/displacement.jpg）
 * 改变取样位置：越靠近玻璃边缘，取样点越往外，所以玻璃外的景物会被「吸」进边缘，像透过一块厚玻璃去看；
 * 红、绿、蓝三个通道的位移各差一点，边缘带一点色散。边框是一圈细亮边，按 135° 的渐变中间那条对角带最亮，
 * 一层滤色、一层叠加。
 * <p>
 * 同一套算法用在两处：视频背景由 {@link FilmRenderer} 的着色器每帧计算；图片背景和面板由 {@link #render} 用 CPU
 * 按较低的分辨率算出一张图，再拉伸画到玻璃上。参数都是它的默认值，网页的 1 像素按 1920 宽的屏幕换算
 * （{@link #css}）。它跟着鼠标的弹性变形和悬停高光是给鼠标用的，电视遥控器用不上，没有移植。
 * <p>
 * 只在主线程上调用（{@link FilmRenderer} 只在 GL 线程上读 {@link #map}，那张图加载后不再改动）。
 */
final class LiquidGlass {
    private static final String TAG = "TvHome.LiquidGlass";

    /** displacementScale：边缘处取样点最多往外移这么多的一半（网页像素）。 */
    static final float DISPLACEMENT = 70f;
    /** aberrationIntensity：三个通道的位移倍数为 1、1 + 0.05 × 它、1 + 0.1 × 它。 */
    static final float ABERRATION = 2f;
    static final float[] CHANNEL = {1f, 1f + ABERRATION * 0.05f, 1f + ABERRATION * 0.1f};
    /** 背后画面的模糊：blur(4 + blurAmount 0.0625 × 32) = 6 网页像素。 */
    static final float BLUR = 6f;
    /** saturate(140%)。 */
    static final float SATURATION = 1.4f;
    /** 边框宽 1.5，最外缘再有一条 0.5 的白线；单位是 dp（见 {@link Border}）。 */
    static final float BORDER = 1.5f, HAIRLINE = 0.5f;
    /** 背景图给液态玻璃用的模糊副本：宽 480 像素（屏幕的 1/4），StackBlur 半径 3，约等于全屏下 6 像素的模糊。 */
    static final int SOURCE_WIDTH = 480, SOURCE_RADIUS = 3;
    /**
     * 面板背后的屏幕快照（{@link Blur#snapshot}，屏幕的 1/8）再做的 StackBlur 半径：1/8 尺寸下一个像素就约等于网页里
     * 8 像素，只需再轻轻模糊一下。快照按 1/4 截取要多花约 40 毫秒，打开面板会卡一下，所以和磨砂一样用 1/8。
     */
    static final int SNAPSHOT_RADIUS = 1;

    private static final int MAP = 256;
    private static Bitmap map;
    /** 位移贴图的红（横向位移）、蓝（纵向位移）两个通道，0 到 255。 */
    private static byte[] mapX, mapY;
    private static boolean loadFailed;

    private LiquidGlass() {
    }

    /** 网页的 1 像素在这块屏幕上是多少像素：按 1920 宽的屏幕换算。 */
    static float css(Context ctx) {
        return Math.max(1f, ctx.getResources().getDisplayMetrics().widthPixels / 1920f);
    }

    /** 加载位移贴图（只加载一次）；之后 {@link #map} 和 {@link #render} 才有位移。 */
    static void load(Context ctx) {
        if (map != null || loadFailed) return;
        try (InputStream in = ctx.getAssets().open("liquid-glass/displacement.jpg")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null || b.getWidth() != MAP || b.getHeight() != MAP) throw new IllegalStateException("bad map");
            int[] px = new int[MAP * MAP];
            b.getPixels(px, 0, MAP, 0, 0, MAP, MAP);
            byte[] x = new byte[px.length], y = new byte[px.length];
            for (int i = 0; i < px.length; i++) {
                x[i] = (byte) ((px[i] >> 16) & 0xFF);
                y[i] = (byte) (px[i] & 0xFF);
            }
            mapX = x;
            mapY = y;
            map = b;
        } catch (Exception | OutOfMemoryError e) {
            loadFailed = true;
            Log.w(TAG, "no displacement map: " + e);
        }
    }

    /** 位移贴图本身（256×256，红为横向、蓝为纵向位移，0.5 为不动），给视频层的着色器上传成纹理；没加载时为 null。 */
    static Bitmap map() {
        return map;
    }

    // ---- 图片背景与面板：CPU 计算 ----

    /** 最近用过的几张源图的像素，免得每次都重新读出整张图。 */
    private static final int CACHE = 3;
    @SuppressWarnings("unchecked")
    private static final WeakReference<Bitmap>[] cachedBitmap = new WeakReference[CACHE];
    private static final int[][] cachedPixels = new int[CACHE][];
    private static final int[] cachedGeneration = new int[CACHE];
    private static int nextSlot;

    private static int[] pixels(Bitmap b) {
        for (int i = 0; i < CACHE; i++) {
            WeakReference<Bitmap> r = cachedBitmap[i];
            if (r != null && r.get() == b && cachedGeneration[i] == b.getGenerationId()) return cachedPixels[i];
        }
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % CACHE;
        cachedBitmap[slot] = new WeakReference<>(b);
        cachedPixels[slot] = px;
        cachedGeneration[slot] = b.getGenerationId();
        return px;
    }

    /**
     * 按液态玻璃的算法算出屏幕上 {@code rect} 这块玻璃里该显示的画面，写进 {@code out}（{@code out} 的尺寸决定精度，
     * 画的时候拉伸到 {@code rect}，外形由画的人裁成圆角）。{@code src} 是铺满屏幕的模糊图：屏幕坐标除以 {@code k}
     * 就是它的像素坐标。{@code look} 为 {gain, sat, offset[, brightest]}，同 {@link GlassLook}。源图或 out 无效时
     * 返回 false。
     */
    static boolean render(Bitmap src, float k, RectF rect, float css, float[] look, Bitmap out) {
        if (src == null || src.isRecycled() || out == null || out.isRecycled() || !out.isMutable()
                || rect.width() <= 0 || rect.height() <= 0) {
            return false;
        }
        int sw = src.getWidth(), sh = src.getHeight();
        int[] in = pixels(src);
        int ow = out.getWidth(), oh = out.getHeight();
        int[] px = new int[ow * oh];
        float cx = rect.centerX(), cy = rect.centerY();
        // 贴图按 xMidYMid slice 铺到玻璃上：等比放大到盖满较长的一边，居中。
        float size = Math.max(rect.width(), rect.height());
        float disp = DISPLACEMENT * css;
        float gain = look[0], sat = look[1], offset = look[2], brightest = look.length > 3 ? look[3] : 1f;
        float stepX = rect.width() / ow, stepY = rect.height() / oh;
        float[] rgb = new float[3];
        byte[] mx = mapX, my = mapY;
        for (int j = 0; j < oh; j++) {
            float y = rect.top + (j + 0.5f) * stepY;
            for (int i = 0; i < ow; i++) {
                float x = rect.left + (i + 0.5f) * stepX;
                float dx = 0, dy = 0;
                if (mx != null) {
                    float u = ((x - cx) / size + 0.5f) * (MAP - 1), v = ((y - cy) / size + 0.5f) * (MAP - 1);
                    dx = sampleMap(mx, u, v) - 0.5f;
                    dy = sampleMap(my, u, v) - 0.5f;
                }
                // feDisplacementMap，scale 为负：取样点 = 当前点 − 位移，越靠边越往外。
                for (int c = 0; c < 3; c++) {
                    float m = disp * CHANNEL[c];
                    rgb[c] = sample(in, sw, sh, (x - m * dx) / k, (y - m * dy) / k, c);
                }
                px[j * ow + i] = shade(rgb, gain, sat, offset, brightest);
            }
        }
        out.setPixels(px, 0, ow, 0, 0, ow, oh);
        return true;
    }

    /** 位移贴图上 (u, v) 处的值，0 到 1（双线性插值）。 */
    private static float sampleMap(byte[] m, float u, float v) {
        u = Math.max(0, Math.min(MAP - 1.001f, u));
        v = Math.max(0, Math.min(MAP - 1.001f, v));
        int x0 = (int) u, y0 = (int) v;
        float fx = u - x0, fy = v - y0;
        int i = y0 * MAP + x0;
        float a = m[i] & 0xFF, b = m[i + 1] & 0xFF, c = m[i + MAP] & 0xFF, d = m[i + MAP + 1] & 0xFF;
        return ((a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy) / 255f;
    }

    /** 源图 (x, y) 处第 {@code channel} 个通道（0 红、1 绿、2 蓝）的值，0 到 1；超出边缘时取边缘（双线性插值）。 */
    private static float sample(int[] in, int w, int h, float x, float y, int channel) {
        x = Math.max(0, Math.min(w - 1.001f, x - 0.5f));
        y = Math.max(0, Math.min(h - 1.001f, y - 0.5f));
        int x0 = (int) x, y0 = (int) y;
        float fx = x - x0, fy = y - y0;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        int shift = 16 - 8 * channel;
        float a = (in[y0 * w + x0] >> shift) & 0xFF, b = (in[y0 * w + x1] >> shift) & 0xFF;
        float c = (in[y1 * w + x0] >> shift) & 0xFF, d = (in[y1 * w + x1] >> shift) & 0xFF;
        return ((a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy) / 255f;
    }

    /** 饱和度（同 CSS saturate 与视频层的着色器）、增益和偏移，最亮不超过 {@code brightest}。 */
    private static int shade(float[] rgb, float gain, float sat, float offset, float brightest) {
        float l = 0.213f * rgb[0] + 0.715f * rgb[1] + 0.072f * rgb[2];
        float r = clamp((l + (rgb[0] - l) * sat) * gain + offset / 255f);
        float g = clamp((l + (rgb[1] - l) * sat) * gain + offset / 255f);
        float b = clamp((l + (rgb[2] - l) * sat) * gain + offset / 255f);
        float m = Math.max(r, Math.max(g, b));
        if (m > brightest) {
            float s = brightest / m;
            r *= s;
            g *= s;
            b *= s;
        }
        return 0xFF000000 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /**
     * 给 {@code rect} 这块玻璃准备 {@link #render} 的输出图：每边是玻璃的 {@code scale} 倍（至少 2 像素），
     * 尺寸没变时沿用 {@code reuse}。
     */
    static Bitmap target(Bitmap reuse, RectF rect, float scale) {
        int w = Math.max(2, Math.round(rect.width() * scale)), h = Math.max(2, Math.round(rect.height() * scale));
        if (reuse != null && !reuse.isRecycled() && reuse.getWidth() == w && reuse.getHeight() == h) return reuse;
        try {
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    // ---- 边框 ----

    /**
     * liquid-glass-react 的边框（两个叠在玻璃上的 span）：各是 1.5 像素宽的一圈，里面先按普通方式叠出
     * 135° 线性渐变、顶部内发光（inset 0 1px 3px rgba(255,255,255,0.25)）和最外缘 0.5 像素的白线
     * （inset 0 0 0 0.5px rgba(255,255,255,0.5)），再整体混合到画面上：一层滤色、透明度 0.2，一层叠加。
     * 这里用两个离屏图层照这个顺序画，结果才和网页一致（逐笔直接用混合模式会让白线处叠加两次、偏亮）。
     * <p>
     * 尺寸按 dp 算：网页是在 2 倍屏上看的，1.5 网页像素就是 3 个物理像素、白线是 1 个物理像素；电视的 density
     * 也是 2。按「屏幕宽 / 1920」算时白线不到一个像素，抗锯齿后亮度只剩一半左右，看不出那圈隐约的白光。
     * 按尺寸生成的路径和渐变只在尺寸变化时重建。
     */
    static final class Border {
        private final Paint screenLayer = new Paint(), overlayLayer = new Paint();
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ringPath = new Path();
        private final RectF tmp = new RectF(), bounds = new RectF();
        private LinearGradient screenRamp, overlayRamp;
        private float shapeW = -1, shapeH = -1, shapeR = -1, shapeUnit = -1;

        Border() {
            screenLayer.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            screenLayer.setAlpha(Math.round(0.2f * 255));
            overlayLayer.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0x80FFFFFF);
        }

        /** 在 {@code rect}（玻璃外框，与 {@code c} 同一坐标系）上画边框；{@code dp} 为屏幕密度（1 dp 的像素数）。 */
        void draw(Canvas c, RectF rect, float radius, float dp) {
            float w = rect.width(), h = rect.height();
            if (w <= 0 || h <= 0) return;
            if (w != shapeW || h != shapeH || radius != shapeR || dp != shapeUnit) shape(w, h, radius, dp);
            int save = c.save();
            c.translate(rect.left, rect.top);
            layer(c, screenRamp, screenLayer, radius, dp);
            layer(c, overlayRamp, overlayLayer, radius, dp);
            c.restoreToCount(save);
        }

        private void shape(float w, float h, float radius, float dp) {
            shapeW = w;
            shapeH = h;
            shapeR = radius;
            shapeUnit = dp;
            bounds.set(0, 0, w, h);
            // 只留 1.5 像素的一圈：外圆角矩形减去向内缩 1.5 的圆角矩形（mask: content-box exclude border-box）。
            float ringW = BORDER * dp;
            ringPath.reset();
            ringPath.setFillType(Path.FillType.EVEN_ODD);
            ringPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            tmp.set(ringW, ringW, w - ringW, h - ringW);
            float inner = Math.max(0f, radius - ringW);
            ringPath.addRoundRect(tmp, inner, inner, Path.Direction.CW);
            // CSS 的 135°：渐变线朝右下，长度 (w + h) / √2，经过中心。
            float half = (w + h) / 4f;
            float x0 = w / 2f - half, y0 = h / 2f - half, x1 = w / 2f + half, y1 = h / 2f + half;
            float[] stops = {0f, 0.33f, 0.66f, 1f};
            screenRamp = new LinearGradient(x0, y0, x1, y1,
                    new int[]{0x00FFFFFF, white(0.12f), white(0.4f), 0x00FFFFFF}, stops, Shader.TileMode.CLAMP);
            overlayRamp = new LinearGradient(x0, y0, x1, y1,
                    new int[]{0x00FFFFFF, white(0.32f), white(0.6f), 0x00FFFFFF}, stops, Shader.TileMode.CLAMP);
            // 顶部内发光：下移 1、模糊 3 的内阴影，贴着上沿最亮，往下约 4 个单位淡到没有。
            glow.setShader(new LinearGradient(0, 0, 0, 4 * dp, white(0.25f), 0x00FFFFFF, Shader.TileMode.CLAMP));
            line.setStrokeWidth(HAIRLINE * dp);
        }

        private void layer(Canvas c, Shader ramp, Paint composite, float radius, float dp) {
            int save = c.saveLayer(bounds, composite);
            ring.setShader(ramp);
            c.drawPath(ringPath, ring);
            c.drawPath(ringPath, glow);
            float by = HAIRLINE * dp / 2f;
            tmp.set(by, by, shapeW - by, shapeH - by);
            float r = Math.max(0f, radius - by);
            c.drawRoundRect(tmp, r, r, line);
            c.restoreToCount(save);
        }

        private static int white(float a) {
            return Math.round(a * 255) << 24 | 0xFFFFFF;
        }
    }
}
