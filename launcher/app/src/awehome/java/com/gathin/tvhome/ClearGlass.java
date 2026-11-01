package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.MaskFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AweHome：「液态玻璃 2」，照纯 CSS 液态玻璃模板（yzrt「纯CSS液态玻璃」的 .box）做的玻璃。
 * <p>
 * 模板里外层容器有 {@code filter: contrast(3)}，浏览器把它当作 backdrop-filter 的「背景根」，所以玻璃那句
 * {@code backdrop-filter: blur(2px)} 取不到页面背景：玻璃本身完全透明，背后的画面原样透出，没有模糊、没有折射。
 * 效果全在边缘的光影上，这里按模板的绘制顺序、原样的数值（网页 1px 按 1dp）画出来：
 * <ol>
 * <li>玻璃外的投影 {@code 0 4px 8px rgba(0,0,0,0.2)}（只在 {@code outside} 时画，见构造函数）；</li>
 * <li>底色 {@code rgba(255,255,255,0.04)}；</li>
 * <li>内阴影：四边 0.9 白的锐利亮边（{@code inset ±2px ∓2px 1px -1px}），0.55 白的次亮边（{@code ±6px ∓6px 1px -6px}），
 * 贴边一圈 2px 的暗边（{@code inset 0 0 2px rgba(0,0,0,0.8)}）；</li>
 * <li>边框 {@code 1px rgba(51,51,51,0.08)}；</li>
 * <li>::before：比玻璃小 16、下移到 35% 处的一圈 1px 黑线，模糊 8px，在玻璃下半部形成厚度阴影；</li>
 * <li>.circle-overlay：向内 4.5 的一圈 0.2 白细环，模糊 1px；</li>
 * <li>::after：45° 渐变的 0.8 白高光（左下、右上两角，中间透明），模糊 3px。</li>
 * </ol>
 * 模板 .box 上的 {@code brightness(0.9)} 与容器的 {@code contrast(3)} 作用在这些纯白、纯黑的层上，结果仍是纯白、纯黑，
 * 所以不必另算。各层都带模糊，于是按尺寸在软件画布上画成一张图（BlurMaskFilter），缓存起来，尺寸不变就直接贴。
 */
final class ClearGlass {
    /** 按钮伸缩时尺寸一直在变，多缓存几种；底板、面板只有一两种尺寸。 */
    private static final int MAX_CACHED = 24;

    /** 一张画好的光影图，以及它相对玻璃左上角的偏移（四周留出画投影、暗弧的边）。 */
    private static final class Art {
        final Bitmap bitmap;
        final float left, top;

        Art(Bitmap bitmap, float left, float top) {
            this.bitmap = bitmap;
            this.left = left;
            this.top = top;
        }
    }

