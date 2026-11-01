#!/usr/bin/env python3
"""生成 AweHome（桌面）和同系列 AweTV（影视 app）的应用图标（自适应图标的背景、前景、单色三层）和电视横幅（宽体图标）。

用法（在 launcher/app/src/awehome 下）：
  python3 art/make_awehome_icons.py                    生成 AweHome，写进本项目的 res/
  python3 art/make_awehome_icons.py awetv 输出目录      生成 AweTV，写进「输出目录/res/」
  第三个参数可以给一个 PNG 路径，另存一张预览图（圆形、圆角方形遮罩，单色主题图标，横幅）。
输出（以 AweHome 为例，AweTV 把 awehome 换成 awetv）：
  res/mipmap-xxxhdpi/ic_awehome_background.png、ic_awehome_foreground.png、ic_awehome_monochrome.png（432×432，即 108dp；
  单色层供 Android 13 起的主题图标用，只有「A」和小窗 / 播放三角的形状）
  res/drawable-nodpi/banner_awehome.png（640×360：电视按 320×180dp 显示，桌面启动器还会再缩放到自己的格子）
设计（2026-11-01 用户在三个方向里选定「玻璃透镜」）：极光夜空、星点和山脊的风景，前面一块「液态玻璃 2」的大玻璃圆
（画法同 clear-liquid-glass 技能，即 ClearGlass.java 的层次和数值；玻璃底下的风景轻度模糊、提亮，和面板上的做法相同），
圆里是白色山峰形的「A」，横杠下方 AweHome 是一扇亮着暖光的四格小窗（「A」成了一座亮灯的小房子，即桌面；
用户在 8 个方案里选的），AweTV 是播放三角。两个产品用颜色区分：AweHome 青绿极光，AweTV 玫红到琥珀的极光；
小窗和播放三角都用暖色，和冷色的极光形成对比。横幅右边是 Outfit 字体的「Awe」（Light）加
「Home」/「TV」（SemiBold），字体是项目自带的 res/font/outfit.ttf（OFL 许可）。需要 Pillow。
"""
import math
import os
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FONT = os.path.join(ROOT, "..", "main", "res", "font", "outfit.ttf")
SS = 3  # 超采样倍数

PRODUCTS = {
    "awehome": dict(
        name="AweHome", symbol="window",
        sky=[(0.0, (8, 12, 40)), (0.55, (26, 32, 98)), (1.0, (40, 72, 138))],
        ribbon=[(45, 212, 191), (110, 231, 183)],
        glow=(129, 140, 248),
        ridge=[(0.0, (14, 22, 62)), (1.0, (6, 9, 28))],
        accent=[(253, 230, 138), (251, 146, 60)], accent_dir=(0.3, 1), accent_glow=0.75,
    ),
    "awetv": dict(
        name="AweTV", symbol="play",
        sky=[(0.0, (20, 8, 38)), (0.55, (64, 20, 92)), (1.0, (112, 36, 104))],
        ribbon=[(244, 114, 182), (251, 146, 60)],
        glow=(129, 140, 248),
        ridge=[(0.0, (40, 14, 54)), (1.0, (14, 5, 22))],
        accent=[(253, 186, 116), (244, 114, 182)], accent_dir=(1, 0), accent_glow=0.5,
    ),
}


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def ramp(stops, t):
    t = max(0.0, min(1.0, t))
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            return lerp(c0, c1, (t - t0) / (t1 - t0) if t1 > t0 else 0)
    return stops[-1][1]


def colorize(L, stops):
    """把 0..255 的灰度图按色标映射成彩色。"""
    luts = [[ramp(stops, i / 255)[k] for i in range(256)] for k in range(3)]
    return Image.merge("RGB", [L.point(lut) for lut in luts])


