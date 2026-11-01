# 主题

[返回 README](../README.md#开发)

主题是一个文件夹，里面放一个 `config.json`，可以再放壁纸。推到电视上以后，不用重新编译安装包就能改桌面的程序坞、布局和外观：桌面下次启动时，用 `config.json` 覆盖安装包里自带的 `config.xml`。

```
my-theme/
├── config.json        要改的设置
├── wallpaper.jpg      一张壁纸（可选）
└── wallpapers/        或者一组壁纸，按顺序轮换（可选）
    ├── 01.jpg
    ├── 02.jpg
    └── captions.txt   每张壁纸的说明文字（可选）
```

主题带了壁纸、你又没在偏好里选过背景时，首页默认显示主题壁纸；偏好 → 背景 →「主题壁纸」也能选它，屏保的「图片」里同样会列出来。

## 推送和还原

```bash
TV_HOST=192.168.1.50 tools/theme.sh my-theme      # 推送主题并重启桌面
TV_HOST=192.168.1.50 tools/theme.sh --reset       # 删掉主题，回到安装包自带的 config.xml
```

Windows：`powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 my-theme`。

也可以手动推：`adb push config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json`，再重启桌面。删掉这个文件就回到默认。

## config.json 的键

大多数键和 [`launcher/app/src/main/res/values/config.xml`](../launcher/app/src/main/res/values/config.xml) 里的名字一样，那里每个键上面都有注释说明作用和取值。壁纸相关的几个键（`wallpaper`、`wallpaper_dim`、`wallpaper_rotation_minutes`、`dock_opacity` 等）只在 `config.json` 里有，读取和取值范围见 [`Config.java`](../launcher/app/src/main/java/com/gathin/tvhome/Config.java)。没写的键保持默认值。一个例子：

```json
{
  "dock_apps": ["com.ktcp.video", "com.mitv.mivideoplayer"],
  "hidden_apps": ["com.mitv.gallery"],
  "columns": 5,
  "rows_below_dock": 0,
  "dock_opacity": 80,
  "wallpaper_rotation_minutes": 30,
  "wallpaper_dim": 20
}
```

隐藏电视上并不存在的信号源接口（例如主板报了 HDMI4、色差、VGA，电视上却没有这些口）：

```json
{
  "input_sources": ["HW8=-", "HW3=-", "HW4=-"]
}
```

偏好面板里改过的每行图标数等设置，优先于主题；推送主题或 `--reset` 会把这些电视上的选择一起清掉。

## 磁贴图

国产 App 大多没有给电视设计的横幅，磁贴上只有一个小图标。可以给它们换成自己做的 16:9 磁贴图（建议 640×360，PNG 或 JPG），文件名是 App 的包名，推到电视上的 `icons/` 目录：

```bash
adb shell mkdir -p /sdcard/Android/data/com.gathin.tvhome/files/icons
adb push com.example.app.png /sdcard/Android/data/com.gathin.tvhome/files/icons/
adb shell am force-stop com.gathin.tvhome   # 重启桌面才会重新读图；按主页键回到桌面即可
```

取图的顺序：手机传图页上给这个 App 换的图 → 推来的磁贴图 → App 自带的电视横幅 → 图标加名字。删掉推来的文件就恢复原样。

App 的名称和 logo 归各自的权利人所有，自己做的磁贴图放在自己的电视上用，不要提交进本仓库。
