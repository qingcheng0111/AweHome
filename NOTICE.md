# 来源与第三方许可

AweHome 本身以 [MIT](LICENSE) 发布。下面这些部分来自别的项目或作者，各自保留原来的许可。

## 上游项目

| 来源 | 用在哪里 | 许可 |
|---|---|---|
| [cleanyourtv](https://github.com/Gathin23/cleanyourtv)（Gathin） | 桌面的底层：`launcher/app/src/main/` 里的首页、程序坞、信号源、时钟屏保、主题配置等，以及 `launcher/build-and-install.*` 和 `tools/` 里的脚本 | MIT，版权行保留在 [LICENSE](LICENSE) |
| [aerial-tv](https://github.com/pengchujin/aerial-tv)（pengchujin） | 天气、手机传图、按键声音和 `res/raw/ui_*.wav`、检查更新、必应精选、文件夹模型、航拍片单 `aerials.json`、信号源的小米路由 | MIT，见 [THIRD-PARTY-AERIAL-LICENSE.txt](launcher/app/src/awehome/THIRD-PARTY-AERIAL-LICENSE.txt) |
| [liquid-glass-react](https://github.com/rdev/liquid-glass-react)（Max Rovensky） | 「液态玻璃」的算法和位移贴图 `assets/liquid-glass/displacement.jpg` | MIT，见 [THIRD-PARTY-LIQUID-GLASS-LICENSE.txt](launcher/app/src/awehome/THIRD-PARTY-LIQUID-GLASS-LICENSE.txt) |

`launcher/app/src/main/` 和 `tools/` 的注释里出现的 `#数字` 和 `docs/…` 路径，指的是 cleanyourtv 仓库里的 issue 和文档，不是本仓库的。

## 字体、图片和数据

| 内容 | 许可 |
|---|---|
| Outfit 字体（`res/font/outfit.ttf`） | SIL Open Font License 1.1，见 [FONT-LICENSE-OFL.txt](launcher/FONT-LICENSE-OFL.txt) |
| 万圣节皮肤的 Fluent Emoji 图片 | MIT，见 [ICONS-LICENSE-FLUENT-EMOJI.txt](launcher/ICONS-LICENSE-FLUENT-EMOJI.txt) |
| 内置照片（`assets/backdrops/`，来自 Wikimedia Commons） | 每张各自的 CC BY 或 CC BY-SA，见 [CREDITS.md](launcher/app/src/awehome/assets/backdrops/CREDITS.md)；电视上显示时右上角带署名 |
| 国内城市表（`assets/cities-cn.tsv`） | [GeoNames](https://www.geonames.org)，CC BY 4.0 |

## 运行时从网上获取、不随安装包分发的内容

- Apple TV 航拍影片：播放时直接从 Apple 的服务器拉流，版权归 Apple。
- 必应每日精选图片、爱奇艺公开榜单的剧照：版权归各自权利人。
- 天气：[Open-Meteo](https://open-meteo.com)；按 IP 定位城市：ipinfo。

## 依赖库

| 库 | 许可 |
|---|---|
| AndroidX RecyclerView、DynamicAnimation | Apache License 2.0 |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) 2.3.1 | BSD 3-Clause |
| [ZXing](https://github.com/zxing/zxing) core 3.5.3 | Apache License 2.0 |

## 设计参考

- 首页、偏好面板和程序坞的交互参考了视频《我的自制安卓tv桌面》里展示的桌面。
- 「液态玻璃 2」的外观（边缘亮边、厚度暗弧、对角高光的层次和数值）参照 yzrt 前端效果合集里的「纯CSS液态玻璃」模板，该合集以 GPL-2.0 发布。AweHome 里是用 Android Canvas 重新画的（`ClearGlass.java`），仓库和安装包里没有这个模板的 HTML / CSS 文件。

AweHome 是独立的开源项目，与 Apple、Google、小米、TCL 等公司无关。
