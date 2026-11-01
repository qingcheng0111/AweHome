# 开发与发版

[返回 README](../README.md#开发)

## 环境

- JDK 17，Android SDK platform 34、build-tools 34.0.0。macOS：`brew install openjdk@17 android-commandlinetools`，再 `sdkmanager "platforms;android-34" "build-tools;34.0.0"`。
- 脚本用的 Python 依赖装进 `tools/venv`：`python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`。

## 构建

```bash
cd launcher
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

- 产物：`launcher/app/build/outputs/apk/release/app-release.apk`，发出去时改名为 `AweHome-<versionName>.apk`。
- 单元测试必须全过，lint 必须是 0 个错误（警告不拦）。
- release 版开了 R8 压缩和资源裁剪。电视上测试一律用 release 版，debug 版在电视上明显更卡。

## 装到电视或模拟器

```bash
cd launcher
TV_HOST=<电视IP> STOCK_LAUNCHER=none ./build-and-install.sh
```

或者直接 `adb install -r app/build/outputs/apk/release/app-release.apk`。同时连着电视和模拟器时，`adb` 要用 `-s` 或 `ANDROID_SERIAL` 指定设备。

开发时常用的 Android TV 模拟器：1080p、Android 9 的 TV 镜像，`emulator -avd <名字> -no-window -no-audio` 在后台跑，`adb exec-out screencap -p > shot.png` 截图。

## 版本号规则

`launcher/app/build.gradle` 的 `versionCode` 和 `versionName`，**每次改动发出去都要升**：

- `versionCode` 每次加 1。
- `versionName` 是 `1.X.Y`，末位 Y 只用 0–9，每次加 1；到 `1.X.9` 之后进一位到 `1.(X+1).0`，例如 `1.1.9` 的下一版是 `1.2.0`。
- 历史：1.0.15 及以前叫 `3.2-plus.N`；1.0.16 到 1.0.23 是旧规则（末位一直加）；1.1.0（versionCode 35）起用上面的规则。

## 发版

1. 升版本号，构建并在电视上验证。
2. 打标签并在 GitHub 发布，附上改好名的安装包：

```bash
cp launcher/app/build/outputs/apk/release/app-release.apk AweHome-1.1.0.apk
gh release create v1.1.0 AweHome-1.1.0.apk --title "AweHome 1.1.0" --notes "这一版改了什么"
```

安装包不提交进仓库（`.gitignore` 已排除 `*.apk`），只放在 Releases 里。

## 签名

release 版目前用构建这台电脑的 debug 密钥签名（`build.gradle` 里 `signingConfig signingConfigs.debug`）。Android 只允许同一个签名的安装包覆盖升级，所以：

- 换一台电脑构建出来的安装包，装不到已经装了 AweHome 的电视上（会报签名不一致），只能先卸载再装，设置会丢。
- 以后要正式长期发布，应该生成一个专用的签名密钥，放在不进仓库的 `local/` 目录，`build.gradle` 从那里读。换密钥之后，已装的电视也要卸载重装一次。

## 应用内检查更新

偏好面板最下面的「立即检查」由 `Updater.java` 实现（照 aerial-tv 的 `Updates.kt` 写）。它读 `launcher/app/src/awehome/res/values/strings_awehome.xml` 里的 `update_url`，**目前是空的**，所以电视上显示「未设置更新地址」。`update_url` 可以是：

- GitHub 的最新版本接口，例如 `https://api.github.com/repos/qingcheng0111/AweHome/releases/latest`：用 Release 的标签和当前版本名比较，第一个 `.apk` 附件就是下载地址；
- 或者一个 JSON 文件：`{"versionCode": 36, "versionName": "1.1.1", "url": "https://…/AweHome-1.1.1.apk"}`。

下载下来的安装包必须包名相同、签名相同、版本更新，否则电视的安装器会拒绝。

## 在电视上测试的规矩

- 有人在看电视时不要按遥控器的键、不要装包。先看前台是什么、遥控器最近有没有在用。
- 不要改电视的系统设置；测试时临时改的，测完改回去。
- 不要停用或卸载系统应用。
- 电视的 IP、截图、备份放在 `local/`、`shots/`、`backup/`，这些目录不进仓库。截图公开前要遮住 Wi‑Fi 名称、城市和账号。
