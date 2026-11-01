package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AweHome：航拍视频的 OpenGL 渲染器。播放器把画面解码到这里的纹理，每来一帧：
 * <ol>
 * <li>把视频铺满屏幕画出来（比例不同时裁切）；</li>
 * <li>有玻璃要画时（{@link FilmGlass}），把这一帧缩到 1/4 做两遍高斯模糊（液态，轻度），或者再缩到 1/8 做（磨砂，重度）；</li>
 * <li>把模糊后的画面按各块玻璃的圆角矩形画回去：液态按 {@link LiquidGlass} 的位移贴图向外取样、带色散，磨砂直接取正后方；
 * 再做颜色变换。</li>
 * </ol>
 * 所以程序坞和按钮里透出的画面每帧都跟着视频动，模糊全在 GPU 上做，主线程完全不参与。小米 MiTV-MFTP0（Mali-G52）
 * 上，1/4 尺寸的模糊每帧处理约 13 万个像素，1/8 的约 3 万个。GL 初始化失败时返回 null，由 {@link AerialFilm} 退回直接输出。
 */
final class FilmRenderer implements SurfaceTexture.OnFrameAvailableListener {
    interface Ready {
        /** 播放器该输出到的 Surface；null 表示 GL 不可用。在渲染线程上调用。 */
        void onSurface(Surface videoSurface);
    }

    private static final String TAG = "TvHome.FilmGL";
    /** 磨砂的模糊程度：1/8 尺寸下的高斯 sigma（全屏 1920 宽约 36 像素，与视频实测一致）。 */
    private static final float SIGMA_FROSTED = 4.5f;
    private static final int TAPS = 7;

    private static final String VERTEX =
            "attribute vec2 aPos;\n" +
            "uniform vec2 uScale;\n" +
            "uniform vec2 uOffset;\n" +
            "uniform mat4 uTex;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  vUv = (uTex * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;\n" +
            "  gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);\n" +
            "}\n";
    private static final String FRAG_VIDEO =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uSrc;\n" +
            "varying vec2 vUv;\n" +
            "void main() { gl_FragColor = texture2D(uSrc, vUv); }\n";
    /** 缩到 1/4：四个双线性采样各覆盖 2×2，合起来正好平均 4×4 个像素，缩小时不闪烁。 */
    private static final String FRAG_DOWN =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uSrc;\n" +
            "uniform vec2 uStep;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  gl_FragColor = 0.25 * (texture2D(uSrc, vUv + uStep) + texture2D(uSrc, vUv - uStep)\n" +
            "      + texture2D(uSrc, vUv + vec2(uStep.x, -uStep.y)) + texture2D(uSrc, vUv + vec2(-uStep.x, uStep.y)));\n" +
            "}\n";
    private static final String FRAG_COPY =
            "precision mediump float;\n" +
            "uniform sampler2D uSrc;\n" +
            "varying vec2 vUv;\n" +
            "void main() { gl_FragColor = texture2D(uSrc, vUv); }\n";
    private static final String FRAG_BLUR =
            "precision mediump float;\n" +
            "uniform sampler2D uSrc;\n" +
            "uniform vec2 uDir;\n" +
            "uniform float uW[" + TAPS + "];\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  vec4 c = texture2D(uSrc, vUv) * uW[0];\n" +
            "  for (int i = 1; i < " + TAPS + "; i++) {\n" +
            "    vec2 o = uDir * float(i);\n" +
            "    c += (texture2D(uSrc, vUv + o) + texture2D(uSrc, vUv - o)) * uW[i];\n" +
            "  }\n" +
            "  gl_FragColor = c;\n" +
            "}\n";
    /** 一块玻璃：按屏幕位置取模糊图，调饱和度、乘增益、加偏移（同 GlassLook），圆角之外透明。 */
    private static final String FRAG_GLASS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D uSrc;\n" +
            "uniform vec2 uScreen;\n" +
            "uniform vec4 uRect;\n" +
            "uniform float uRadius;\n" +
            "uniform vec3 uLook;\n" +
            "uniform float uAlpha;\n" +
            "uniform float uMax;\n" +
            "uniform vec2 uOrigin;\n" +
            "void main() {\n" +
            "  vec2 p = gl_FragCoord.xy + uOrigin;\n" +
            "  vec3 c = texture2D(uSrc, p / uScreen).rgb;\n" +
            "  float l = dot(c, vec3(0.213, 0.715, 0.072));\n" +
            "  c = clamp(mix(vec3(l), c, uLook.y) * uLook.x + uLook.z, 0.0, 1.0);\n" +
            "  float m = max(c.r, max(c.g, c.b));\n" +
            "  if (m > uMax) c *= uMax / m;\n" +
            "  vec2 center = (uRect.xy + uRect.zw) * 0.5;\n" +
            "  vec2 extent = (uRect.zw - uRect.xy) * 0.5;\n" +
            "  vec2 q = abs(p - center) - (extent - vec2(uRadius));\n" +
            "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;\n" +
            "  gl_FragColor = vec4(c, clamp(0.5 - d, 0.0, 1.0) * uAlpha);\n" +
            "}\n";

