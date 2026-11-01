package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * AweHome：「模糊效果」的两种玻璃，程序坞底板、右上角按钮、按钮打开的面板三处共用。
 * <ul>
 * <li>液态玻璃：移植自 liquid-glass-react（{@link LiquidGlass}）：背后画面轻度模糊（6 网页像素）、饱和度 140%，
 * 按位移贴图向外取样，边缘把玻璃外的景物「吸」进来、带一点色散；边框是一圈 1.5dp 的细亮边：135° 渐变、
 * 最外缘一条白线和顶部的内发光，一层滤色、一层叠加，看起来是隐约的一圈白光（{@link LiquidGlass.Border}）。</li>
 * <li>磨砂玻璃：参数来自视频逐像素测量（以视频结尾没有面板的帧作为原始背景，与同一位置的像素对比）：背后画面
 * <b>重度</b>模糊（约 36 像素），饱和度降低、程序坞和面板压暗，颜色发灰。边缘是一圈柔和的光晕。</li>
 * </ul>
 */
final class GlassLook {
    private GlassLook() {
    }

    // 颜色变换：先按 sat 调整饱和度（以亮度为中心），再乘以 gain、加 offset。
    // 数组 {gain, sat, offset} 给视频层的实时玻璃（FilmRenderer）和液态玻璃的 CPU 计算（LiquidGlass.render）用，
    // ColorFilter 给磨砂的静态模糊图用，同一组数。液态的饱和度是 liquid-glass-react 的 140%，磨砂的数值见类注释。
    // 按钮多一个第 4 项：玻璃最亮不超过 0.78（以 1 为白），背后是明亮的天空时，按钮上的白色图标仍看得清。
    // 液态面板略压暗到 0.9：面板上文字多，背后是明亮的画面时仍要看得清（网页版只靠文字阴影）。
    static final float[] LIQUID_PILL_LOOK = {1f, LiquidGlass.SATURATION, 0, 0.78f}, FROSTED_PILL_LOOK = {1.0f, 0.8f, 0, 0.78f};
    static final float[] LIQUID_DOCK_LOOK = {1f, LiquidGlass.SATURATION, 0}, FROSTED_DOCK_LOOK = {0.8f, 0.8f, 0};
    static final float[] LIQUID_PANEL_LOOK = {0.9f, LiquidGlass.SATURATION, 0};
    static final ColorFilter FROSTED_PILL = filter(FROSTED_PILL_LOOK);
    static final ColorFilter FROSTED_DOCK = filter(FROSTED_DOCK_LOOK);
    static final float[] FROSTED_PANEL_LOOK = {0.6f, 0.85f, 8};
    /** 液态玻璃 2 的面板底：模板的 rgba(255,255,255,0.04) 底色再乘 brightness(0.9)：× 0.9 × 0.96，加 0.04 × 0.9 的白。 */
    static final float[] CLEAR_PANEL_LOOK = {0.864f, 1f, 9.2f};
    static final ColorFilter FROSTED_PANEL = filter(FROSTED_PANEL_LOOK);
    static final ColorFilter CLEAR_PANEL = filter(CLEAR_PANEL_LOOK);
    /** 面板玻璃由视频层实时画时，叠在上面的界面截图也按同样的颜色变换（见 {@link GlassPanel}）。 */
    static final ColorFilter LIQUID_PANEL = filter(LIQUID_PANEL_LOOK);

    /** 换背景时玻璃跟着画面交叉淡入的时长，与 {@link Backdrop} 的图片淡入一致。 */
    static final long FADE_MS = 700;

    private static ColorFilter filter(float[] look) {
        return filter(look[0], look[1], look[2]);
    }