def lin(w, h, ux, uy):
    """线性渐变（0..255），方向 (ux, uy) 按归一化坐标。先在 64×64 上算，再放大（线性函数放大不失真）。"""
    s = 64
    img = Image.new("L", (s, s))
    px = img.load()
    ps = [cx * ux + cy * uy for cx in (0, 1) for cy in (0, 1)]
    lo, hi = min(ps), max(ps)
    for y in range(s):
        for x in range(s):
            px[x, y] = round(255 * (x / (s - 1) * ux + y / (s - 1) * uy - lo) / (hi - lo))
    return img.resize((w, h), Image.BILINEAR)


def paint(img, mask, rgb, alpha=1.0):
    if alpha != 1.0:
        mask = mask.point(lambda v: round(v * alpha))
    layer = Image.new("RGBA", img.size, rgb + (0,))
    layer.putalpha(mask)
    img.alpha_composite(layer)


def blob(img, cx, cy, r, rgb, alpha):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse([cx - r, cy - r, cx + r, cy + r], fill=round(255 * alpha))
    paint(img, m.filter(ImageFilter.GaussianBlur(r * 0.5)), rgb)


def scene(p, w, h, seed=7):
    """风景背景：夜空渐变、左下一团紫光、两条起伏的极光帘、星点、底部山脊。"""
    img = colorize(lin(w, h, 0.25, 1.0), p["sky"]).convert("RGBA")
    u = min(w, h)
    blob(img, w * 0.15, h * 0.95, u * 0.45, p["glow"], 0.35)
    # 极光：一条起伏的光带往上叠十几层、逐层变淡，模糊后成了帘子。
    for rgb, y0, amp, ph, a in [(p["ribbon"][0], 0.42, 0.07, 0.6, 0.85), (p["ribbon"][1], 0.30, 0.05, 2.2, 0.55)]:
        m = Image.new("L", (w, h), 0)
        d = ImageDraw.Draw(m)
        for k in range(14):
            pts = []
            for j in range(41):
                x = -0.1 * w + j / 40 * 1.2 * w
                pts.append((x, h * (y0 + amp * math.sin(x / w * 5.2 + ph) + 0.02 * math.sin(x / w * 13 + ph * 2))
                            - k * u * 0.018))
            d.line(pts, fill=round(255 * a * (1 - k / 14) ** 1.6), width=max(1, round(u * 0.035)))
        paint(img, m.filter(ImageFilter.GaussianBlur(u * 0.03)), rgb)
    rnd = random.Random(seed)
    stars = Image.new("L", (w, h), 0)
    sd = ImageDraw.Draw(stars)
    for _ in range(int(60 * w * h / u / u)):
        x, y, r = rnd.random() * w, rnd.random() * h * 0.55, u * (0.002 + rnd.random() * 0.003)
        sd.ellipse([x - r, y - r, x + r, y + r], fill=round(255 * (0.3 + 0.6 * rnd.random())))
    paint(img, stars.filter(ImageFilter.GaussianBlur(u * 0.0015)), (255, 255, 255))
    m = Image.new("L", (w, h), 0)
    pts = [(0, h)]
    rr = random.Random(seed + 1)
    x = -0.05 * w
    while x < 1.05 * w:
        pts.append((x, h * (0.80 + 0.04 * math.sin(x / w * 7) - 0.06 * max(0, 1 - abs(x / w - 0.62) * 4)
                            + 0.01 * rr.random())))
        x += w / 40
    pts.append((w, h))
    ImageDraw.Draw(m).polygon(pts, fill=255)
    ridge = colorize(lin(w, h, 0, 1), p["ridge"]).convert("RGBA")
    ridge.putalpha(m.filter(ImageFilter.GaussianBlur(u * 0.002)))
    img.alpha_composite(ridge)
    return img


def circle_shape(W, H, cx, cy, r):
    """圆形玻璃的形状：f(inset, ox, oy) 返回画布大小的遮罩，inset 为正时向内缩、为负时向外扩。"""
    def f(inset=0.0, ox=0.0, oy=0.0):
        m = Image.new("L", (W, H), 0)
        rr = r - inset
        if rr > 0:
            ImageDraw.Draw(m).ellipse([cx - rr + ox, cy - rr + oy, cx + rr + ox, cy + rr + oy], fill=255)
        return m
    return f


