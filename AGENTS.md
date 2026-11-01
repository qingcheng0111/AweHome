# AweHome：给 AI 代理的说明

Android TV 桌面，纯 Java，minSdk 26，包名 `com.gathin.tvhome`。底层是 cleanyourtv 的桌面，AweHome 在上面加了玻璃效果、背景、屏保、偏好面板等。

开始任何工作前先读：
1. `docs/ARCHITECTURE.md`：目录、上游代码和 AweHome 代码怎么分开、每个功能在哪个文件
2. `docs/DEVELOPMENT.md`：构建、版本号规则、发版、签名、在电视上测试的规矩
3. `docs/devices/*.md`：各台电视的限制
4. `local/`（如果存在）：电视的 IP 和本机信息，不要复制进版本库里的文件

## 命令

```bash
cd launcher
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
TV_HOST=<电视IP> STOCK_LAUNCHER=none ./build-and-install.sh
```

- 单元测试全过、lint 0 个错误才算构建通过。
- 连电视的命令（adb、tools/tv.py）在沙箱里会被拦，需要关闭沙箱。
- 同时连着电视和模拟器时，用 `ANDROID_SERIAL=<设备>` 指定 adb 的目标。

## 每次改动

- 升版本号：`versionCode` 加 1；`versionName` 是 `1.X.Y`，末位到 9 之后进位（`1.1.9` → `1.2.0`）。
- 安装包命名为 `AweHome-<versionName>.apk`。
- 汇报时写明版本号，并用 `adb shell dumpsys package com.gathin.tvhome | grep versionName` 核对电视上装的是哪一版。

## 代码约定

- 新代码、新资源放 `launcher/app/src/awehome/`；必须改上游文件（`src/main`）时，在改动处的注释里写 `AweHome`。
- AweHome 的界面文字放 `strings_awehome.xml`，6 种语言都要加：`values`（英）、`values-zh-rCN`、`values-zh-rTW`、`values-ja`、`values-ko`、`values-es`，占位符要一致。
- 注释用中文，标识符用英文。
- 播放器、网络、文件读写不要放在主线程：有的电视（小米 MFTP0）的播放器调用会同步等网络，放主线程会 ANR。
- 偏好里的设置要原地即时生效，不要靠重建界面（重建会闪黑屏）。

## 不要做

- 用户要一个开关或功能时，按字面做。不要自己加定时、自动触发、提示文字之类的额外行为；名称和行为对不上、或者有多种理解时，先问清楚。
- 不要卸载或停用系统应用；不要改电视的系统设置，测试时临时改的要改回去并说明。
- 有人在看电视时不要按键、不要装包。先确认前台是什么、遥控器是否空闲。
- 不要提交 `local/`、`tools/adbkey/`、电视的 IP、Wi‑Fi 名称、序列号、账号等信息；截图公开前遮住 Wi‑Fi 名称和城市。
- 不要把安装包提交进仓库，安装包放在 GitHub Releases。
- 不要改包名：电视上已装的版本会无法覆盖升级。
- 没有在电视或模拟器上验证过的功能，不要说「已完成」。
