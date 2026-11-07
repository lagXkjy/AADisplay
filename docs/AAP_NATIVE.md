# Android Auto（AAP）出口

目标：车机 Android Auto 走本模块 **自定义 AAP 协议**，**不再**依赖 Google gearhead 进程、DexKit 钩子或 Car SDK 投影壳。

**Google 原路已硬下线：** 无 gearhead LSPosed scope、无 `AndroidAutoHook`、Manifest 不再导出 `AaActivityService`。残留 `channel=0` / `aa` 自动回落 `defaultChannel()`（已装 AA → AAP，否则 CarPlay）。遗留源码清单见 [GEARHEAD_LEGACY.md](GEARHEAD_LEGACY.md)。

## 通道

状态页「投到哪里」：

| 选项 | 说明 |
|------|------|
| Android Auto（AAP） | 画面+音频+仪表歌词+方向盘；**默认**（已装 AA） |
| CarPlay 无线 | 现有无线 CarPlay；未装 AA 时默认 |

已安装 AA 时默认 **AAP**；未安装则默认 CarPlay。装 AA **只为** CarService 证书提取，**不必**勾 LSPosed 的 Android Auto。

## 已接能力（档 1 / AADisplay 核心）

| 能力 | 实现 |
|------|------|
| 无线 / USB 建连 + TLS | `AapSourceSession` + CarService 证书提取；有线 AOA 见 `AapUsbAccessoryActivity` |

### 用 Google DHU 测有线 AAP

1. 状态页选 **Android Auto（AAP）**，确认 CarService 证书已提取  
2. Mac：`./desktop-head-unit --usb`（不要用默认 ADB/`Start head unit server`）  
3. 手机应进 `AapUsbAccessoryActivity`；必要时切一次 AAP 触发 `AapUsbProbe`  
4. DHU accessory：`manufacturer=Android`，`model=Android Open Automotive Protocol`

| 主屏 H.264 + 双 VD 分屏 | `AapDisplayBridge`（复用 CarPlay GL/AVC） |
| 触控 | `CarPlayInputBridge.injectNormalized` → pane 本地坐标 |
| 媒体 / 导航提示 PCM → 车机 | `AapAudioEgress` |
| 仪表 Now Playing / 歌词 | `AapNowPlaying` ← `ClusterLyricStore` |
| 方向盘媒体键 | `AapInputKeys`（短按媒体/音量；长按右换窗 / 左 Recent） |
| 旋钮 / 触控板 | `RelativeEvent` → `scrollPane`；touchpad 同触控 |
| 电量状态条 | `AapBatteryReporter` |

**本期不做：** 车麦、Google 助理、`PhoneStatus`、蓝牙 HFP 配对、`NavigationStatusChannel`、telephony audio sink。

## 证书

1. 检测已安装的 `com.google.android.projection.gearhead`
2. 读取 APK → 提取 CarService 叶证书到 `files/aap_certs/carservice/`
3. `AaCertPackageReceiver` 在 AA `versionCode` 变化时覆盖落盘
4. 卸载 AA **不删**已落盘证；过期需更新 AA 再提取

算法移植自 AARE `extract_key.py`（GPL-3.0）。**不**把 Google 私钥打进发布包。

## 协议来源

`vendor/aare-aap`：vendored [prototux/AARE](https://github.com/prototux/AARE) `aap` 模块（GPL-3.0-or-later）。
