package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;
    /** Light glass like the top bar's pills (config.xml dock_glass): a white tint that fades downwards. */
    private final boolean lightGlass;
    /** Padding all round the tiles equal to the gap between them (config.xml dock_even_padding). */
    private final boolean evenPadding;
    /** The band's top and bottom the glass gradient was made for; it is remade when they move. */
    private float shaderTop = Float.NaN, shaderBottom = Float.NaN;
    /** Draw the band and its edge (config.xml dock_band); false leaves the tiles floating on the wallpaper. */
    private boolean bandVisible;
    /** single_row: the tiles after the dock share its row, so they take the same offsets to stay level with it. */
    private final boolean singleRow;
    /** dock_band_all: in a single row, the band runs behind every app instead of the dock apps only. */
    private final boolean bandAll;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param opacity     the plain band's opacity in percent (dock_opacity), so a wallpaper can show through it.
     * @param lightGlass  a light frosted band like the pills, for a sky or wallpaper behind it.
     * @param evenPadding the band's padding is the gap between the tiles on all four sides (dock_even_padding).
     * @param bandVisible false skips the band and its edge, so the tiles float on the wallpaper (dock_band).
     * @param singleRow   every app sits in the dock's row (single_row); the band covers the dock apps only, unless bandAll.
     * @param bandAll     with singleRow, the band runs behind every app in the row (dock_band_all).
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, int opacity, boolean lightGlass,
                   boolean evenPadding, boolean bandVisible, boolean singleRow, boolean bandAll) {
        this.dockSize = dockSize;
        this.singleRow = singleRow;
        this.bandAll = singleRow && bandAll;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        this.lightGlass = lightGlass;
        this.evenPadding = evenPadding;
        this.bandVisible = bandVisible;
        band.setColor(translucent ? 0x8C121014 : (Math.round(opacity * 2.55f) << 24) | 0x141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(lightGlass ? 0x38FFFFFF : translucent ? 0x26FFFFFF : 0x1FFFFFFF);
    }

    /**
     * AweHome：模糊后的背景（{@link Backdrop#blurred}），画在底板里面，看起来就是盖在图片上的磨砂玻璃；
     * 以及网格在屏幕上的位置，用来对齐。为 null 时保持普通的玻璃。
     */
    private android.graphics.Bitmap frost, blurredImage, clearImage;
    private final Paint frostPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 换背景时上一张模糊图，在新图下面淡出。 */
    private final Paint previousPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix previousMatrix = new android.graphics.Matrix();
    private android.graphics.Bitmap previousImage;
    private long fadeStart;
    private static final String FILM_KEY = "dock";
    private final RectF rimRect = new RectF();
    private final android.graphics.Matrix frostMatrix = new android.graphics.Matrix();
    private final int[] gridOnScreen = new int[2];
    /** AweHome：液态玻璃（{@link LiquidGlass}）的边框，以及按底板大小算好的玻璃图（两张轮流用，换图时交叉淡入）。 */
    private final LiquidGlass.Border border = new LiquidGlass.Border();
    /** AweHome「液态玻璃 2」：透明的玻璃，只画边缘的光影和下方的投影（{@link ClearGlass}）。 */
    private final ClearGlass clearGlass = new ClearGlass(true);
    private final android.graphics.Bitmap[] liquid = new android.graphics.Bitmap[2];
    private final android.graphics.BitmapShader[] liquidShader = new android.graphics.BitmapShader[2];
    private int liquidAt;
    private android.graphics.Bitmap liquidFrom;
    private final RectF liquidRect = new RectF(), onScreen = new RectF();
    /** 液态玻璃按底板的 1/4 计算：画面本来就是模糊的。 */
    private static final float LIQUID_SCALE = 0.25f;

    /** AweHome：重度模糊图（磨砂用）和轻度模糊图（液态用），见 {@link Backdrop#blurred}、{@link Backdrop#clear}。 */
    void setFrost(android.graphics.Bitmap blurred, android.graphics.Bitmap clear) {
        blurredImage = blurred;
        clearImage = clear;
        pickFrost();
    }

    private void pickFrost() {
        boolean frosted = FilmGlass.frosted();
        android.graphics.Bitmap image = frosted || clearImage == null ? blurredImage : clearImage;
        if (image == frost) return;
        if (frost != null && image != null && frostPaint.getShader() != null) {
            // 换了一张图：当前的玻璃留着在下面淡出。液态的玻璃是算好的图，淡出的就是它，新的画到另一张上。
            boolean wasLiquid = liquidFrom != null && liquid[liquidAt] != null;
            previousImage = wasLiquid ? liquid[liquidAt] : frost;
            previousPaint.setShader(frostPaint.getShader());
            previousPaint.setColorFilter(frostPaint.getColorFilter());
            if (wasLiquid) liquidAt = 1 - liquidAt;
            fadeStart = android.os.SystemClock.uptimeMillis();
        }
        frost = image;
        liquidFrom = null;
        frostPaint.setShader(image == null ? null
                : new android.graphics.BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }

    /** 液态：底板在屏幕上的位置或源图变了，就重新算这块玻璃，并让 frostPaint 画它（拉伸到底板）。 */
    private void liquid(RecyclerView parent) {
        onScreen.set(rect);
        onScreen.offset(gridOnScreen[0], gridOnScreen[1]);
        android.graphics.Bitmap out = liquid[liquidAt];
        if (liquidFrom != frost || !onScreen.equals(liquidRect) || out == null) {
            android.graphics.Bitmap target = LiquidGlass.target(out, rect, LIQUID_SCALE);
            if (target == null) return;
            if (target != out) {
                liquid[liquidAt] = target;
                liquidShader[liquidAt] = new android.graphics.BitmapShader(target, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            float k = parent.getRootView().getWidth() / (float) frost.getWidth();
            if (LiquidGlass.render(frost, k, onScreen, LiquidGlass.css(parent.getContext()), GlassLook.LIQUID_DOCK_LOOK, target)) {
                liquidFrom = frost;
                liquidRect.set(onScreen);
            }
        }
        frostPaint.setShader(liquidShader[liquidAt]);
        stretch(liquid[liquidAt], frostMatrix);
        frostPaint.getShader().setLocalMatrix(frostMatrix);
    }

    /** 算好的玻璃图拉伸到底板。 */
    private void stretch(android.graphics.Bitmap glass, android.graphics.Matrix m) {
        m.setScale(rect.width() / glass.getWidth(), rect.height() / glass.getHeight());
        m.postTranslate(rect.left, rect.top);
    }

    /**
     * AweHome「模糊效果」，见 {@link GlassLook}：液态玻璃透出轻度模糊、更鲜艳的背景，边缘一条锐利亮线；
     * 磨砂玻璃透出重度模糊、发灰压暗的背景，边缘一圈柔和光晕。两者都不再叠白色光泽。
     * 用哪种只记在 {@link FilmGlass#frosted} 一处；改了之后调这里，换颜色变换和用哪张模糊图。
     */
    void restyle() {
        // 先换图（旧的玻璃连同它的滤镜一起淡出），再换滤镜：液态的颜色变换已算进玻璃图（LiquidGlass.render）。
        pickFrost();
        frostPaint.setColorFilter(FilmGlass.frosted() ? GlassLook.FROSTED_DOCK : null);
    }

    /** AweHome：「每行图标数」原地改动时，底板跟着新的格数（MainActivity.applyColumns）。 */
    void setDockSize(int n) {
        dockSize = n;
    }

    /** AweHome「程序坞背景」：不重建首页，直接显示或隐藏底板。 */
    void setBandVisible(boolean on) {
        bandVisible = on;
    }

    /** Concentric corners: the band's radius is the tiles' radius plus the band's padding around them. */
    static float radiusPx(float tileRadiusPx, float paddingPx) {
        return tileRadiusPx + paddingPx;
    }

    /**
     * The band's padding on every side for dock_even_padding: the gap between two dock tiles (a cell minus its tile, as
     * AppAdapter.tileWidthPx lays them out), capped by the room the grid leaves at its edges.
     */
    static float evenPaddingPx(int innerWidthPx, int columns, int tileWidthPx, float roomPx) {
        float gap = columns > 0 ? innerWidthPx / (float) columns - tileWidthPx : 0f;
        return Math.max(0f, Math.min(gap, roomPx));
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && (singleRow || pos < dockSize)) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (!bandVisible) {
            FilmGlass.remove(FILM_KEY);
            return;
        }
        boolean any = false;
        int cardWidth = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || (!bandAll && pos >= dockSize)) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            cardWidth = card.getWidth();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) {
            FilmGlass.remove(FILM_KEY);
            return;
        }
        float pad = 14 * density;
        // The band can only draw inside the grid, and the first and last dock tiles sit 12dp in from its
        // edges: a full 14dp at the sides sliced the rounded ends flat (#454). Keep the sides, with the
        // 1dp edge line, inside the grid; the top and bottom have room.
        // 22dp at the sides (the grid's 10dp padding makes room): a focused first or last tile grows about 7dp
        // outwards and still keeps about the band's top margin from its rounded end.
        float sidePad = 22 * density;
        float side = Math.max(0f, Math.min(sidePad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        float radius = AppAdapter.cornerRadius(cardWidth, density);
        if (evenPadding) {
            // The same padding on all four sides, so the band's corners are concentric with the tiles' everywhere.
            int inner = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            // A single row's tiles can run off the screen, so only its top limits the padding; its ends are clamped below.
            float room = (singleRow ? rect.top : Math.min(rect.top, Math.min(rect.left, parent.getWidth() - rect.right))) - density;
            float even = evenPaddingPx(inner, dockSize, cardWidth, room);
            rect.inset(-even, -even);
            radius = radiusPx(radius, even);
        } else {
            // dock_even_padding off: the band as before, with its fixed 26 dp corners.
            rect.inset(singleRow ? -sidePad : -side, -pad);
            radius = 26 * density;
        }
        if (singleRow) {
            // Tiles past the screen's edge: the band stops at the grid's edge with its rounded end instead of being
            // sliced square there.
            rect.left = Math.max(rect.left, density);
            rect.right = Math.min(rect.right, parent.getWidth() - density);
        }
        rect.bottom += namesRoom;
        if (lightGlass && (rect.top != shaderTop || rect.bottom != shaderBottom)) {
            // The pills' glass (pill_bg: white, 18% fading to 8%), a little fainter over this much larger area.
            band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x24FFFFFF, 0x0DFFFFFF,
                    Shader.TileMode.CLAMP));
            shaderTop = rect.top;
            shaderBottom = rect.bottom;
        }
        if (lightGlass && FilmGlass.clear()) {
            // AweHome：液态玻璃 2：玻璃透明，视频层不必画，这里只画光影。
            FilmGlass.remove(FILM_KEY);
            clearGlass.draw(c, rect, radius, density);
            return;
        }
        if (lightGlass && FilmGlass.active()) {
            // AweHome：视频背景：底板里的玻璃由视频层每帧实时画（FilmRenderer），这里登记位置，只画边缘。
            parent.getLocationOnScreen(gridOnScreen);
            // 程序坞淡出淡入时网格的透明度在变，视频层里的玻璃跟着一起变（MainActivity.hideDock）。
            FilmGlass.put(FILM_KEY, gridOnScreen[0] + rect.left, gridOnScreen[1] + rect.top, gridOnScreen[0] + rect.right,
                    gridOnScreen[1] + rect.bottom, radius, FilmGlass.frosted() ? GlassLook.FROSTED_DOCK_LOOK : GlassLook.LIQUID_DOCK_LOOK,
                    parent.getAlpha());
            GlassLook.drawRim(c, rect, radius, FilmGlass.frosted(), density, rimPaint, rimRect, border);
            return;
        }
        if (frost != null && lightGlass) {
            parent.getLocationOnScreen(gridOnScreen);
            if (FilmGlass.frosted()) {
                // 模糊图放大后与屏幕一样大：按网格在屏幕上的位置平移它。
                float k = parent.getRootView().getWidth() / (float) frost.getWidth();
                frostMatrix.setScale(k, k);
                frostMatrix.postTranslate(-gridOnScreen[0], -gridOnScreen[1]);
                frostPaint.getShader().setLocalMatrix(frostMatrix);
            } else {
                liquid(parent);
            }
            // AweHome：换背景时，上一张在下面淡出，新的一张随背景一起淡入。
            float f = Math.min(1f, (android.os.SystemClock.uptimeMillis() - fadeStart) / (float) GlassLook.FADE_MS);
            if (f < 1f && previousPaint.getShader() != null && previousImage != null) {
                if (previousImage == liquid[0] || previousImage == liquid[1]) {
                    stretch(previousImage, previousMatrix);
                } else {
                    float pk = parent.getRootView().getWidth() / (float) previousImage.getWidth();
                    previousMatrix.setScale(pk, pk);
                    previousMatrix.postTranslate(-gridOnScreen[0], -gridOnScreen[1]);
                }
                previousPaint.getShader().setLocalMatrix(previousMatrix);
                c.drawRoundRect(rect, radius, radius, previousPaint);
                frostPaint.setAlpha(Math.round(255 * f));
                parent.postInvalidateOnAnimation();
            } else {
                frostPaint.setAlpha(255);
                previousImage = null;
            }
            c.drawRoundRect(rect, radius, radius, frostPaint);
        }
        if (frost != null && lightGlass) {
            // AweHome：玻璃本身就是透出的背景，边缘按模式画（GlassLook.drawRim）。
            GlassLook.drawRim(c, rect, radius, FilmGlass.frosted(), density, rimPaint, rimRect, border);
            return;
        }
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
