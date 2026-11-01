package com.gathin.tvhome;

import android.graphics.SurfaceTexture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AweHome：视频背景下的实时玻璃。首页上需要透出背景的地方（程序坞底板、右上角按钮）每次绘制时把自己在屏幕上的
 * 圆角矩形登记到这里；{@link FilmRenderer} 每画一帧视频，就把这些区域背后的画面在 GPU 上模糊、调色后画进视频层。
 * 所以玻璃和画面同步流动，主线程不再抓帧或做模糊。没有视频时（{@link #active} 为 false），各处仍用静态模糊图。
 */
final class FilmGlass {
    /** 一块玻璃：屏幕坐标的圆角矩形，以及颜色变换（先调饱和度，再乘增益、加偏移，同 {@link GlassLook}）。 */
    static final class Region {
        final float left, top, right, bottom, radius, gain, saturation, offset;
        /** 整块玻璃的不透明度：程序坞淡出淡入时跟着变。 */
        final float alpha;
        /** 颜色最亮不超过这个值（0 到 1）：按钮上要放白色图标。 */
        final float brightest;

        Region(float left, float top, float right, float bottom, float radius, float[] look, float alpha) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.radius = radius;
            this.gain = look[0];
            this.saturation = look[1];
            this.offset = look[2];
            this.alpha = alpha;
            this.brightest = look.length > 3 ? look[3] : 1f;
        }

        /** 与这些参数完全相同：每帧登记的位置大多没变，先比较，不必新建对象。 */
        boolean same(float left, float top, float right, float bottom, float radius, float[] look, float alpha) {
            return this.left == left && this.top == top && this.right == right && this.bottom == bottom
                    && this.radius == radius && gain == look[0] && saturation == look[1] && offset == look[2]
                    && this.alpha == alpha && brightest == (look.length > 3 ? look[3] : 1f);
        }
    }

    /**
     * 浮在首页上方、自己有窗口的玻璃面板（偏好、信号源菜单、长按菜单）。首页的界面盖在视频层上面，视频层画在面板位置的
     * 玻璃会被首页界面挡住、面板若透明又会露出首页界面，所以面板里放一个 TextureView，渲染器每画一帧视频，
     * 就把这块玻璃直接画进面板自己的画面（{@link FilmRenderer}）。首页界面另由面板叠一张模糊截图（{@link GlassPanel}）。
     */
    static final class Panel {
        static final int FROSTED = 0, LIQUID = 1, CLEAR = 2;
        final SurfaceTexture texture;
        volatile int width, height, style;
        /** 面板左上角在屏幕上的位置，以及圆角（像素）：圆角外由渲染器画成透明。 */
        volatile float left, top, radius;
        /** 颜色变换 {gain, sat, offset}，同 {@link Region}。 */
        volatile float[] look;

        Panel(SurfaceTexture texture, int width, int height) {
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }

    private static final List<Panel> panels = new ArrayList<>();
    /** 画玻璃的渲染器（首页的影片），面板关掉时由它先撤掉画面再释放纹理。 */
    private static FilmRenderer renderer;

    private static final Map<String, Region> regions = new HashMap<>();
    private static volatile boolean active, frosted, clear;
    private static volatile Runnable onChange, onActiveChanged;

    private FilmGlass() {
    }

    /** 视频正在画玻璃：程序坞和按钮只画边缘，玻璃本身交给视频层。 */
    static boolean active() {
        return active;
    }

    static void setActive(boolean on) {
        if (on == active) return;
        active = on;
        // 停下时清空：各处下次绘制会按静态模糊图画，重新开始时再各自登记。
        if (!on) {
            synchronized (regions) {
                regions.clear();
            }
        }
        changed();
        Runnable ui = onActiveChanged;
        if (ui != null) ui.run();
    }

    /** 首页登记：开始或停止实时玻璃时，让程序坞和按钮重画（在主线程上调用）。 */
    static synchronized void setOnActiveChanged(Runnable r) {
        onActiveChanged = r;
    }

    /** 首页销毁时调用：只有登记的仍是它自己时才清掉。 */
    static synchronized void clearOnActiveChanged(Runnable r) {
        if (onActiveChanged == r) onActiveChanged = null;
    }

    /** 当前模糊效果：磨砂模糊得更重。 */
    static boolean frosted() {
        return frosted;
    }

    static void setFrosted(boolean on) {
        if (on == frosted) return;
        frosted = on;
        changed();
    }

    /**
     * 当前是否为「液态玻璃 2」（{@link ClearGlass}）：玻璃本身透明，视频层不必为它画任何东西，程序坞和按钮都不登记，
     * 只画边缘的光影。
     */
    static boolean clear() {
        return clear;
    }

    static void setClear(boolean on) {
        if (on == clear) return;
        clear = on;
        changed();
    }

    /** 渲染器登记：玻璃有变化（位置、模式）时重画一帧，不必等下一帧视频。 */
    static synchronized void setOnChange(Runnable r) {
        onChange = r;
    }

    /** 渲染器登记自己：之后面板的画面由它画、由它释放。 */
    static synchronized void setRenderer(FilmRenderer r) {
        renderer = r;
    }

    /** 渲染器释放时调用：只有登记的仍是它自己时才清掉。 */
    static synchronized void clearRenderer(FilmRenderer r) {
        if (renderer == r) renderer = null;
    }

    static void addPanel(Panel p) {
        synchronized (panels) {
            panels.add(p);
        }
        changed();
    }

    /** 面板关掉：渲染器先撤掉它在这块画面上的 EGL 输出再释放纹理；没有渲染器时直接释放。 */
    static void removePanel(Panel p) {
        synchronized (panels) {
            panels.remove(p);
        }
        FilmRenderer r;
        synchronized (FilmGlass.class) {
            r = renderer;
        }
        if (r == null || !r.releasePanel(p)) p.texture.release();
        changed();
    }

    /** 面板挪了位置、换了模式：视频暂停时也重画一帧。 */
    static void panelChanged() {
        changed();
    }

    /** 把当前打开的面板放进 {@code out}（渲染器每帧复用同一个列表）。 */
    static void panels(List<Panel> out) {
        synchronized (panels) {
            for (Panel p : panels) out.add(p);
        }
    }

    /** 渲染器释放时调用：只有登记的仍是它自己时才清掉，不会误清后来登记的渲染器。 */
    static synchronized void clearOnChange(Runnable r) {
        if (onChange == r) onChange = null;
    }

    static void put(String key, float left, float top, float right, float bottom, float radius, float[] look) {
        put(key, left, top, right, bottom, radius, look, 1f);
    }

    static void put(String key, float left, float top, float right, float bottom, float radius, float[] look, float alpha) {
        synchronized (regions) {
            Region old = regions.get(key);
            if (old != null && old.same(left, top, right, bottom, radius, look, alpha)) return;
            regions.put(key, new Region(left, top, right, bottom, radius, look, alpha));
        }
        changed();
    }

    static void remove(String key) {
        synchronized (regions) {
            if (regions.remove(key) == null) return;
        }
        changed();
    }

    /** 把当前登记的各块玻璃放进 {@code out}（渲染器每帧复用同一个列表）。 */
    static void snapshot(List<Region> out) {
        synchronized (regions) {
            for (Region r : regions.values()) out.add(r);
        }
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }
}
