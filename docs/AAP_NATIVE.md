# 原生 AAP 出口

目标：车机 Android Auto **不再依赖** gearhead 进程与 DexKit 钩子（避免 AA 强制更新后钩子点位漂移）。

## 通道

状态页「投到哪里」：

| 选项 | 说明 |
|------|------|
| 原生 Android Auto（AAP） | 本模块说 AAP；画面 = 现有分屏 VD |
| Android Auto（原路 / gearhead） | 旧路径，仍需 LSPosed 勾 AA |
| CarPlay 无线 | 现有无线 CarPlay |

已安装 AA 时默认 **AAP**；未安装则默认 CarPlay。

## 已接能力（档 1 / AADisplay 核心）

| 能力 | 实现 |
|------|------|
| 无线 / USB 建连 + TLS | `AapSourceSession` + CarService 证书提取；有线 AOA 见 `AapUsbAccessoryActivity`（先提证书、`sendPings=false`、pending attach） |

### 用 Google DHU 测有线 AAP

1. 状态页选 **原生 Android Auto（AAP）**，确认 CarService 证书已提取  
2. Mac：`./desktop-head-unit --usb`（不要用默认 ADB/`Start head unit server`——那是 gearhead 当服务端，原生 AAP 当不了客户端）  
3. 手机应进 `AapUsbAccessoryActivity`；若之前因 filter 不匹配只弹了网页 URI，装本修复后 **重开 DHU** 或切一次 AAP 通道触发 `AapUsbProbe`  
4. DHU accessory 身份：`manufacturer=Android`，`model=Android Open Automotive Protocol`
| 主屏 H.264 + 双 VD 分屏 | `AapDisplayBridge`（复用 CarPlay GL/AVC） |
| 触控 | `AapTouchMapper` → `CoreApi.touchPane`；Recent/Picker 覆盖层优先 |
| 媒体 / 导航提示 PCM → 车机 | `AapAudioEgress`：`pullCarPlay*` → AAP `AudioChannel`；先 `AUDIO_FOCUS_GAIN`，等通知再泵 MEDIA；`LOSS_TRANSIENT_CAN_DUCK` 保流；GUIDANCE 优先于 SYSTEM |
| 仪表 Now Playing / 歌词 | `AapNowPlaying`：`ClusterLyricStore` → `MediaPlaybackChannel` |
| 方向盘媒体键 | `AapInputKeys`（短按媒体/音量；长按右换窗 / 左 Recent） |
| 旋钮相对轴 / 触控板 | `RelativeEvent` → `scrollPane`；`touchpad_event` 同触控路径 |
| 电量状态条 | `AapBatteryReporter` → control `BATTERY_STATUS_NOTIFICATION`（非 PhoneStatus） |

**本期不做：** 车麦、Google 助理、`PhoneStatus` 来电、蓝牙 HFP 配对、`NavigationStatusChannel`、Coolwalk/FacetBar 钩子（gearhead 专用）、telephony 音频 sink。

## 证书

车机 TLS 校验手机侧 **CarService** 证书。本模块：

1. 检测已安装的 `com.google.android.projection.gearhead`
2. 读取其 `sourceDir` / `splitSourceDirs` APK
3. 自动提取并保存到 `files/aap_certs/carservice/`
4. **刷新策略（版本号，非日历定时）：**
   - 状态页打开 / AAP arm / 开机与 BT ON（AAP 通道）会跑 `ensureExtracted`
   - `AaCertPackageReceiver` 监听 gearhead `PACKAGE_ADDED` / `PACKAGE_REPLACED`，版本 `versionCode` 变化则覆盖落盘
   - 同版本号不重提：叶证书过期只能靠**更新 AA** 再提取，同一 APK 解不出新证
   - 剩余不足 30 天或已过期：状态页告警（同版本 APK 无法续期，需更新 AA）
   - **卸载 AA 不删除**已落盘 CarService；连车继续用，直到过期再装较新 AA

算法移植自 AARE `extract_key.py`（GPL-3.0）。**不**把 Google 私钥打进发布包。

## 协议来源

`vendor/aare-aap`：vendored [prototux/AARE](https://github.com/prototux/AARE) `aap` 模块（GPL-3.0-or-later）。
