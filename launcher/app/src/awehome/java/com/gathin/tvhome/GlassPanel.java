package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

/**
 * AweHome：偏好面板、长按菜单、信号源列表和命名对话框用的玻璃。在面板所在的位置画出背后模糊的屏幕，
 * 再按「模糊效果」画玻璃：
 * <ul>
 * <li>磨砂玻璃：重度模糊，颜色压暗、发灰，上方一层淡淡的白光，任何电视都画得动；</li>
 * <li>液态玻璃：liquid-glass-react 的做法（{@link LiquidGlass}）：轻度模糊、饱和度 140%，按位移贴图向外取样，
 * 边缘把面板外的画面「吸」进来、带一点色散，边框是一圈按 135° 渐变的细亮边；</li>
 * <li>液态玻璃 2：纯 CSS 模板的清透玻璃（{@link ClearGlass}），面板下垫一层轻度模糊。</li>
 * </ul>
 * 背后的画面有两种来源：
 * <ul>
 * <li>背景是图片（或影片还没显示）：{@link #setBackdrop} 给的是整屏的模糊截图，按面板在屏幕上的位置取那一块；</li>
 * <li>背景是正在播放的影片（{@link FilmGlass#active}）：面板里放一个 TextureView，视频渲染器每画一帧视频，就把这块
 * 玻璃直接画进来（{@link FilmGlass.Panel}），所以面板跟着画面实时变化；{@link #setBackdrop} 给的是只有界面、
 * 不含视频的透明截图（{@link Blur#interfaceSnapshot}），叠在玻璃上，把首页上压在面板下面的界面也模糊着透出来。</li>
 * </ul>
 */
public class GlassPanel extends FrameLayout implements TextureView.SurfaceTextureListener {
    /** 液态玻璃按面板的 1/4 计算：画面本来就是模糊的，面板又大，这样一次只算两万多个点（电视上约 20 毫秒）。 */
    private static final float LIQUID_SCALE = 0.25f;
    private final float density, css;
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LiquidGlass.Border border = new LiquidGlass.Border();
    /** 液态玻璃 2 的边缘光影（{@link ClearGlass}），画在面板范围内。 */
    private final ClearGlass clearGlass = new ClearGlass(false);
    /** 液态玻璃 2 垫在下面的快照（不做位移，只调颜色）。 */
    private final Paint clearPicture = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    /** 实时模式：叠在玻璃上的界面截图。 */
    private final Paint interfacePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF edgeRect = new RectF(), onScreen = new RectF(), rendered = new RectF();
    private final int[] at = new int[2];
    /** 实时模式下由视频渲染器画玻璃的画面。 */
    private final TextureView film;
    private FilmGlass.Panel panel;
    private Bitmap backdrop;
    /** 液态：按面板在屏幕上的位置算好的玻璃图，以及它是从哪张快照算的。 */
    private Bitmap glass;
    private BitmapShader glassShader;
    private Bitmap renderedFrom;
    private boolean liquid, live;
    private float radius;
    private int shaderW = -1, shaderH = -1;

    public GlassPanel(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        css = LiquidGlass.css(context);
        radius = 22 * density;
        setWillNotDraw(false);
        rim.setStyle(Paint.Style.STROKE);
        setClipToPadding(false);
        film = new TextureView(context);
        film.setFocusable(false);
        film.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        film.setSurfaceTextureListener(this);
        film.setVisibility(View.GONE);
        // 圆角外由渲染器画成透明（FilmRenderer.drawPanels），所以不能当不透明的画面合成。
        film.setOpaque(false);
        // 0×0 加入：它不参与面板的尺寸（MATCH_PARENT 会在按内容定高的下拉菜单里把面板撑到可用空间的最大高度），
        // 量好面板之后再按面板的大小量它（onMeasure）。
        addView(film, 0, new LayoutParams(0, 0));
    }

