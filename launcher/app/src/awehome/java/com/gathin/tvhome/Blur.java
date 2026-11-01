package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * AweHome：玻璃面板、网格和文件夹背后的模糊。Android 9 的电视没有 RenderEffect，所以先把画面缩到几百像素宽，
 * 再用软件模糊（stack blur，与各种 BlurView 库的做法相同）：只花几毫秒和一张小纹理，拉伸回全屏后看起来就是真正的模糊。
 */
final class Blur {
    /** 快照每边比屏幕小这么多倍。 */
    static final int SCALE = 8;

    private Blur() {
    }

    /**
     * {@code view} 所画内容的缩小、模糊副本；视图还没有尺寸时返回 null。{@code saturation} 为 1 时保持原色，
     * 大于 1 时更浓（液态玻璃），小于 1 时变淡。
     */
    static Bitmap snapshot(View view, int radius, float saturation) {
        return snapshot(view, null, radius, saturation);
    }

    /**
     * AweHome：给了 {@code under} 时先把它铺满整个画布再画界面：用于影片画面（{@link AerialFilm#frame}），
     * 因为 TextureView 在这种软件画布上什么都画不出来。
     */
    static Bitmap snapshot(View view, Bitmap under, int radius, float saturation) {
        int w = view.getWidth() / SCALE, h = view.getHeight() / SCALE;
        if (w <= 0 || h <= 0) return null;
        try {
            Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            c.drawColor(Color.BLACK);
            if (under != null) c.drawBitmap(under, null, new android.graphics.Rect(0, 0, w, h), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            c.scale(1f / SCALE, 1f / SCALE);
            // 在软件画布上，不透明的 TextureView 会用一张全黑的绘制缓存盖住垫在下面的画面：
            // 快照期间先把它设为不可见（下一帧屏幕显示不受影响）。
            List<View> films = new ArrayList<>();
            if (under != null && view instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) view;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View child = g.getChildAt(i);
                    if (child instanceof TextureView && child.getVisibility() == View.VISIBLE) films.add(child);
                }
            }
            for (View f : films) f.setVisibility(View.INVISIBLE);
            try {
                view.draw(c);
            } finally {
                for (View f : films) f.setVisibility(View.VISIBLE);
            }
            return finish(small, radius, saturation);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * AweHome：只有界面、不含视频的透明截图（屏幕的 1/{@link #SCALE}），模糊 {@code radius}。面板的玻璃由视频层
     * 实时画进面板自己的画面时（{@link FilmGlass.Panel}），首页上压在面板下面的界面（程序坞、地名文字等）用它叠上去。
     * 透明处按预乘透明度模糊，界面边缘不会晕出黑边。视图还没有尺寸时返回 null。
     */
    static Bitmap interfaceSnapshot(View view, int radius) {
        int w = view.getWidth() / SCALE, h = view.getHeight() / SCALE;
        if (w <= 0 || h <= 0) return null;
        try {
            Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            c.scale(1f / SCALE, 1f / SCALE);
            List<View> films = new ArrayList<>();
            if (view instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) view;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View child = g.getChildAt(i);
                    if (child instanceof TextureView && child.getVisibility() == View.VISIBLE) films.add(child);
                }
            }
            for (View f : films) f.setVisibility(View.INVISIBLE);
            try {
                view.draw(c);
            } finally {
                for (View f : films) f.setVisibility(View.VISIBLE);
            }
            if (radius > 0) boxPremultiplied(small, radius);
            return small;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** 带透明度的模糊：先按透明度预乘，四个通道各做两遍方框模糊（近似高斯），再还原。 */
    private static void boxPremultiplied(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int n = w * h;
        int[] px = new int[n];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        int[][] ch = new int[4][n];
        for (int i = 0; i < n; i++) {
            int a = px[i] >>> 24;
            ch[0][i] = a;
            ch[1][i] = ((px[i] >> 16) & 0xFF) * a / 255;
            ch[2][i] = ((px[i] >> 8) & 0xFF) * a / 255;
            ch[3][i] = (px[i] & 0xFF) * a / 255;
        }
        int[] tmp = new int[n];
        for (int[] c : ch) {
            for (int pass = 0; pass < 2; pass++) {
                box(c, tmp, w, h, radius, true);
                box(tmp, c, w, h, radius, false);
            }
        }
        for (int i = 0; i < n; i++) {
            int a = ch[0][i];
            if (a <= 0) {
                px[i] = 0;
                continue;
            }
            int r = Math.min(255, ch[1][i] * 255 / a), g = Math.min(255, ch[2][i] * 255 / a), b = Math.min(255, ch[3][i] * 255 / a);
            px[i] = Math.min(255, a) << 24 | r << 16 | g << 8 | b;
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** 一遍方框模糊：{@code rows} 为 true 时沿每行，否则沿每列；超出边缘的取边缘值。 */
    private static void box(int[] in, int[] out, int w, int h, int radius, boolean rows) {
        int lines = rows ? h : w, length = rows ? w : h, div = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sum = 0;
            for (int i = -radius; i <= radius; i++) sum += in[at(line, Math.min(length - 1, Math.max(i, 0)), w, rows)];
            for (int i = 0; i < length; i++) {
                out[at(line, i, w, rows)] = sum / div;
                sum += in[at(line, Math.min(length - 1, i + radius + 1), w, rows)]
                        - in[at(line, Math.max(0, i - radius), w, rows)];
            }
        }
    }

    private static int at(int line, int i, int w, boolean rows) {
        return rows ? line * w + i : i * w + line;
    }

    /** 一张图片缩小并模糊后的副本，宽约 {@code width} 像素。 */
    static Bitmap of(Bitmap source, int width, int radius, float saturation) {
        if (source == null || source.getWidth() <= 0) return null;
        try {
            Bitmap soft = source.getConfig() == Bitmap.Config.HARDWARE ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            if (soft == null) return null;
            int h = Math.max(1, Math.round(width * soft.getHeight() / (float) soft.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(soft, width, h, true);
            if (soft != source && soft != small) soft.recycle();
            if (small == source) small = source.copy(Bitmap.Config.ARGB_8888, true);
            else if (!small.isMutable() || small.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap copy = small.copy(Bitmap.Config.ARGB_8888, true);
                small.recycle();
                small = copy;
            }
            return finish(small, radius, saturation);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap finish(Bitmap small, int radius, float saturation) {
        if (saturation != 1f) {
            Bitmap tinted = Bitmap.createBitmap(small.getWidth(), small.getHeight(), Bitmap.Config.ARGB_8888);
            ColorMatrix m = new ColorMatrix();
            m.setSaturation(saturation);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(new ColorMatrixColorFilter(m));
            new Canvas(tinted).drawBitmap(small, 0, 0, p);
            small.recycle();
            small = tinted;
        }
        if (radius > 0) stack(small, radius);
        return small;
    }

    /**
     * Mario Klingemann 的 stack blur，直接在 ARGB_8888 位图上处理：开销很小，效果接近高斯模糊。
     * 先处理各行，再处理各列。
     */
    static void stack(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        if (radius < 1 || w < 2 || h < 2) return;
        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);
        blurPass(pix, w, h, radius, true);
        blurPass(pix, w, h, radius, false);
        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
    }

    private static void blurPass(int[] pix, int w, int h, int radius, boolean rows) {
        int lines = rows ? h : w, length = rows ? w : h;
        int div = radius * 2 + 1;
        int[] r = new int[length], g = new int[length], b = new int[length];
        int[][] stack = new int[div][3];
        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        for (int line = 0; line < lines; line++) {
            int rsum = 0, gsum = 0, bsum = 0, rin = 0, gin = 0, bin = 0, rout = 0, gout = 0, bout = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = pix[index(line, Math.min(length - 1, Math.max(i, 0)), w, rows)];
                int[] sir = stack[i + radius];
                sir[0] = (p >> 16) & 0xff;
                sir[1] = (p >> 8) & 0xff;
                sir[2] = p & 0xff;
                int rbs = radius + 1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rin += sir[0];
                    gin += sir[1];
                    bin += sir[2];
                } else {
                    rout += sir[0];
                    gout += sir[1];
                    bout += sir[2];
                }
            }
            int stackpointer = radius;
            for (int x = 0; x < length; x++) {
                r[x] = rsum / divsum;
                g[x] = gsum / divsum;
                b[x] = bsum / divsum;
                rsum -= rout;
                gsum -= gout;
                bsum -= bout;
                int stackstart = stackpointer - radius + div;
                int[] sir = stack[stackstart % div];
                rout -= sir[0];
                gout -= sir[1];
                bout -= sir[2];
                int p = pix[index(line, Math.min(x + radius + 1, length - 1), w, rows)];
                sir[0] = (p >> 16) & 0xff;
                sir[1] = (p >> 8) & 0xff;
                sir[2] = p & 0xff;
                rin += sir[0];
                gin += sir[1];
                bin += sir[2];
                rsum += rin;
                gsum += gin;
                bsum += bin;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];
                rout += sir[0];
                gout += sir[1];
                bout += sir[2];
                rin -= sir[0];
                gin -= sir[1];
                bin -= sir[2];
            }
            for (int x = 0; x < length; x++) {
                pix[index(line, x, w, rows)] = 0xff000000 | (clamp(r[x]) << 16) | (clamp(g[x]) << 8) | clamp(b[x]);
            }
        }
    }

    private static int index(int line, int at, int w, boolean rows) {
        return rows ? line * w + at : at * w + line;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    /**
     * 按钮在这张图上取的颜色：这块区域（用图片的比例表示）的平均色，亮度压在中间，白色图标放上去仍看得清。
     * 没有图片时为 0。
     */
    static int tint(Bitmap source, float left, float top, float right, float bottom) {
        if (source == null) return 0;
        try {
            Bitmap soft = source.getConfig() == Bitmap.Config.HARDWARE ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            if (soft == null) return 0;
            int x0 = Math.round(left * soft.getWidth()), x1 = Math.round(right * soft.getWidth());
            int y0 = Math.round(top * soft.getHeight()), y1 = Math.round(bottom * soft.getHeight());
            long rs = 0, gs = 0, bs = 0, n = 0;
            int stepX = Math.max(1, (x1 - x0) / 24), stepY = Math.max(1, (y1 - y0) / 12);
            for (int y = y0; y < y1; y += stepY) {
                for (int x = x0; x < x1; x += stepX) {
                    int p = soft.getPixel(Math.min(x, soft.getWidth() - 1), Math.min(y, soft.getHeight() - 1));
                    rs += Color.red(p);
                    gs += Color.green(p);
                    bs += Color.blue(p);
                    n++;
                }
            }
            if (soft != source) soft.recycle();
            if (n == 0) return 0;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (rs / n), (int) (gs / n), (int) (bs / n), hsv);
            hsv[1] = Math.min(0.75f, hsv[1] * 1.25f);
            hsv[2] = Math.max(0.32f, Math.min(0.62f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 图片里最鲜艳的颜色，给 Wi-Fi 按钮作强调色；没有值得用的颜色时为 0。 */
    static int vivid(Bitmap source) {
        if (source == null) return 0;
        try {
            Bitmap soft = source.getConfig() == Bitmap.Config.HARDWARE ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            if (soft == null) return 0;
            float best = 0f;
            int bestColor = 0;
            float[] hsv = new float[3];
            int stepX = Math.max(1, soft.getWidth() / 32), stepY = Math.max(1, soft.getHeight() / 18);
            for (int y = 0; y < soft.getHeight(); y += stepY) {
                for (int x = 0; x < soft.getWidth(); x += stepX) {
                    Color.colorToHSV(soft.getPixel(x, y), hsv);
                    float score = hsv[1] * hsv[2];
                    if (score > best) {
                        best = score;
                        bestColor = soft.getPixel(x, y);
                    }
                }
            }
            if (soft != source) soft.recycle();
            if (best < 0.35f) return 0;
            Color.colorToHSV(bestColor, hsv);
            hsv[2] = Math.min(hsv[2], 0.85f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