    private static ColorFilter filter(float gain, float sat, float offset) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(sat);
        m.postConcat(new ColorMatrix(new float[]{
                gain, 0, 0, 0, offset,
                0, gain, 0, 0, offset,
                0, 0, gain, 0, offset,
                0, 0, 0, 1, 0}));
        return new ColorMatrixColorFilter(m);
    }

    /**
     * 圆角矩形玻璃的边缘：液态是 liquid-glass-react 的细亮边（{@link LiquidGlass.Border}）；磨砂是约 3dp 宽、
     * 由外向内变淡的柔和光晕（用三道叠加的描边模拟）。{@code rect} 是玻璃的外框，{@code radius} 是圆角。
     */
    static void drawRim(Canvas c, RectF rect, float radius, boolean frosted, float dp, Paint paint, RectF tmp,
                        LiquidGlass.Border border) {
        if (!frosted) {
            border.draw(c, rect, radius, dp);
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(null);
        float[] widths = {3.5f * dp, 2.2f * dp, 1f * dp};
        int[] alphas = {0x14, 0x1A, 0x24};
        for (int i = 0; i < widths.length; i++) {
            paint.setStrokeWidth(widths[i]);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(alphas[i]);
            inset(c, rect, radius, widths[i] / 2f, paint, tmp);
        }
    }

    private static void inset(Canvas c, RectF rect, float radius, float by, Paint paint, RectF tmp) {
        tmp.set(rect);
        tmp.inset(by, by);
        float r = Math.max(0f, radius - by);
        c.drawRoundRect(tmp, r, r, paint);
    }

    /**
     * 右上角按钮未聚焦时的背景：一块圆角玻璃。磨砂透出按钮正后方的重度模糊图（{@link Backdrop#blurred}，按铺满屏幕时的
     * 位置取那一块）；液态由 {@link LiquidGlass#render} 从轻度模糊图（{@link Backdrop#clear}）算出带位移和色散的画面。
     * 视频背景下玻璃由视频层画（{@link FilmGlass}），这里只画边缘。还没有背景时画一层浅色玻璃。
     */
    static final class Pill extends Drawable {
        /** 液态玻璃按固定的 48×48 计算：画面本来就是模糊的，拉伸后看不出差别，按钮伸缩时也不用重新分配。 */
        private static final int LIQUID_SIZE = 48;
        private final View host;
        private final float dp, css;
        /** 磨砂：源图；液态：算好的玻璃图。 */
        private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        /** 换图时上一张，在新图下面淡出。 */
        private final Paint previous = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Matrix previousMatrix = new Matrix();
        private Bitmap previousImage;
        private long fadeStart;
        private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final LiquidGlass.Border border = new LiquidGlass.Border();
        /** 液态玻璃 2：按钮只画在自身范围内（见 {@link ClearGlass#ClearGlass}）。 */
        private final ClearGlass clearGlass = new ClearGlass(false);
        private final Matrix matrix = new Matrix();
        private final RectF rect = new RectF();
        private final RectF tmp = new RectF();
        private final RectF onScreen = new RectF(), rendered = new RectF();
        private final int[] at = new int[2];
        /** 源图：磨砂是重度模糊图，液态是轻度模糊图。 */
        private Bitmap image;
        /** 液态：两张轮流用的玻璃图（换图时一张淡出、一张淡入），以及当前这张是从哪张源图算的。 */
        private final Bitmap[] glass = new Bitmap[2];
        private final BitmapShader[] glassShader = new BitmapShader[2];
        private int glassAt;
        private Bitmap renderedFrom;
        private boolean frosted;

        Pill(View host) {
            this.host = host;
            dp = host.getResources().getDisplayMetrics().density;
            css = LiquidGlass.css(host.getContext());
        }

        void set(Bitmap image, boolean frosted) {
            boolean fade = image != this.image && this.image != null && image != null && frosted == this.frosted;
            if (fade && frosted && picture.getShader() != null) {
                // 磨砂换了一张图：旧的留着在下面淡出，和背景的交叉淡入同步。
                previousImage = this.image;
                previous.setShader(picture.getShader());
                previous.setColorFilter(FROSTED_PILL);
                fadeStart = android.os.SystemClock.uptimeMillis();
            } else if (fade && glass[glassAt] != null && renderedFrom != null) {
                // 液态换了一张图：当前的玻璃图留着淡出，新的画到另一张上。
                previousImage = glass[glassAt];
                previous.setShader(glassShader[glassAt]);
                previous.setColorFilter(null);
                glassAt = 1 - glassAt;
                fadeStart = android.os.SystemClock.uptimeMillis();
            } else {
                previousImage = null;
            }
            this.image = image;
            this.frosted = frosted;
            renderedFrom = null;
            if (frosted) {
                picture.setShader(image == null ? null : new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                picture.setColorFilter(FROSTED_PILL);
            } else {
                picture.setColorFilter(null);
            }
            plain.setColor(frosted ? 0x8C5A5652 : 0x40FFFFFF);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            rect.set(getBounds());
            float r = rect.height() / 2f;
            if (FilmGlass.clear()) {
                // 液态玻璃 2：玻璃透明，背后的画面原样透出，只画边缘的光影。
                clearGlass.draw(c, rect, r, dp);
                return;
            }
            if (FilmGlass.active()) {
                // 视频背景：玻璃由视频层每帧实时画在按钮正后方（位置由 TopPills 每帧登记），这里只画边缘。
            } else if (image != null && host.isAttachedToWindow() && (!frosted || picture.getShader() != null)) {
                host.getLocationOnScreen(at);
                float f = fade();
                if (frosted) {
                    // 位图按铺满屏幕的比例放大，再按按钮在屏幕上的位置平移，取到的正是按钮正后方那一块。
                    place(picture, image);
                    if (f < 1f && previousImage != null && previous.getShader() != null) place(previous, previousImage);
                } else {
                    liquid();
                    stretch(picture);
                    if (f < 1f && previousImage != null) stretch(previous);
                }
                if (f < 1f && previousImage != null && previous.getShader() != null) {
                    c.drawRoundRect(rect, r, r, previous);
                    picture.setAlpha(Math.round(255 * f));
                    invalidateSelf();
                } else {
                    picture.setAlpha(255);
                    previousImage = null;
                }
                c.drawRoundRect(rect, r, r, picture);
            } else {
                c.drawRoundRect(rect, r, r, plain);
            }
            drawRim(c, rect, r, frosted, dp, rim, tmp, border);
        }

        /** 磨砂：源图铺满屏幕时，按钮正后方那一块。 */
        private void place(Paint p, Bitmap source) {
            float k = host.getRootView().getWidth() / (float) source.getWidth();
            Matrix m = p == picture ? matrix : previousMatrix;
            m.setScale(k, k);
            m.postTranslate(-at[0], -at[1]);
            p.getShader().setLocalMatrix(m);
        }

        /** 液态：按钮在屏幕上的位置或源图变了，就重新算这块玻璃。 */
        private void liquid() {
            onScreen.set(rect);
            onScreen.offset(at[0], at[1]);
            if (renderedFrom == image && onScreen.equals(rendered) && glass[glassAt] != null) return;
            if (glass[glassAt] == null) {
                glass[glassAt] = Bitmap.createBitmap(LIQUID_SIZE, LIQUID_SIZE, Bitmap.Config.ARGB_8888);
                glassShader[glassAt] = new BitmapShader(glass[glassAt], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            float k = host.getRootView().getWidth() / (float) image.getWidth();
            if (LiquidGlass.render(image, k, onScreen, css, LIQUID_PILL_LOOK, glass[glassAt])) {
                renderedFrom = image;
                rendered.set(onScreen);
            }
            picture.setShader(glassShader[glassAt]);
        }

        /** 液态：玻璃图拉伸到按钮的大小。 */
        private void stretch(Paint p) {
            Matrix m = p == picture ? matrix : previousMatrix;
            m.setScale(rect.width() / LIQUID_SIZE, rect.height() / LIQUID_SIZE);
            m.postTranslate(rect.left, rect.top);
            p.getShader().setLocalMatrix(m);
        }

        /** 交叉淡入进度，0 到 1。 */
        private float fade() {
            long t = android.os.SystemClock.uptimeMillis() - fadeStart;
            return Math.min(1f, Math.max(0f, t / (float) FADE_MS));
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
