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
| 媒体 / 导航提示 PCM → 车机 | `AapAudioEgress`（焦点无应答重试 `FocusRetry`：1s 重问 / 2s×15 release+gain，任何回音即停，AARE v1.4.0 #143；车机收回焦点后再授权见下「音频焦点回收」） |
| 仪表 Now Playing / 歌词 | `AapNowPlaying` ← `ClusterLyricStore` |
| 方向盘媒体键 | `AapInputKeys`（短按媒体/音量；长按右换窗 / 左 Recent） |
| 旋钮 / 触控板 | `RelativeEvent` → `scrollPane`；touchpad 同触控 |
| 电量状态条 | `AapBatteryReporter` |

**本期不做：** 车麦、Google 助理、`PhoneStatus`、蓝牙 HFP 配对、`NavigationStatusChannel`、telephony audio sink。

### 音频焦点回收（收音机 → 切回 AA）

车机把音源切到收音机 / 电话时回 `AUDIO_FOCUS_STATE_LOSS`，很多 HU 用户切回 Android Auto 音源后**不会重新授权**——旧逻辑「任何回音即停问」之后 MEDIA 泵永不恢复，必须断连重连。现：

- 非授权回音（LOSS 等）→ `FocusRetry` **withdrawn**（停掉未答重试，不追着车机狂问）；
- `AapAudioEgress` 增 2s 低频侦测：无焦点时 pull 系统媒体环（drop-to-live ≤100ms）算 s16 峰值，>32（≈-40dB）判定「手机仍在出声」→ 回调 `AapSourceSession` 每 **10s** 重发 `AUDIO_FOCUS_REQUEST(GAIN, Priority.AUDIO)`，直到车机回授权（任何焦点回音 / 授权都重置 10s 耐心计时）。手机无播放则不打扰。

### 无线会话的蜂窝互联网（连车机 Wi‑Fi 不走车网）

车机 AP 无外网：手机挂上后默认路由若被它抢走，分屏里地图 / 微信等全部断网（无线 CarPlay 已有整套对策，见 [CARPLAY_WIFI_CELLULAR.md](CARPLAY_WIFI_CELLULAR.md)；**先 CarPlay 后切 AA** 时 CarPlay 已把车 AP 保存 + autojoin，手机自动挂在上面，AA 侧此前无对策）。现 `WirelessConnector` 对齐 CarPlay 姿态：

- WPP `connectWifi` 回调内先 `CoreApi.armCarAccessoryWifi(ssid, psk)`（持久保存 + `noInternetAccessExpected` + autojoin，同 CarPlay；兼防「未保存热点秒拆」）再 specifier 入网；
- 入网成功后：App 侧 `requestNetwork(TRANSPORT_CELLULAR + INTERNET)` 保蜂窝 NetworkAgent（会话结束随链路释放）+ system_server `preferCellularOverUnvalidatedWifi` 0/1.5/4s 三次清扫（OEM 会在 NetworkAgent 重建时再盖 `ACCEPT_UNVALIDATED`）。

## 证书

1. 检测已安装的 `com.google.android.projection.gearhead`
2. 读取 APK → 提取 CarService 叶证书到 `files/aap_certs/carservice/`
3. `AaCertPackageReceiver` 在 AA `versionCode` 变化时覆盖落盘
4. 卸载 AA **不删**已落盘证；过期需更新 AA 再提取

算法移植自 AARE `extract_key.py`（GPL-3.0）。**不**把 Google 私钥打进发布包。

## 协议来源

`vendor/aare-aap`：vendored [prototux/AARE](https://github.com/prototux/AARE) `aap` 模块（GPL-3.0-or-later），当前 pin `5ac79ba`。