    /**
     * 液态玻璃（{@link LiquidGlass}，移植自 liquid-glass-react）：按位移贴图改变取样位置，越靠边越往外取，
     * 红、绿、蓝三个通道各按 uChannel 的倍数位移（色散）；之后的饱和度、增益和圆角同 FRAG_GLASS。
     */
    private static final String FRAG_LIQUID =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform sampler2D uSrc;\n" +
            "uniform sampler2D uMap;\n" +
            "uniform vec2 uScreen;\n" +
            "uniform vec4 uRect;\n" +
            "uniform float uRadius;\n" +
            "uniform vec3 uLook;\n" +
            "uniform float uAlpha;\n" +
            "uniform float uMax;\n" +
            "uniform vec2 uOrigin;\n" +
            "uniform float uDisp;\n" +
            "uniform vec3 uChannel;\n" +
            "void main() {\n" +
            "  vec2 p = gl_FragCoord.xy + uOrigin;\n" +
            "  vec2 center = (uRect.xy + uRect.zw) * 0.5;\n" +
            "  vec2 extent = (uRect.zw - uRect.xy) * 0.5;\n" +
            // 贴图按 xMidYMid slice 铺满玻璃；贴图第一行在上，GL 的 y 向上，所以纵向翻过来。
            "  vec2 m = (p - center) / (2.0 * max(extent.x, extent.y));\n" +
            "  vec3 map = texture2D(uMap, vec2(m.x + 0.5, 0.5 - m.y)).rgb;\n" +
            // 网页里取样点 = 当前点 − 位移（y 向下）；换到 y 向上，纵向分量反号。
            "  vec2 d = vec2(0.5 - map.r, map.b - 0.5) * uDisp;\n" +
            "  vec3 c = vec3(texture2D(uSrc, (p + d * uChannel.x) / uScreen).r,\n" +
            "                texture2D(uSrc, (p + d * uChannel.y) / uScreen).g,\n" +
            "                texture2D(uSrc, (p + d * uChannel.z) / uScreen).b);\n" +
            "  float l = dot(c, vec3(0.213, 0.715, 0.072));\n" +
            "  c = clamp(mix(vec3(l), c, uLook.y) * uLook.x + uLook.z, 0.0, 1.0);\n" +
            "  float mx = max(c.r, max(c.g, c.b));\n" +
            "  if (mx > uMax) c *= uMax / mx;\n" +
            "  vec2 q = abs(p - center) - (extent - vec2(uRadius));\n" +
            "  float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;\n" +
            "  gl_FragColor = vec4(c, clamp(0.5 - sd, 0.0, 1.0) * uAlpha);\n" +
            "}\n";

    private final HandlerThread thread = new HandlerThread("film-gl");
    private final Handler gl;
    private final float[] texMatrix = new float[16];
    private final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private final FloatBuffer quad;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture video;
    private Surface videoSurface;
    private int oes;
    private Prog progVideo, progDown, progCopy, progBlur, progGlass, progLiquid;
    /** 液态玻璃的位移贴图（{@link LiquidGlass#map}）。 */
    private int mapTex;
    /** 只在 {@link #glass} 为 true 时用：本渲染器登记给 FilmGlass 的回调，释放时只清掉自己这一个。 */
    private final Runnable onGlassChange = this::requestRender;
    /** 每帧取玻璃区域时复用，不再每帧新建列表。 */
    private final List<FilmGlass.Region> regions = new ArrayList<>();
    /** 打开着的面板（{@link FilmGlass.Panel}），以及渲染器在各面板画面上建的 EGL 输出（只在渲染线程上用）。 */
    private final List<FilmGlass.Panel> panels = new ArrayList<>();
    private final Map<FilmGlass.Panel, EGLSurface> panelSurfaces = new HashMap<>();
    private EGLConfig config;
    /** 首页的影片画程序坞和按钮的玻璃；视频屏保没有玻璃，不碰 FilmGlass。 */
    private final boolean glass;
    /** 1/4 尺寸两张（0、3，液态的模糊来回用），1/8 尺寸两张（1、2，磨砂的模糊来回用）。 */
    private static final int[] DIV = {4, 8, 8, 4};
    private final int[] fbo = new int[4], fboTex = new int[4], fboW = new int[4], fboH = new int[4];
    private int width, height;
    private volatile int videoW, videoH;
    private boolean frameAvailable, hasFrame, renderPosted, released;
    private float blurSigma = -1;
    private final float[] weights = new float[TAPS];
    private float blurStep;

