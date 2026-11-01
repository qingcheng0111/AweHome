# Redmi 电视 MiTV-MFTP0（Android 9）

[返回 README](../../README.md#实测过的机型)

固件 23.7.11.6589。AweHome 的主要测试机，日常使用。

## 这台电视的特点

- `screencap` 截出来是全黑的，要用 `screenrecord` 录屏再取帧。
- 没有 Android 的屏保服务（`Can't find service: dreams`），小米自己的屏保应用停用后就没有屏保了，所以 AweHome 的三种屏保都由桌面自己显示，不需要额外权限，也不改系统的屏保设置。
- 播放器是厂商的（`player type = 210`），有些调用会同步等网络。AweHome 把所有播放器调用放在单独的线程里，否则视频背景会让桌面无响应（ANR），系统随即结束桌面、清掉默认桌面。
- HDMI 和 AV 没有应用处理标准的 channel URI，要走小米的 `EXTSRC_PLAY`（见 `MainActivity.openInput` 和 `InputLooks.java` 里的小米路由）。
- 主板报出 HDMI4（HW8）、色差（HW3）、VGA（HW4），电视上其实没有这些口。推一个只隐藏这三个口的 `config.json` 就行，命名页也会跳过它们：

```json
{
  "input_sources": ["HW8=-", "HW3=-", "HW4=-"]
}
```

```bash
adb push config.json /sdcard/Android/data/com.gathin.tvhome/files/config.json
```

## 实测结果

| 项目 | 结果 |
|---|---|
| 首页：天气、时钟、按钮取色、磨砂程序坞、照片署名 | 正常 |
| 信号源：HDMI1 显示 CEC 设备名「Apple TV」、HDMI2–4 徽标、投屏、TV、DTMB、AV、USB | 列表可滚到底；HDMI1 → 源 23，AV → 源 2，TV → `AtvActivity`，DTMB → `DTMBActivity`，投屏 → `smartshare`，USB → `mediaexplorer` |
| 信号源命名 | 列表显示「Switch · HDMI2」这样的名称加徽标 |
| 隐藏没有的接口 | 信号源列表剩 HDMI1–3、投屏、TV、DTMB、AV、USB；命名页剩 HDMI 1–3 |
| 偏好面板（液态玻璃） | 正常 |
| 按键声音 | 能听到 |
| 手机传图端口 | 8791（Aerial 占用 8790 时顺延） |
| 程序坞背景、桌面布局、模糊效果、程序坞标题 | 切换后立即生效 |
| 语言切换（录屏逐帧检查） | 简体中文 → English → 简体中文，无黑帧、无突变，文字原地换 |
| 视频背景下的实时玻璃 | 稳定播放 15 秒共 453 帧，掉帧 0，99% 的帧在 8 毫秒内 |
| 航拍视频背景 | 正常播放、换片、按分类切换；面板玻璃透出视频画面；进网格时暂停并显示模糊帧 |
| 返回键开屏保 | 时钟、图片、视频三种都能打开，按任意键回到首页 |