    /**
     * 背后的画面：影片正在播放时（{@link FilmGlass#active}）是只有界面的透明截图，玻璃由视频渲染器实时画；
     * 否则是整屏的模糊截图（屏幕的 1/8，{@link Blur#SCALE}）。{@code liquid} 为 false 时是磨砂玻璃。
     */
    void setBackdrop(Bitmap blurred, boolean liquid) {
        backdrop = blurred;
        this.liquid = liquid;
        renderedFrom = null;
        live = FilmGlass.active();
        film.setVisibility(live ? View.VISIBLE : View.GONE);
        if (blurred != null && !liquid) {
            picture.setShader(new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }
        clearPicture.setShader(blurred == null ? null
                : new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        clearPicture.setColorFilter(GlassLook.CLEAR_PANEL);
        interfacePaint.setShader(blurred == null ? null
                : new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        interfacePaint.setColorFilter(lookFilter());
        // AweHome：磨砂按视频实测压暗、去饱和（GlassLook）；液态的颜色变换在 LiquidGlass.render 里算好。
        picture.setColorFilter(liquid ? null : GlassLook.FROSTED_PANEL);
        if (getWidth() > 0) makeShaders(getWidth(), getHeight());
        updatePanel();
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        if (getWidth() > 0) makeShaders(getWidth(), getHeight());
        updatePanel();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        film.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    /** 玻璃按尺寸生成的渐变：每种尺寸只生成一次，不在每一帧都重建。 */
    private void makeShaders(int w, int h) {
        rect.set(0, 0, w, h);
        shaderW = w;
        shaderH = h;
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        rim.setStrokeWidth(density);
        rim.setColor(0x33FFFFFF);
        sheen.setShader(new LinearGradient(0, 0, 0, h * 0.4f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        makeShaders(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updatePanel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (shaderW != w || shaderH != h) makeShaders(w, h);
        if (live) {
            // 影片的第一帧画进来之前先垫一层深色玻璃（裁成圆角）；玻璃、界面截图和边缘都画在 TextureView 上面（drawChild）。
            int save = canvas.save();
            canvas.clipPath(clip);
            canvas.drawColor(0x8C2C2C30);
            canvas.restoreToCount(save);
            return;
        }
        int save = canvas.save();
        canvas.clipPath(clip);
        getLocationOnScreen(at);
        if (backdrop != null && liquid && FilmGlass.clear()) {
            // 液态玻璃 2：模板的玻璃是透明的，可面板上全是文字，所以垫一层轻度模糊的快照，再按模板加 4% 的白、
            // 亮度 0.9（GlassLook.CLEAR_PANEL）。
            place(clearPicture);
            canvas.drawRect(rect, clearPicture);
        } else if (backdrop != null && liquid && liquidGlass()) {
            matrix.setScale(w / (float) glass.getWidth(), h / (float) glass.getHeight());
            glassShader.setLocalMatrix(matrix);
            canvas.drawRect(rect, picture);
        } else if (backdrop != null && !liquid) {
            place(picture);
            canvas.drawRect(rect, picture);
            canvas.drawRect(rect, sheen);
        } else {
            canvas.drawColor(liquid ? 0x8C2C2C30 : 0xE62A2A2E);
        }
        canvas.restoreToCount(save);
        drawEdge(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        boolean more = super.drawChild(canvas, child, drawingTime);
        if (child == film && live) {
            // 实时模式：视频渲染器画的玻璃之上，叠首页界面的模糊截图（同样的颜色变换），再画磨砂的白光和边缘。
            if (backdrop != null) {
                getLocationOnScreen(at);
                place(interfacePaint);
                canvas.drawRoundRect(rect, radius, radius, interfacePaint);
            }
            if (!liquid) canvas.drawRoundRect(rect, radius, radius, sheen);
            drawEdge(canvas);
        }
        return more;
    }

    /** 截图放大到屏幕大小，再按面板在屏幕上的位置平移，取到的正是面板正后方那一块。 */
    private void place(Paint p) {
        float s = getResources().getDisplayMetrics().widthPixels / (float) backdrop.getWidth();
        matrix.setScale(s, s);
        matrix.postTranslate(-at[0], -at[1]);
        p.getShader().setLocalMatrix(matrix);
    }

    private void drawEdge(Canvas canvas) {
        if (liquid && FilmGlass.clear()) {
            clearGlass.draw(canvas, rect, radius, density);
        } else if (liquid) {
            border.draw(canvas, rect, radius, density);
        } else {
            float half = rim.getStrokeWidth() / 2f;
            edgeRect.set(rect);
            edgeRect.inset(half, half);
            canvas.drawRoundRect(edgeRect, radius - half, radius - half, rim);
        }
    }

    /** 当前模式的颜色变换 {gain, sat, offset}，视频渲染器画玻璃时用，界面截图也按它调色。 */
    private float[] look() {
        if (!liquid) return GlassLook.FROSTED_PANEL_LOOK;
        return FilmGlass.clear() ? GlassLook.CLEAR_PANEL_LOOK : GlassLook.LIQUID_PANEL_LOOK;
    }

    private ColorFilter lookFilter() {
        if (!liquid) return GlassLook.FROSTED_PANEL;
        return FilmGlass.clear() ? GlassLook.CLEAR_PANEL : GlassLook.LIQUID_PANEL;
    }

    /** 实时模式：把面板在屏幕上的位置和模式告诉视频渲染器。 */
    private void updatePanel() {
        FilmGlass.Panel p = panel;
        if (p == null) return;
        getLocationOnScreen(at);
        p.left = at[0];
        p.top = at[1];
        p.radius = radius;
        p.look = look();
        p.style = !liquid ? FilmGlass.Panel.FROSTED : FilmGlass.clear() ? FilmGlass.Panel.CLEAR : FilmGlass.Panel.LIQUID;
        FilmGlass.panelChanged();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        panel = new FilmGlass.Panel(texture, width, height);
        updatePanel();
        FilmGlass.addPanel(panel);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        FilmGlass.Panel p = panel;
        if (p == null) return;
        p.width = width;
        p.height = height;
        updatePanel();
    }

    /** 面板关掉或换回截图模式：渲染器先撤掉它在这块画面上的输出，再释放纹理（返回 false，纹理由我们释放）。 */
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        FilmGlass.Panel p = panel;
        panel = null;
        if (p != null) {
            FilmGlass.removePanel(p);
            return false;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    /** 液态：面板的位置、大小或快照变了，就重新算玻璃图；算不出来时返回 false。 */
    private boolean liquidGlass() {
        onScreen.set(at[0], at[1], at[0] + getWidth(), at[1] + getHeight());
        if (renderedFrom == backdrop && onScreen.equals(rendered) && glass != null) return true;
        Bitmap target = LiquidGlass.target(glass, onScreen, LIQUID_SCALE);
        if (target == null) return false;
        if (target != glass) {
            glass = target;
            glassShader = new BitmapShader(target, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        float k = getResources().getDisplayMetrics().widthPixels / (float) backdrop.getWidth();
        if (!LiquidGlass.render(backdrop, k, onScreen, css, GlassLook.LIQUID_PANEL_LOOK, glass)) return false;
        renderedFrom = backdrop;
        rendered.set(onScreen);
        picture.setShader(glassShader);
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // 各行也裁在圆角玻璃之内，选中那一行的卡片不会从角上露出去；实时模式下的 TextureView 也裁成圆角。
        int save = canvas.save();
        if (!clip.isEmpty()) canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
}