    FilmRenderer(boolean glass) {
        this.glass = glass;
        thread.start();
        gl = new Handler(thread.getLooper());
        float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);
    }

    /** 在视图的纹理上建 GL 环境；就绪后把播放器该用的 Surface 交给 {@code ready}。 */
    void start(SurfaceTexture out, int w, int h, Ready ready) {
        gl.post(() -> {
            Surface s = null;
            try {
                width = w;
                height = h;
                setUp(out);
                s = videoSurface;
                if (glass) {
                    FilmGlass.setOnChange(onGlassChange);
                    FilmGlass.setRenderer(this);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "no GL, the film goes straight to the view: " + e);
                tearDown();
            }
            ready.onSurface(s);
        });
    }

    void resize(int w, int h) {
        gl.post(() -> {
            if (released) return;
            width = w;
            height = h;
            makeTargets();
            render();
        });
    }

    void setVideoSize(int w, int h) {
        videoW = w;
        videoH = h;
        requestRender();
    }

    /** 玻璃变了（位置、模式），视频没有新帧也重画一次。可从任何线程调用，多次请求合并成一次。 */
    void requestRender() {
        gl.post(() -> {
            if (renderPosted) return;
            renderPosted = true;
            gl.post(this::render);
        });
    }

    /** 播放器已释放后调用：释放 GL 环境，再在渲染线程上运行 {@code after}，然后结束线程。 */
    void release(Runnable after) {
        gl.post(() -> {
            if (glass) {
                FilmGlass.clearOnChange(onGlassChange);
                FilmGlass.clearRenderer(this);
            }
            tearDown();
            if (after != null) after.run();
            thread.quitSafely();
        });
    }

    @Override
    public void onFrameAvailable(SurfaceTexture texture) {
        frameAvailable = true;
        render();
    }

    // ---- 渲染线程 ----

    private void setUp(SurfaceTexture out) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new RuntimeException("eglInitialize");
        int[] attribs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) throw new RuntimeException("eglChooseConfig");
        config = configs[0];
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext");
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], out, new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreateWindowSurface");
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw new RuntimeException("eglMakeCurrent");
        progVideo = new Prog(FRAG_VIDEO);
        progDown = new Prog(FRAG_DOWN, "uStep");
        progCopy = new Prog(FRAG_COPY);
        progBlur = new Prog(FRAG_BLUR, "uDir", "uW");
        progGlass = new Prog(FRAG_GLASS, "uScreen", "uRect", "uRadius", "uLook", "uAlpha", "uMax", "uOrigin");
        progLiquid = new Prog(FRAG_LIQUID, "uScreen", "uRect", "uRadius", "uLook", "uAlpha", "uMax", "uOrigin", "uDisp",
                "uChannel", "uMap");
        mapTex = mapTexture();
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        oes = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        textureParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        video = new SurfaceTexture(oes);
        video.setOnFrameAvailableListener(this, gl);
        videoSurface = new Surface(video);
        makeTargets();
        // 第一帧视频到之前先画黑，视图不会显示上次残留的内容。
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        EGL14.eglSwapBuffers(display, eglSurface);
    }

    private void tearDown() {
        if (released) return;
        released = true;
        if (display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
            deleteTargets();
            if (oes != 0) GLES20.glDeleteTextures(1, new int[]{oes}, 0);
            for (Prog p : new Prog[]{progVideo, progDown, progCopy, progBlur, progGlass, progLiquid}) if (p != null) GLES20.glDeleteProgram(p.id);
            if (mapTex != 0) GLES20.glDeleteTextures(1, new int[]{mapTex}, 0);
        }
        if (videoSurface != null) videoSurface.release();
        if (video != null) video.release();
        videoSurface = null;
        video = null;
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            for (EGLSurface ps : panelSurfaces.values()) EGL14.eglDestroySurface(display, ps);
            panelSurfaces.clear();
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
    }

    /** 位移贴图上传成纹理；还没加载时用一个不位移的灰点（0.5 即不动）。 */
    private static int mapTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        textureParams(GLES20.GL_TEXTURE_2D);
        Bitmap map = LiquidGlass.map();
        if (map != null) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, map, 0);
        } else {
            ByteBuffer grey = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            grey.put(new byte[]{(byte) 128, (byte) 128, (byte) 128, (byte) 255}).position(0);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, grey);
        }
        return t[0];
    }

    private void makeTargets() {
        deleteTargets();
        for (int i = 0; i < fbo.length; i++) {
            fboW[i] = Math.max(1, width / DIV[i]);
            fboH[i] = Math.max(1, height / DIV[i]);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            fboTex[i] = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i]);
            textureParams(GLES20.GL_TEXTURE_2D);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fboW[i], fboH[i], 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glGenFramebuffers(1, t, 0);
            fbo[i] = t[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void deleteTargets() {
        for (int i = 0; i < fbo.length; i++) {
            if (fbo[i] != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo[i]}, 0);
            if (fboTex[i] != 0) GLES20.glDeleteTextures(1, new int[]{fboTex[i]}, 0);
            fbo[i] = fboTex[i] = 0;
        }
    }

    private void render() {
        renderPosted = false;
        if (released || eglSurface == EGL14.EGL_NO_SURFACE) return;
        try {
            if (frameAvailable) {
                frameAvailable = false;
                video.updateTexImage();
                video.getTransformMatrix(texMatrix);
                hasFrame = true;
            }
            if (!hasFrame) return;
            // 铺满屏幕、按比例裁切（同原来 TextureView.setTransform 的做法）。
            float sx = 1f, sy = 1f;
            if (videoW > 0 && videoH > 0 && width > 0 && height > 0) {
                float scale = Math.max(width / (float) videoW, height / (float) videoH);
                sx = videoW * scale / width;
                sy = videoH * scale / height;
            }
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, width, height);
            drawVideo(progVideo, sx, sy);
            regions.clear();
            panels.clear();
            if (glass && FilmGlass.active()) {
                FilmGlass.snapshot(regions);
                FilmGlass.panels(panels);
            }
            int blurred = regions.isEmpty() && panels.isEmpty() ? 0 : blurFrame(sx, sy);
            if (!regions.isEmpty()) drawGlass(regions, blurred);
            EGL14.eglSwapBuffers(display, eglSurface);
            if (!panels.isEmpty()) drawPanels(blurred);
        } catch (RuntimeException e) {
            Log.w(TAG, "render failed: " + e);
        }
    }

    private void drawVideo(Prog program, float sx, float sy) {
        GLES20.glUseProgram(program.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        GLES20.glUniform1i(program.uSrc, 0);
        quadUniforms(program, sx, sy, 0, 0, texMatrix);
        drawQuad(program);
    }

    /**
     * 当前帧缩到 1/4，然后横竖各模糊一遍。磨砂再缩到 1/8 才模糊，结果在 fboTex[1]；液态在 1/4 上做轻度模糊
     * （{@link LiquidGlass#BLUR}），结果在 fboTex[0]。返回结果所在的纹理。
     */
    private int blurFrame(float sx, float sy) {
        boolean frosted = FilmGlass.frosted();
        float sigma = frosted ? SIGMA_FROSTED : LiquidGlass.BLUR * css() / DIV[0];
        if (sigma != blurSigma) weights(sigma);
        // 1/4：每个输出像素平均视频里 4×4 个像素。
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glViewport(0, 0, fboW[0], fboH[0]);
        GLES20.glUseProgram(progDown.id);
        float vw = videoW > 0 ? videoW : width, vh = videoH > 0 ? videoH : height;
        GLES20.glUniform2f(progDown.extra[0], 1f / vw, 1f / vh);
        drawVideo(progDown, sx, sy);
        if (!frosted) {
            // 液态：横向 1/4 → 另一张 1/4，纵向再写回。
            blur(fboTex[0], 3, blurStep / fboW[0], 0);
            blur(fboTex[3], 0, 0, blurStep / fboH[0]);
            return fboTex[0];
        }
        // 1/8：双线性一次正好平均 2×2。
        copy(fboTex[0], 1);
        // 横向 1/8 → 另一张，纵向再写回。
        blur(fboTex[1], 2, blurStep / fboW[1], 0);
        blur(fboTex[2], 1, 0, blurStep / fboH[1]);
        return fboTex[1];
    }

    /** 网页的 1 像素在这块屏幕上是多少像素（同 {@link LiquidGlass#css}，按视图宽度算）。 */
    private float css() {
        return Math.max(1f, width / 1920f);
    }

    private void copy(int src, int target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[target]);
        GLES20.glViewport(0, 0, fboW[target], fboH[target]);
        GLES20.glUseProgram(progCopy.id);
        bind2d(progCopy, src);
        quadUniforms(progCopy, 1, 1, 0, 0, identity);
        drawQuad(progCopy);
    }

    private void blur(int src, int target, float dx, float dy) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[target]);
        GLES20.glViewport(0, 0, fboW[target], fboH[target]);
        GLES20.glUseProgram(progBlur.id);
        bind2d(progBlur, src);
        GLES20.glUniform2f(progBlur.extra[0], dx, dy);
        GLES20.glUniform1fv(progBlur.extra[1], TAPS, weights, 0);
        quadUniforms(progBlur, 1, 1, 0, 0, identity);
        drawQuad(progBlur);
    }

    /** 高斯权重：七个采样点（含中心）各向两边，覆盖约 ±2.5 sigma，双线性采样让间隔不必是整像素。 */
    private void weights(float sigma) {
        blurSigma = sigma;
        blurStep = 2.5f * sigma / (TAPS - 1);
        float sum = 0;
        for (int i = 0; i < TAPS; i++) {
            float x = i * blurStep;
            weights[i] = (float) Math.exp(-x * x / (2 * sigma * sigma));
            sum += i == 0 ? weights[i] : 2 * weights[i];
        }
        for (int i = 0; i < TAPS; i++) weights[i] /= sum;
    }

    private void drawGlass(List<FilmGlass.Region> regions, int blurred) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        Prog prog = FilmGlass.frosted() ? progGlass : progLiquid;
        GLES20.glUseProgram(prog.id);
        bind2d(prog, blurred);
        int[] u = prog.extra;
        GLES20.glUniform2f(u[0], width, height);
        GLES20.glUniform2f(u[6], 0, 0);
        if (prog == progLiquid) liquidUniforms(u);
        for (FilmGlass.Region r : regions) {
            if (r.alpha <= 0f) continue;
            // 屏幕坐标 y 向下，GL 的 gl_FragCoord y 向上。
            float l = r.left, t = height - r.bottom, rr = r.right, b = height - r.top;
            GLES20.glUniform4f(u[1], l, t, rr, b);
            GLES20.glUniform1f(u[2], r.radius);
            GLES20.glUniform3f(u[3], r.gain, r.saturation, r.offset / 255f);
            GLES20.glUniform1f(u[4], r.alpha);
            GLES20.glUniform1f(u[5], r.brightest);
            // 只画这块区域（外扩 1 像素留给边缘抗锯齿）。
            float x0 = (l - 1) / width * 2 - 1, x1 = (rr + 1) / width * 2 - 1;
            float y0 = (t - 1) / height * 2 - 1, y1 = (b + 1) / height * 2 - 1;
            quadUniforms(prog, (x1 - x0) / 2, (y1 - y0) / 2, (x0 + x1) / 2, (y0 + y1) / 2, identity);
            drawQuad(prog);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** 液态着色器的位移、色散和位移贴图（纹理单元 1）。 */
    private void liquidUniforms(int[] u) {
        GLES20.glUniform1f(u[7], LiquidGlass.DISPLACEMENT * css());
        float[] ch = LiquidGlass.CHANNEL;
        GLES20.glUniform3f(u[8], ch[0], ch[1], ch[2]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mapTex);
        GLES20.glUniform1i(u[9], 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    /**
     * 各面板：在面板自己的画面（TextureView）上铺满一块玻璃，与主画面同一张模糊图、同一套算法；片元坐标加上面板左下角在
     * 屏幕上的位置，取样和位移就和画在主画面上一样。TextureView 是硬件图层，面板视图的圆角裁剪管不到它，所以圆角在这里画：
     * 圆角之外透明。
     */
    private void drawPanels(int blurred) {
        int[] noAttribs = {EGL14.EGL_NONE};
        for (FilmGlass.Panel p : panels) {
            EGLSurface s = panelSurfaces.get(p);
            if (s == null) {
                try {
                    s = EGL14.eglCreateWindowSurface(display, config, p.texture, noAttribs, 0);
                } catch (RuntimeException e) {
                    s = EGL14.EGL_NO_SURFACE;
                }
                if (s == EGL14.EGL_NO_SURFACE) continue;
                panelSurfaces.put(p, s);
            }
            if (!EGL14.eglMakeCurrent(display, s, s, context)) continue;
            int pw = p.width, ph = p.height;
            float[] look = p.look;
            if (pw <= 0 || ph <= 0 || look == null) continue;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, pw, ph);
            // 圆角外透明：先清成透明，再按着色器给的覆盖率混合；颜色乘上覆盖率（预乘），透明度直接取覆盖率，
            // TextureView（不透明设为 false）按预乘透明度合成，圆角边缘不会发亮。
            GLES20.glClearColor(0, 0, 0, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE,
                    GLES20.GL_ONE_MINUS_SRC_ALPHA);
            Prog prog = p.style == FilmGlass.Panel.LIQUID ? progLiquid : progGlass;
            GLES20.glUseProgram(prog.id);
            bind2d(prog, blurred);
            int[] u = prog.extra;
            float left = p.left, bottom = height - (p.top + ph);
            GLES20.glUniform2f(u[0], width, height);
            GLES20.glUniform4f(u[1], left, bottom, left + pw, bottom + ph);
            GLES20.glUniform1f(u[2], p.radius);
            GLES20.glUniform3f(u[3], look[0], look[1], look[2] / 255f);
            GLES20.glUniform1f(u[4], 1);
            GLES20.glUniform1f(u[5], 1);
            GLES20.glUniform2f(u[6], left, bottom);
            if (prog == progLiquid) liquidUniforms(u);
            quadUniforms(prog, 1, 1, 0, 0, identity);
            drawQuad(prog);
            GLES20.glDisable(GLES20.GL_BLEND);
            EGL14.eglSwapBuffers(display, s);
        }
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
    }

    /**
     * 面板关掉：在渲染线程上撤掉它的 EGL 输出，再释放它的纹理。渲染线程已结束时返回 false，由调用方直接释放纹理。
     */
    boolean releasePanel(FilmGlass.Panel p) {
        return gl.post(() -> {
            EGLSurface s = panelSurfaces.remove(p);
            if (s != null && display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context);
                EGL14.eglDestroySurface(display, s);
            }
            p.texture.release();
        });
    }

    private void bind2d(Prog program, int texture) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(program.uSrc, 0);
    }

    private void quadUniforms(Prog program, float sx, float sy, float ox, float oy, float[] tex) {
        GLES20.glUniform2f(program.uScale, sx, sy);
        GLES20.glUniform2f(program.uOffset, ox, oy);
        GLES20.glUniformMatrix4fv(program.uTex, 1, false, tex, 0);
    }

    private void drawQuad(Prog program) {
        int pos = program.aPos;
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
    }

    private static void textureParams(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    /** 一个着色器程序，连同它用到的 attribute 和 uniform 位置：建程序时查一次，之后每帧直接用。 */
    private static final class Prog {
        final int id, aPos, uSrc, uScale, uOffset, uTex;
        /** 各程序自己的 uniform，顺序同构造时传入的名字。 */
        final int[] extra;

        Prog(String fragment, String... uniforms) {
            id = program(fragment);
            aPos = GLES20.glGetAttribLocation(id, "aPos");
            uSrc = GLES20.glGetUniformLocation(id, "uSrc");
            uScale = GLES20.glGetUniformLocation(id, "uScale");
            uOffset = GLES20.glGetUniformLocation(id, "uOffset");
            uTex = GLES20.glGetUniformLocation(id, "uTex");
            extra = new int[uniforms.length];
            for (int i = 0; i < uniforms.length; i++) extra[i] = GLES20.glGetUniformLocation(id, uniforms[i]);
        }
    }

    private static int program(String fragment) {
        int vs = shader(GLES20.GL_VERTEX_SHADER, VERTEX), fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("link: " + log);
        }
        return p;
    }

    private static int shader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("compile: " + log);
        }
        return s;
    }
}