def glass(shape, dp):
    """「液态玻璃 2」的透明玻璃（与 ClearGlass.java 相同的层次和数值）；dp = 网页 1px 对应的像素数。返回 RGBA。"""
    inside = shape()
    W, H = inside.size
    x0, y0, x1, y1 = inside.getbbox()
    art = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # 投影 0 4px 8px rgba(0,0,0,.2)，只在玻璃外面。
    sh = ImageChops.offset(inside, 0, round(4 * dp)).filter(ImageFilter.GaussianBlur(4 * dp))
    paint(art, ImageChops.subtract(sh, inside), (0, 0, 0), 0.2)
    paint(art, inside, (255, 255, 255), 0.05)
    # 五条内阴影，CSS 里列在前面的画在上面，所以倒着画。
    for dx, dy, b, spread, rgb, a in [(0, 0, 2, 0, (0, 0, 0), 0.8),
                                      (-6, 6, 1, -6, (255, 255, 255), 0.55), (6, -6, 1, -6, (255, 255, 255), 0.55),
                                      (-2, 2, 1, -1, (255, 255, 255), 0.9), (2, -2, 1, -1, (255, 255, 255), 0.9)]:
        s = ImageChops.invert(shape(spread * dp, dx * dp, dy * dp))
        if b / 2 * dp > 0.25:
            s = s.filter(ImageFilter.GaussianBlur(b / 2 * dp))
        paint(art, ImageChops.multiply(s, inside), rgb, a)
    # ::before：玻璃下半部的厚度暗弧。
    lift = 0.35 * (y1 - y0)
    arc = ImageChops.subtract(shape(8 * dp, 0, lift), shape(9 * dp, 0, lift)).filter(ImageFilter.GaussianBlur(8 * dp))
    paint(art, ImageChops.multiply(arc, inside), (0, 0, 0), 0.9)
    # .circle-overlay：内侧一圈细亮环。
    ring = ImageChops.subtract(shape(4.5 * dp), shape(5.5 * dp)).filter(ImageFilter.GaussianBlur(dp))
    paint(art, ImageChops.multiply(ring, inside), (255, 255, 255), 0.2)
    # ::after：45° 渐变，左下、右上两块白色高光（圆形 25%）。
    s = 96
    g = Image.new("L", (s, s))
    gp = g.load()
    for y in range(s):
        for x in range(s):
            t = (x / (s - 1) + (1 - y / (s - 1))) / 2
            v = (1 - t / 0.25) if t < 0.25 else ((t - 0.75) / 0.25 if t > 0.75 else 0)
            gp[x, y] = round(255 * 0.8 * v)
    hl = Image.new("L", (W, H), 0)
    hl.paste(g.resize((x1 - x0, y1 - y0), Image.BILINEAR), (x0, y0))
    hl = ImageChops.multiply(hl, inside).filter(ImageFilter.GaussianBlur(3 * dp))
    paint(art, ImageChops.multiply(hl, inside), (255, 255, 255))
    return art


