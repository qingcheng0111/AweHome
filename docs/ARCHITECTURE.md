# 代码结构

[返回 README](../README.md#开发)

## 目录

```
launcher/                       Android 工程（Gradle，纯 Java，minSdk 26）
├── app/src/main/               上游 cleanyourtv 的桌面代码，以及 AweHome 改过的上游文件
├── app/src/awehome/            AweHome 新增的全部代码和素材
│   ├── java/                   新增的类（包名同样是 com.gathin.tvhome）
│   ├── res/                    图标、横幅、按键音、strings_awehome.xml（简中、繁中、英、日、韩、西 6 种语言）
│   ├── assets/                 内置照片、航拍片单、国内城市表、液态玻璃位移贴图、手机传图页面
│   └── art/make_awehome_icons.py   生成应用图标和电视横幅的脚本
├── app/src/test/               JVM 单元测试（./gradlew testReleaseUnitTest）
├── build-and-install.sh / .ps1 编译、安装并设为默认桌面
tools/
├── tv.py                       Python 写的 ADB 客户端（adb_shell），脚本都靠它连电视
├── b64push.py                  把文件分块推到电视（不依赖 adb push）
├── theme.sh / theme.ps1        推送主题（config.json 和壁纸），见 THEMES.md
└── requirements.txt            上面这些脚本的依赖，装进 tools/venv
docs/                           文档、截图和品牌图片
```

## 上游代码和 AweHome 代码怎么分开

- `app/build.gradle` 的 `sourceSets` 把 `src/awehome/java`、`res`、`assets` 加进构建。两边包名相同，类互相可见。
- AweHome 改过的上游文件（`MainActivity`、`Config`、`AppAdapter`、`DockDecoration`、首页布局、`config.xml`、清单等）仍在 `src/main` 的原处，改动处在注释里用 `AweHome` 标出，方便以后对照上游更新。
- `values-ja/strings.xml` 是上游字符串的日语翻译，放在 `src/main`，和其他语言在一起。
- `src/main` 和 `tools/` 注释里的 `#数字`、`docs/…` 路径指的是 [cleanyourtv](https://github.com/Gathin23/cleanyourtv) 仓库里的 issue 和文档。
- 包名仍是上游的 `com.gathin.tvhome`：改包名会让电视把它当成另一个 App，已装的用户无法覆盖升级，设置和布局也会丢。

## 功能与代码

「来源」一列：**新写**是 AweHome 自己写的；「上游」指 cleanyourtv；**移植 aerial-tv** 指照 [aerial-tv](https://github.com/pengchujin/aerial-tv) 的 Kotlin 代码改写成 Java。

| 功能 | 来源 | 主要文件 |
|---|---|---|
| 顶栏四个胶囊：信号源 / Wi‑Fi / 偏好 / 设置；焦点所在的那个展开带文字，其余收成圆形；焦点离开顶栏时全部收回（按钮打开的面板开着时保持展开）；颜色随壁纸变化 | cleanyourtv 已有的胶囊和 `icon_only_*`，**新写**展开、收起与取色逻辑 | `TopPills.java`、`Blur.tint/vivid` |
| 「偏好」玻璃面板（16 项设置，多级页面，返回键回到上一页） | **新写** | `PrefsPanel.java`、`PanelUi.java`、`PanelWindow.java` |
| 模糊效果：磨砂玻璃 / 液态玻璃 / 液态玻璃+普通字体，统一控制程序坞底板、右上角按钮、按钮打开的桌面面板（信号源、偏好、齿轮下拉），切换后立即生效。**液态**：移植 [liquid-glass-react](https://github.com/rdev/liquid-glass-react)（MIT）：背后画面轻度模糊（6 网页像素）、饱和度 140%，再按它的位移贴图（standard 模式）向外取样，越靠边取得越远，玻璃外的景物被「吸」进边缘，红绿蓝三通道位移各差 10%（色散）；边框照它的两层做：1.5dp 一圈，里面有 135° 渐变、顶部内发光和最外缘 0.5dp 的白线，先各自叠好，再一层滤色（透明度 0.2）、一层叠加混合到画面上，看起来是隐约的一圈白光；边框按 dp 换算（网页在 2 倍屏上看，与电视 density 2 一致），位移等其余参数按 1920 宽的屏幕换算，都是它的默认值；跟随鼠标的弹性和悬停高光在遥控器上用不上，没有移植。为了电视上看得清，按钮玻璃最亮压到 0.78、面板略压暗到 0.9。**液态玻璃 2**：照一份纯 CSS 液态玻璃模板（yzrt「纯CSS液态玻璃」）：玻璃本身透明（模板外层的 `contrast(3)` 让 `backdrop-filter` 取不到背景，所以浏览器里也是透明的），背后的视频或照片原样透出，效果全在边缘：四边 0.9 白的锐利亮边、贴边暗边、下半部模糊 8px 的厚度暗弧、左下右上两块模糊 3px 的白色高光、内侧 0.2 白细环、程序坞下方的投影，数值照抄、网页 1px 按 1dp；这些层在软件画布上画成一张图按尺寸缓存（`ClearGlass.java`）。面板上文字多，垫一层轻度模糊的截图，再按模板加 4% 白、亮度 0.9。**磨砂**：参数由视频逐像素测量：重度模糊（约 36 像素），饱和度降低、程序坞和面板压暗，发灰；边缘一圈柔和光晕 | 液态**移植 liquid-glass-react**（视频背景在 GPU 着色器里每帧算，图片背景和面板用 CPU 按低分辨率算）；磨砂**新写**（软件 StackBlur，兼容 Android 8/9 电视） | `LiquidGlass.java`、`ClearGlass.java`、`assets/liquid-glass/`、`GlassLook.java`、`GlassPanel.java`、`Blur.java`、`TopPills.java`、`DockDecoration.java`、`FilmRenderer.java` |
| 背景：主题壁纸（用 `tools/theme.sh` 推送的主题自带的 `wallpaper.jpg` 或 `wallpapers/` 合集）；没在偏好里选过背景、主题又带了壁纸时默认显示它，与上游的 `WallpaperPlayer` 一样支持 `wallpaper_dim`、`wallpaper_moods` 和 `captions.txt`，换图间隔用偏好里的「更换背景」 | cleanyourtv 已有主题壁纸，**新写**为背景的一个来源 | `Backgrounds.themeItems`、`HomePrefs.bgSource` |
| 背景：内置照片 | cleanyourtv `themes/nature-calm` 的 7 张照片（CC BY/BY‑SA，署名显示在右上角） | `assets/backdrops/` |
| 背景：必应精选 | **移植 aerial-tv**（`Wallpapers.refreshDaily`） | `Backgrounds.java` |
| 背景：热门影视（可选地区） | **新写**（爱奇艺公开榜单，16:9 剧照） | `HotShows.java` |
| 背景：墙纸文件夹 + 手机传图（扫码上传、电视上点图删除、自定义 App 图标、设天气城市） | **移植 aerial-tv**（`PhoneServer`，NanoHTTPD + ZXing，带随机令牌）；端口从 8791 起，被占用就顺延，避开 Aerial 自己的 8790 | `PhoneServer.java`、`assets/web/phone.html`、`CustomIcons.java` |
| 背景：午夜蓝、海洋蓝等颜色 | **新写**（电视端绘制的渐变） | `Backgrounds.gradient` |
| 背景：航拍视频（Apple TV 航拍影片在线播放，地名随画面变化；「选择墙纸」里按全部 / 风景 / 城市 / 太空 / 水下选分类） | **移植 aerial-tv**（片单 `assets/aerials.json` 与 `Aerials.kt`、`VideoWallpaper.kt` 的做法），播放部分改用系统 MediaPlayer，所有播放器调用放在独立线程；没有网络时直接退回图片，一部影片 12 秒内准备不好、或播放中缓冲超过 15 秒就换一部，连续 3 部失败退回图片 | `AerialFilm.java`、`Backdrop.java` |
| 视频背景下程序坞、右上角按钮和按钮打开的面板（偏好、信号源菜单、长按菜单）的玻璃实时跟着画面流动 | **新写**：视频由自己的 OpenGL 渲染器画到 TextureView 上；每帧把画面缩到 1/8，在 GPU 上做两遍高斯模糊（液态 sigma 2.2、磨砂 4.5，相当于全屏约 18 与 36 像素），再按各块玻璃的圆角矩形和颜色变换画回视频层。程序坞和按钮只登记自己的位置、画边缘。主线程不再抓帧或模糊；GL 不可用时退回原来每 4 秒抓一帧的做法。面板是单独的窗口、盖在首页界面上面：面板里放一个 TextureView，渲染器用同一个 GL 环境每帧把这块玻璃画进去，首页上压在面板下面的界面另截一张不含视频的透明截图、模糊后叠在玻璃上，所以面板既跟着视频动，又不会露出下面清晰的界面 | `FilmRenderer.java`、`FilmGlass.java`、`GlassPanel.java`、`Blur.interfaceSnapshot`、`DockDecoration.java`、`GlassLook.java` |
| 图片背景下换图时，程序坞和按钮的玻璃跟着画面一起交叉淡入（700 毫秒） | **新写** | `DockDecoration.java`、`GlassLook.Pill` |
| 选择墙纸（带缩略图）、更换背景间隔、交叉淡入、图片名称 | cleanyourtv 已有轮换与标题，**新写**数据源与选择页 | `Backdrop.java` |
| 屏幕保护：时钟 / 图片 / 视频，在首页按返回键打开选中的那个（在应用网格里按返回，先回到程序坞）；选「图片」会进入下一级，选择全部分类或主题壁纸（主题带壁纸时才列出）、内置照片、必应精选、热门影视、墙纸文件夹中的一类 | 时钟屏保是 cleanyourtv 自带的 `ClockActivity`；**新写**图片屏保（轮播背景各分类的照片）和视频屏保；返回键开屏保参照 aerial-tv 的 `backStartsScreensaver` | `Screensavers.java`、`PhotoScene.java`、`PhotoActivity.java`、`VideoActivity.java` |
| 偏好里的设置一律原地即时生效、不闪屏：桌面布局、程序坞背景、程序坞标题、模糊效果、背景等直接改首页；每行图标数原地重排网格、程序坞和图块尺寸，不再重建首页；语言也不重建：直接把首页资源换成新语言，原地刷新顶栏按钮、日期与上午/下午、天气、图片和航拍地名、文件夹名，偏好面板随后用新语言重画（必应精选会按新语言的市场重新取一批图，取到后渐变过去）。面板选项行的白底随焦点瞬间切换，不再淡入淡出 | **新写** | `MainActivity.applyPref`、`applyColumns`、`applyLocale`、`Backdrop.relocalize`、`PanelUi.rowBackground` |
| 按键声音 | **移植 aerial-tv**（`Sounds.kt` 及 4 个 wav） | `KeySounds.java` |
| 桌面布局（是否露出第二排）、程序坞背景、程序坞标题、每行 4/5/6 个 | cleanyourtv 已有 `rows_below_dock`/`dock_band`/`dock_title`/`columns` 配置，**新写**电视端开关与即时生效 | `HomePrefs.java`、`Config.java` |
| 语言：简中、繁中、English、日本語、한국어、Español，即时切换 | cleanyourtv 已有 `locale` 与 30 种语言资源，**新写**语言页和日语资源 | `values-ja/` |
| 桌面天气（城市自动定位或手动设置，地名随语言变化） | **移植 aerial-tv**（Open‑Meteo + ipinfo + 中国城市表） | `Weather.java`、`assets/cities-cn.tsv` |
| 开机自启（可选任意 App） | cleanyourtv 已有 `OpenOnStart`，放进了偏好面板 | `PrefsPanel.bootPage` |
| 信号源名称（13 种预设设备 + 自定义输入框），显示为「名称 + HDMI1 徽标」 | **新写**（设备清单参照 aerial-tv 的 `SourceDevice`） | `InputLooks.java`、`NameDialog.java` |
| 信号源列表里的 投屏 / TV / DTMB / AV / USB；行数多时可滚动 | **移植 aerial-tv**（`SystemState` 的小米路由与 USB 播放器）；小米电视上 HDMI 与 AV 在没配 `input_sources` 时走 `EXTSRC_PLAY`（`xiaomiRoute`：`hdmiN_order` 或 HW 号 + 18，AV 为 2） | `InputLooks.java`、`MainActivity.showInputs`、`MainActivity.openInput` |
| 立即检查更新 | **移植 aerial-tv**（`Updates.kt`）；需在 `res/values/strings_awehome.xml` 的 `update_url` 填发布地址，否则显示「未设置更新地址」 | `Updater.java` |
| 长按菜单：添加到程序坞、四向移一格（菜单不关）、移到文件夹、应用信息 | cleanyourtv 已有移动与程序坞逻辑，**新写**菜单界面 | `TileMenu.java` |
| 文件夹、隐藏文件夹、打开时背景模糊 | **移植 aerial-tv** 的文件夹模型（`LayoutStore`），**新写**界面 | `Folders.java`、`FolderArt.java`、`FolderOverlay.java` |
| 进入网格时背景模糊（背景是航拍影片时照常播放、清晰显示，不模糊）、标题区让位、程序坞行置顶 | **新写** | `Backdrop.setGridMode`、`MainActivity.setGridLayout` |
| 隐藏程序坞看壁纸：程序坞上按上键，程序坞连同选择框、程序标题、环境光淡出（0.3 秒）；再按上键到右上角按钮；按钮上按下键，按钮收起、程序坞仍隐藏；再按下键程序坞淡入，选中原来的图标。隐藏时左右键不动，确认、返回、菜单键只把程序坞显示出来；视频背景下程序坞的实时玻璃跟着一起淡出淡入；离开首页再回来照常显示 | **新写** | `MainActivity.hideDock`、`showDock`、`onTileVerticalKey`、`FilmGlass.Region.alpha` |
| 程序坞常驻（偏好里「程序坞常驻」：开启 / 关闭，默认关闭）：开启时程序坞不会隐藏，在程序坞上按上键直接到右上角按钮；关闭时照旧，按上键先把程序坞淡出隐藏。没有定时隐藏 | **新写** | `MainActivity.dockStays`、`PrefsPanel`、`HomePrefs.DOCK_STAYS` |

## 与上游 cleanyourtv 的主要差异

cleanyourtv 的桌面刻意做得很小：不联网、不模糊、不放视频，安装包限制在 576 KB 以内。AweHome 走的是另一条路：

- 加了 `INTERNET` 和 `REQUEST_INSTALL_PACKAGES` 权限：天气、必应精选、热门影视、航拍影片、手机传图、检查更新都要联网。
- 新增依赖 NanoHTTPD（手机传图的网页服务）和 ZXing（二维码）。
- 安装包约 3 MB，主要是 1.9 MB 的内置照片和 196 KB 的城市表。
- `config.xml` 的一些默认值改了：图标化的顶栏按钮、玻璃程序坞、长按菜单、每行 5 个；关掉了环境光（`ambient_glow`），免得选中程序坞时整个背景被图标颜色染色。
- 上游仓库里的去广告清单（`examples/`）、模块卡片插件（`addons/`）、主题合集和性能工具没有放进这个仓库，需要时去上游取。
