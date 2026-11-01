# 安装说明

[返回 README](../README.md#安装)

AweHome 需要 Android 8.0（API 26）及以上的 Android TV、电视盒子或投影仪。三星 Tizen、LG webOS、海信 VIDAA、Roku 这类不是 Android 的电视没有 ADB，装不了。

## 连接电视

1. 按 [README](../README.md#1-打开电视的-adb-调试) 打开电视的 ADB 调试，在「设置 → 网络」里查到电视的 IP。
2. 电脑上装好 `adb`（macOS：`brew install android-platform-tools`；Windows：下载 Google 的 Platform Tools）。
3. 连接：

```bash
adb connect 192.168.1.50:5555
adb devices          # 显示 192.168.1.50:5555  device 才算连上
```

电视弹出「允许 USB 调试吗？」时选「始终允许」。显示 `unauthorized` 说明电视上的提示还没点。

**只有「无线调试」的 Android 11 及以上电视**（没有 USB 调试或网络调试开关）不开放 5555 端口，第一次要配对：在电视上打开 开发者选项 → 无线调试 → 使用配对码配对设备，然后

```bash
adb pair 192.168.1.50:37115      # 配对页上显示的 IP:端口，再输入 6 位配对码
adb connect 192.168.1.50:39201   # 无线调试主页面上的另一组 IP:端口
adb tcpip 5555                   # 以后就能直接连 5555；tools/ 里的脚本也只认 5555
```

## 安装

### 用 Release 里的安装包

```bash
adb install -r AweHome-1.1.0.apk
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity
```

这样装不会停用电视原来的桌面。`install` 报 `INSTALL_FAILED_USER_RESTRICTED` 时，电视上找「安装未知来源的应用」或「USB 安装」之类的开关打开再试。

### 从源码编译安装

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
cd launcher
TV_HOST=192.168.1.50 STOCK_LAUNCHER=none ./build-and-install.sh
```

`build-and-install.sh` 先问电视的 Android 版本，再编译、推送、安装，启动一次确认能起来，最后设为默认桌面。

- 不加 `STOCK_LAUNCHER=none` 时，脚本会**停用**它认得的原桌面（`com.google.android.tvlauncher`、`com.google.android.apps.tv.launcherx`、`com.google.android.leanbacklauncher`、`com.mitv.tvhome`），这样主页键只能落到 AweHome。想恢复时运行 `adb shell pm enable <包名>`。拿不准就加 `STOCK_LAUNCHER=none`。
- 新桌面没能启动时，脚本不会动原桌面，并打印恢复命令；中途失败的记录写在 `~/awehome-handover-<电视IP>.txt`。
- Windows：`powershell -ExecutionPolicy Bypass -File build-and-install.ps1 -TvHost 192.168.1.50 -StockLauncher none`。

## 设为默认桌面时的几种情况

- **原生 Android TV**：按主页键时系统问「使用哪个应用」，选「主屏幕」（AweHome 在系统里的名字，英文系统里叫 Home），再选「始终」。
- **Google TV**：Home 有时由开机向导 `com.google.android.tungsten.setupwraith` 占着，脚本会停下来提示。不要随手停用它：有的电视上已保存的 Wi‑Fi 属于它，停用后电视会断网、ADB 也连不上。先用 `STOCK_LAUNCHER=none` 装好，再按主页键看系统给不给选择。
- **国产电视（小米、TCL 等）**：固件常把主页键锁在自家桌面上，`set-home-activity` 不一定生效。这时 AweHome 仍能从应用列表打开，当作普通 App 用。
- **Android 8.0 以下**：`minSdk` 是 26，装不上，脚本会在编译前就说明。

## 还原

先找到电视原来的桌面：

```bash
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

列出的每一行是 `包名/Activity`，除了 `com.gathin.tvhome/.MainActivity` 之外的那个就是原桌面。把它设回去：

```bash
adb shell cmd package set-home-activity --user 0 <包名>/<Activity>
adb shell pm enable <包名>          # 只有用脚本停用过原桌面时才需要
adb uninstall com.gathin.tvhome     # 不想留着 AweHome 时再卸载
```

也可以在电视上：设置 → 应用 →「主屏幕」（即 AweHome）→ 清除默认设置，再按主页键选原来的桌面。

## 用完关掉 ADB

ADB 的 5555 端口是明文的，同一网络里谁都能连。装好以后，建议在开发者选项里把 USB 调试 / 网络调试 / 无线调试关掉；以后升级时再打开。