def soften(m, r):
    """把尖角稍微磨圆：模糊后再拉回边缘。"""
    return m.filter(ImageFilter.GaussianBlur(r)).point(lambda v: max(0, min(255, (v - 96) * 255 // 64)))


def mark_masks(W, H, cx, cy, s, symbol):
    """山峰形「A」的遮罩和横杠位置符号（四格小窗 window / 播放三角 play）的遮罩；s 为「A」的高度。"""
    stroke = 0.20 * s
    apex, foot = (cx, cy - 0.52 * s), 0.48 * s
    pts = [(cx - 0.50 * s, cy + foot), apex, (cx + 0.50 * s, cy + foot)]
    a = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(a)
    d.line(pts, fill=255, width=round(stroke), joint="curve")
    for x, y in pts:
        d.ellipse([x - stroke / 2, y - stroke / 2, x + stroke / 2, y + stroke / 2], fill=255)
    bottom = cy + foot + stroke / 2
    sym = Image.new("L", (W, H), 0)
    sd = ImageDraw.Draw(sym)
    if symbol == "window":
        # 四格小窗（2×2 的圆角方块），放在两条腿之间、横杠的位置下方。
        z, g = 0.11 * s, 0.03 * s
        x0, y0 = cx - z - g / 2, cy + 0.12 * s
        for i in range(2):
            for j in range(2):
                x, y = x0 + i * (z + g), y0 + j * (z + g)
                sd.rounded_rectangle([x, y, x + z, y + z], radius=0.025 * s, fill=255)
    else:
        z, py, px0 = 0.30 * s, bottom - 0.20 * s, cx + 0.02 * s
        sd.polygon([(px0 - 0.36 * z, py - 0.5 * z), (px0 - 0.36 * z, py + 0.5 * z), (px0 + 0.58 * z, py)], fill=255)
        sym = soften(sym, z * 0.06)
    return a, sym


def lens(dst, bg, p, cx, cy, u, dp):
    """玻璃透镜：玻璃底下的风景轻度模糊、提亮，盖上玻璃，再画白色「A」和亮色符号。u 为尺寸基准（图标为画布边长）。"""
    W, H = dst.size
    shape = circle_shape(W, H, cx, cy, 0.275 * u)
    frosted = ImageEnhance.Brightness(bg.filter(ImageFilter.GaussianBlur(u * 0.012))).enhance(1.12)
    dst.paste(frosted, (0, 0), shape())
    dst.alpha_composite(glass(shape, dp))
    s = 0.31 * u
    a, sym = mark_masks(W, H, cx, cy, s, p["symbol"])
    shadow = ImageChops.offset(ImageChops.lighter(a, sym).filter(ImageFilter.GaussianBlur(0.06 * s)), 0, round(0.04 * s))
    paint(dst, shadow, (8, 10, 40), 0.40)
    white = colorize(lin(W, H, 0, 1), [(0, (255, 255, 255)), (1, (222, 240, 255))]).convert("RGBA")
    white.putalpha(a)
    dst.alpha_composite(white)
    paint(dst, sym.filter(ImageFilter.GaussianBlur(u * 0.03)), p["accent"][1], p["accent_glow"])
    tint = colorize(lin(W, H, *p["accent_dir"]), [(0, p["accent"][0]), (1, p["accent"][1])]).convert("RGBA")
    tint.putalpha(sym)
    dst.alpha_composite(tint)


def down(img, w, h):
    return img.resize((w, h), Image.LANCZOS)


def icon(p):
    """自适应图标三层。前景里带着玻璃底下那块模糊的风景（背景是固定的，烤进前景即可）。"""
    n = 432 * SS
    bg = scene(p, n, n)
    fg = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    lens(fg, bg, p, n / 2, n / 2, n, 2.0 * SS)
    a, sym = mark_masks(n, n, n / 2, n / 2, 0.31 * n, p["symbol"])
    mono = Image.new("RGBA", (n, n), (255, 255, 255, 0))
    mono.putalpha(ImageChops.lighter(a, sym))
    return down(bg, 432, 432).convert("RGB"), down(fg, 432, 432), down(mono, 432, 432)


def banner(p, w=640, h=360):
    W, H = w * SS, h * SS
    art = scene(p, W, H)
    bg = art.copy()
    u = 0.86 * H                               # 透镜的尺寸基准，玻璃圆直径 0.55u
    gw, gap, margin = 0.62 * u, 0.02 * W, 0.07 * W
    d = ImageDraw.Draw(art)
    rest = p["name"][3:]
    # 字号按剩余宽度算：「Awe」+「Home」/「TV」最宽占满透镜右边到右边距之间，最高为横幅高度的 0.30。
    size = round(0.30 * H)
    while True:
        light = ImageFont.truetype(FONT, size)
        light.set_variation_by_name("Light")
        bold = ImageFont.truetype(FONT, size)
        bold.set_variation_by_name("SemiBold")
        ba = d.textbbox((0, 0), "Awe", font=light)
        bb = d.textbbox((0, 0), rest, font=bold)
        tw = (ba[2] - ba[0]) + (bb[2] - bb[0]) + size * 0.02
        if tw <= W - 2 * margin - gw - gap or size < 10:
            break
        size -= 2
    gx = (W - (gw + gap + tw)) / 2 + gw / 2
    lens(art, bg, p, gx, H / 2, u, 2.0 * SS)
    tx = gx + gw / 2 + gap - ba[0]
    tx2 = tx + ba[0] + (ba[2] - ba[0]) + size * 0.02 - bb[0]
    top, bot = min(ba[1], bb[1]), max(ba[3], bb[3])
    ty = (H - (bot - top)) / 2 - top
    sh = Image.new("L", (W, H), 0)
    sd = ImageDraw.Draw(sh)
    sd.text((tx, ty + 0.012 * H), "Awe", font=light, fill=255)
    sd.text((tx2, ty + 0.012 * H), rest, font=bold, fill=255)
    paint(art, sh.filter(ImageFilter.GaussianBlur(0.02 * H)), (8, 10, 40), 0.40)
    d.text((tx, ty), "Awe", font=light, fill=(255, 255, 255, 235))
    d.text((tx2, ty), rest, font=bold, fill=(255, 255, 255, 255))
    return down(art, w, h).convert("RGB")


def preview(bg, fg, mono, ban, path):
    """预览：圆形、圆角方形遮罩（取中间 72dp），单色主题图标，横幅。"""
    full = bg.convert("RGBA")
    full.alpha_composite(fg)
    c = full.crop((72, 72, 360, 360)).resize((288, 288), Image.LANCZOS)
    m = mono.crop((72, 72, 360, 360)).resize((288, 288), Image.LANCZOS)
    out = Image.new("RGB", (1064, 790), (24, 24, 30))

    def put(img, x, y, kind):
        mask = Image.new("L", (288 * 4, 288 * 4), 0)
        md = ImageDraw.Draw(mask)
        if kind == "circle":
            md.ellipse([0, 0, 288 * 4 - 1, 288 * 4 - 1], fill=255)
        else:
            md.rounded_rectangle([0, 0, 288 * 4 - 1, 288 * 4 - 1], radius=288 * 4 * 0.22, fill=255)
        out.paste(img, (x, y), mask.resize((288, 288), Image.LANCZOS))

    put(c.convert("RGB"), 60, 40, "circle")
    put(c.convert("RGB"), 388, 40, "squircle")
    themed = Image.new("RGB", (288, 288), (210, 228, 250))
    themed.paste(Image.new("RGB", (288, 288), (30, 58, 110)), (0, 0), m.getchannel("A"))
    put(themed, 716, 40, "circle")
    out.paste(ban, (212, 370))
    out.save(path)


def main(argv):
    key = argv[1] if len(argv) > 1 else "awehome"
    if key not in PRODUCTS:
        print("用法：make_awehome_icons.py [awehome|awetv] [输出目录] [预览图.png]")
        return 2
    p = PRODUCTS[key]
    out = argv[2] if len(argv) > 2 else ROOT
    bg, fg, mono = icon(p)
    ban = banner(p)
    os.makedirs(os.path.join(out, "res", "mipmap-xxxhdpi"), exist_ok=True)
    bg.save(os.path.join(out, "res", "mipmap-xxxhdpi", f"ic_{key}_background.png"), optimize=True)
    fg.save(os.path.join(out, "res", "mipmap-xxxhdpi", f"ic_{key}_foreground.png"), optimize=True)
    mono.save(os.path.join(out, "res", "mipmap-xxxhdpi", f"ic_{key}_monochrome.png"), optimize=True)
    os.makedirs(os.path.join(out, "res", "drawable-nodpi"), exist_ok=True)
    ban.save(os.path.join(out, "res", "drawable-nodpi", f"banner_{key}.png"), optimize=True)
    if len(argv) > 3:
        preview(bg, fg, mono, ban, argv[3])
    print(f"已生成 {p['name']} 的图标与横幅")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
