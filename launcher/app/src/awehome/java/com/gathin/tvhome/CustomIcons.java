package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * AweHome：用户自己从手机页面（「上传墙纸与图标」）传来的图标图片，同 Aerial 的 CustomIcons。
 * 以 16:9 存在 files/icons/&lt;package&gt;.png，像应用的横幅一样铺满图标。
 */
final class CustomIcons {
    private static final String TAG = "TvHome.CustomIcons";
    private static final int W = 640, H = 360;

    private CustomIcons() {
    }

    static File file(Context ctx, String pkg) {
        return new File(Backgrounds.dir(ctx, "icons"), pkg.replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
    }

    static boolean has(Context ctx, String pkg) {
        return file(ctx, pkg).isFile();
    }

    /** 用户为这个应用图标设的图片，没有时为 null。 */
    static Drawable drawable(Context ctx, String pkg) {
        File f = file(ctx, pkg);
        if (!f.isFile()) return null;
        Bitmap b = BitmapFactory.decodeFile(f.getPath());
        return b == null ? null : new BitmapDrawable(ctx.getResources(), b);
    }

    /** 把传来的图片从中间裁成图标的 16:9 并保存。不在主线程上调用。 */
    static boolean save(Context ctx, String pkg, File upload) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(upload.getPath(), bounds);
            if (bounds.outWidth <= 0) return false;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = Math.max(1, Math.min(bounds.outWidth / W, bounds.outHeight / H));
            Bitmap src = BitmapFactory.decodeFile(upload.getPath(), o);
            if (src == null) return false;
            float want = W / (float) H, has = src.getWidth() / (float) src.getHeight();
            Rect from;
            if (has > want) {
                int w = Math.round(src.getHeight() * want);
                from = new Rect((src.getWidth() - w) / 2, 0, (src.getWidth() + w) / 2, src.getHeight());
            } else {
                int h = Math.round(src.getWidth() / want);
                from = new Rect(0, (src.getHeight() - h) / 2, src.getWidth(), (src.getHeight() + h) / 2);
            }
            Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, from, new Rect(0, 0, W, H), new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            try (OutputStream os = new FileOutputStream(file(ctx, pkg))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            out.recycle();
            return true;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not keep an icon for " + pkg + ": " + e);
            return false;
        }
    }

    static boolean clear(Context ctx, String pkg) {
        return file(ctx, pkg).delete();
    }
}
