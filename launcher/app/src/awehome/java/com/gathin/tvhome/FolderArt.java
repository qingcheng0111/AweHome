package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * AweHome：文件夹的图标，画法同 tvOS：一块磨砂玻璃，里面以 2×2 的小图标显示前四个应用。
 * 没有横幅的应用在深色卡片上显示它的图标。
 */
final class FolderArt extends Drawable {
    private final List<AppAdapter.Entry> apps;
    private final float density;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private final Path clip = new Path();

    FolderArt(List<AppAdapter.Entry> apps, float density) {
        this.apps = apps;
        this.density = density;
        glass.setColor(0x47FFFFFF);
        card.setColor(0xFF1E1E22);
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(180 * density);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(101 * density);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        canvas.drawRect(b, glass);
        float gap = b.height() * 0.07f;
        float w = (b.width() - gap * 3) / 2f, h = (b.height() - gap * 3) / 2f;
        // 每个小图标在自己的格子里保持 16:9。
        float tw = Math.min(w, h * 16f / 9f), th = tw * 9f / 16f;
        float r = Math.max(2 * density, th * 0.12f);
        for (int i = 0; i < Math.min(4, apps.size()); i++) {
            float cx = b.left + gap + (i % 2) * (w + gap) + (w - tw) / 2f;
            float cy = b.top + gap + (i / 2) * (h + gap) + (h - th) / 2f;
            cell.set(cx, cy, cx + tw, cy + th);
            AppAdapter.Entry e = apps.get(i);
            int save = canvas.save();
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            canvas.clipPath(clip);
            if (e.banner != null) {
                e.banner.setBounds(Math.round(cell.left), Math.round(cell.top), Math.round(cell.right), Math.round(cell.bottom));
                e.banner.draw(canvas);
            } else {
                canvas.drawRect(cell, card);
                if (e.icon != null) {
                    float side = th * 0.62f;
                    e.icon.setBounds(Math.round(cell.centerX() - side / 2), Math.round(cell.centerY() - side / 2),
                            Math.round(cell.centerX() + side / 2), Math.round(cell.centerY() + side / 2));
                    e.icon.draw(canvas);
                }
            }
            canvas.restoreToCount(save);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        glass.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