    private final boolean outside;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Map<String, Art> cache = new LinkedHashMap<String, Art>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Art> eldest) {
            if (size() <= MAX_CACHED) return false;
            eldest.getValue().bitmap.recycle();
            return true;
        }
    };

    /**
     * @param outside 是否画到玻璃外面（投影、底部暗弧）。右上角按钮是一个个小视图，伸缩时画到自身外面的部分在局部刷新下
     *                会留下残影，所以按钮传 false，只画在按钮范围内；程序坞底板画在网格自己的画布上，传 true。
     */
    ClearGlass(boolean outside) {
        this.outside = outside;
    }

    /** 在 {@code rect}（与 {@code c} 同一坐标系）上画这块玻璃的光影；{@code dp} 为屏幕密度。 */
    void draw(Canvas c, RectF rect, float radius, float dp) {
        int w = Math.round(rect.width()), h = Math.round(rect.height());
        if (w <= 0 || h <= 0) return;
        String key = w + "x" + h + "r" + Math.round(radius * 10) + "d" + dp;
        Art art = cache.get(key);
        if (art == null) {
            art = make(w, h, radius, dp);
            if (art == null) return;
            cache.put(key, art);
        }
        c.drawBitmap(art.bitmap, rect.left - art.left, rect.top - art.top, blit);
    }

    private Art make(int w, int h, float radius, float dp) {
        // 四周留出的边：投影（下移 4、sigma 4）和 ::before 暗弧（sigma 8，下沿在 1.35h − 16）。
        float side = outside ? 26 * dp : 0, top = outside ? 26 * dp : 0;
        float bottom = outside ? Math.max(0.35f * h - 16 * dp, 0) + 26 * dp : 0;
        Bitmap b;
        try {
            b = Bitmap.createBitmap(Math.round(w + 2 * side), Math.round(h + top + bottom), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
        Canvas c = new Canvas(b);
        c.translate(side, top);
        RectF box = new RectF(0, 0, w, h);
        float r = Math.min(radius, Math.min(w, h) / 2f);
        Path boxPath = new Path();
        boxPath.addRoundRect(box, r, r, Path.Direction.CW);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (outside) {
            // 0 4px 8px rgba(0,0,0,0.2)：玻璃下方的投影。CSS 的外投影只画在玻璃外面，玻璃里面挖掉。
            p.setColor(0x33000000);
            p.setMaskFilter(blur(4 * dp));
            RectF drop = new RectF(box);
            drop.offset(0, 4 * dp);
            c.drawRoundRect(drop, r, r, p);
            p.setMaskFilter(null);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            c.drawPath(boxPath, p);
            p.setXfermode(null);
        }
        // 底色 rgba(255,255,255,0.04)。
        p.setColor(0x0AFFFFFF);
        c.drawPath(boxPath, p);

        // 内阴影：列在前面的画在上面，所以倒着画。
        int save = c.save();
        c.clipPath(boxPath);
        inset(c, box, r, 0, 0, 2, 0, 0xCC000000, dp, p);
        inset(c, box, r, -6, 6, 1, -6, 0x8CFFFFFF, dp, p);
        inset(c, box, r, 6, -6, 1, -6, 0x8CFFFFFF, dp, p);
        inset(c, box, r, -2, 2, 1, -1, 0xE6FFFFFF, dp, p);
        inset(c, box, r, 2, -2, 1, -1, 0xE6FFFFFF, dp, p);
        c.restoreToCount(save);
        p.setMaskFilter(null);

        // border: 1px rgba(51,51,51,0.08)，画在玻璃外框以内。
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp);
        p.setColor(0x14333333);
        stroke(c, box, r, dp, p);

        if (!outside) c.clipRect(box);
        // ::before：宽高各小 16、上边在 35% 处的一圈 1px 黑线（rgba(0,0,0,0.9)），模糊 8px。
        RectF before = new RectF(8 * dp, 0.35f * h, w - 8 * dp, 0.35f * h + h - 16 * dp);
        if (before.width() > 0 && before.height() > 0) {
            p.setColor(0xE6000000);
            p.setMaskFilter(blur(8 * dp));
            stroke(c, before, Math.min(r, Math.min(before.width(), before.height()) / 2f), dp, p);
        }
        // .circle-overlay：宽高各小 9、居中的一圈 1px rgba(255,255,255,0.2)，模糊 1px。
        RectF ring = new RectF(box);
        ring.inset(4.5f * dp, 4.5f * dp);
        if (ring.width() > 0 && ring.height() > 0) {
            p.setColor(0x33FFFFFF);
            p.setMaskFilter(blur(dp));
            stroke(c, ring, Math.min(r, Math.min(ring.width(), ring.height()) / 2f), dp, p);
        }
        // ::after：linear-gradient(45deg, rgba(255,255,255,0.8) 0%, transparent tr, transparent 100% − tr,
        // rgba(255,255,255,0.8) 100%)，模糊 3px。tr：圆形 25%，长条（模板里的长按钮）15%。
        float tr = w > 1.5f * h ? 0.15f : 0.25f;
        float half = (w + h) / 4f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        p.setShader(new LinearGradient(w / 2f - half, h / 2f + half, w / 2f + half, h / 2f - half,
                new int[]{0xCCFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0xCCFFFFFF}, new float[]{0f, tr, 1f - tr, 1f},
                Shader.TileMode.CLAMP));
        p.setMaskFilter(blur(3 * dp));
        c.drawRoundRect(box, r, r, p);
        return new Art(b, side, top);
    }

    /**
     * CSS 的内阴影：「洞」是玻璃外框按 spread 缩进（负数为放大）、再按 (dx, dy) 平移后的圆角矩形，洞外面填上颜色并模糊，
     * 只留在玻璃里面（调用方已裁剪）。数值都是网页像素，blur 为模糊半径（sigma = blur / 2）。
     */
    private static void inset(Canvas c, RectF box, float r, float dx, float dy, float blur, float spread, int color,
                              float dp, Paint p) {
        RectF hole = new RectF(box);
        hole.inset(spread * dp, spread * dp);
        hole.offset(dx * dp, dy * dp);
        float hr = Math.max(0f, r - spread * dp);
        RectF big = new RectF(box);
        float out = (Math.abs(dx) + Math.abs(dy) + Math.abs(spread) + 3 * blur + 4) * dp;
        big.inset(-out, -out);
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(big, Path.Direction.CW);
        path.addRoundRect(hole, hr, hr, Path.Direction.CW);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setMaskFilter(blur > 0 ? blur(blur / 2f * dp) : null);
        c.drawPath(path, p);
    }

    /** CSS 的 border 画在元素框以内：描边居中，所以向内缩半个线宽。 */
    private static void stroke(Canvas c, RectF rect, float r, float dp, Paint p) {
        RectF in = new RectF(rect);
        in.inset(dp / 2f, dp / 2f);
        float rr = Math.max(0f, r - dp / 2f);
        c.drawRoundRect(in, rr, rr, p);
    }

    /** 按高斯的 sigma（像素）给出 BlurMaskFilter；Skia 把半径换算成 sigma = 0.57735 × 半径 + 0.5。太小时不模糊。 */
    private static MaskFilter blur(float sigma) {
        float radius = (sigma - 0.5f) / 0.57735f;
        return radius < 0.5f ? null : new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL);
    }
}
