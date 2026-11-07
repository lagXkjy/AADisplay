# Changelog

## Unreleased — Google gearhead 原路硬下线（AAP 替代）

- **运行时硬切：** LSPosed scope 去掉 `gearhead`；`XposedInit` 不再装 `AndroidAutoHook`；Manifest 移除 Car SDK 投影入口（`AaActivityService` / `gms.car.application`）与 `:cluster` Title 壳。
- **通道：** 残留 `CHANNEL_AA` 永迁 `defaultChannel()`（已装 AA → AAP）；状态页仅 AAP / CarPlay；方向盘 / 仪表歌词走 AAP。
- **删除退役 gearhead 钩子源码：** `xposed/hook/aa/**`（含 Frx/Maps/TTS、`AaUiHook`/Coolwalk DexKit、签名/媒体/仪表 Egress）、`AndroidAutoHook`、`DexKitMethodCache`、`AaDisplayProcessHook`；去掉 DexKit 依赖。
- **上层冗余清理（AAP 自渲染后）：** 删除 Car SDK 壳（`AaDisplayActivity*` / `AaActivityService` / `AaMainFragment` / Recents RecyclerView UI）、`aauto.aar`、Coolwalk 轨带结算（`util/coolwalk/` / `CoolwalkRailStore` / `DisplayProfileSettle`）、`ClusterLyricMediaService`；`resolveDisplayProfile` 只锁客户端上报尺寸；Coolwalk / presentation 触控 AIDL 改 no-op；Binder 桥仅本模块 uid。BT 鼠标车机精灵随壳移除（pane 注入仍有效）。
- 文档：`docs/AAP_NATIVE.md`、`docs/GEARHEAD_LEGACY.md`、`archive/gearhead-aa/`；`COOLWALK_FACETBAR.md` → `docs/archive/`。
- 清理未使用的 `AapTouchMapper`；`ClusterLyricMirror` 不再 warmStart Title 壳。
- **死路径再清：** 删壳广播常量与无接收方发送；`registerAaUiBroadcastReceivers` / `HidShellGeometry` / `useGearheadAa` / `tryTouchAaDisplay*` / `automotive_app_desc.xml` / `rb_channel_aa` / `androidx.media`；`EXECUTION.md` / `CLUSTER_LYRIC_CLOCK.md` 去掉仍当活路径的 gearhead 叙述。

## 0.3#17.8-r6 — AAP 核心体验（音频 / 仪表 / 方向盘）

versionCode **3106** / versionName **0.3#17.8-r6**。原生 AAP 对齐 AADisplay 原通道核心体验（档 1）：车机听得到、仪表有歌词、方向盘媒体键可用。

- **AAP 证书刷新：** 监听 gearhead 安装/更新自动 `ensureExtracted`；按 `versionCode` 替换落盘；不足 30 天/过期状态页告警；卸载 AA 保留已提取 CarService。
- **AAP 有线 AOA：** 插线先提 CarService 再 `openAccessory`；USB `sendPings=false`；FGS 未就绪时 pending attach；补 accessory 权限请求。
- **DHU `--usb`：** accessory model 实为 `Android Open Automotive Protocol`（不是 `Android Auto`），补 filter + `AapUsbProbe` 扫已挂接配件；否则系统只弹 `UsbAccessoryUriActivity`。
- **AAP 有线不出画：** probe 无权限时会要权；TLS 前收到加密帧则关 fd 重试；状态页 radio 默认值不再误切回 gearhead；USB 会话中不把状态盖成「监听无线」。
- **AAP 触控：** 按分屏比例路由到对应 pane 本地坐标（复用 `CarPlayInputBridge`）；不再把整幅画面坐标塞进 `focusedPane`。
- **AAP 应用选择器：** `AapDisplayBridge` 补 `CarPlayAppPickerOverlay.bindContext`（原先无 Context，列表恒为空）。

### Added
- **`AapAudioEgress`：** 复用 `CarPlayAudioCapture`（`pullCarPlayAudio` / `pullCarPlaySpeechAudio`）泵入 AAP `AudioChannel`（MEDIA 48k 立体声 / GUIDANCE·SYSTEM 16k 单声道）；`requestAudioFocus` + focus 启停媒体泵。
- **`AapNowPlaying`：** `ClusterLyricStore` / `ClusterArtStore` → `MediaPlaybackChannel`（歌词行作 song、曲 id 稳定、整秒进度、封面）。
- **`AapInputKeys`：** MEDIA 短按 + 长按 NEXT 换窗 / PREV Recent；Recent/Picker 覆盖层触控优先于 VD。
- **协议补漏：** PCM 每包独立缓冲（避免 `sendData` 异步复用踩内存）；仅在 channel `ready` 后 `startPlayback`；`LOSS_TRANSIENT_CAN_DUCK` 保 MEDIA；GUIDANCE 优先 SYSTEM；断开 `AUDIO_FOCUS_RELEASE`；`RelativeEvent`/`touchpad`；电量 control 通知；音量键转发。

### Notes
- 仍不做车麦 / 电话 / 导航指引 / Google 助理。
- 遗留 `CHANNEL_AA` 仅作迁移哨兵。

## 0.3#17.8-r5 — 原生 AAP 出口（摆脱 gearhead 钩子）

versionCode **3105** / versionName **0.3#17.8-r5**。新增 **原生 Android Auto（AAP）** 通道：用 vendored [AARE](https://github.com/prototux/AARE) 协议栈直接连车机，VirtualDisplay 出画，**运行时不经过 gearhead DexKit 钩子**。

### Added
- **`CHANNEL_AAP`：** 状态页「原生 Android Auto（AAP，不依赖钩子）」；与 AA gearhead / CarPlay 互斥；默认在已安装 AA 时优先 AAP。
- **证书自动提取：** 从**已安装**的 `com.google.android.projection.gearhead` APK 路径扫描 DEX，复现 AARE `extract_key.py` KDF，落盘 CarService 证书（AA 更新后点「重新提取」）。无手动选文件；发布包不内置 Google 私钥。
- **无线建连：** BT RFCOMM bootstrap + Wi‑Fi + TCP；USB AOA 入口（`AapUsbAccessoryActivity`）。
- **出画 / 触控：** 复用 CarPlay GL 分屏 + AVC 编码器，帧送 AAP `VideoChannel`；触控经 `CoreApi.touchPane`。

### Notes
- 原厂 Google 库车机仍需有效 CarService 证书（安装 AA 后自动提取即可，不必启用 gearhead 钩子）。
- 遗留 `CHANNEL_AA`（gearhead）仍保留；仪表歌词等 AA Title 出口一期未接到 AAP（见 r6）。

## 0.3#17.8-r4 — 奥迪刚连声音、分隔条与键盘、4026 重连

versionCode **3104** / versionName **0.3#17.8-r4**。相对 r2：奥迪认领 MainAudio 后再发 AAC；分隔条命中与收起键盘对齐 AA；断开后恢复手机音量；MIB3 4026 不再被 outbound `…caff` 挡住首次 `…cafe`。

### Fixed
- **无线 CarPlay 刚连上声音不稳 / 交换分屏命中过宽 / 没有收起键盘 / 车机要连多次：** 奥迪 AAC 在第一次 `modesChanged` 认领 MainAudio 之后才发 RTP（认领超过约 2s 仍无 200 则照旧开流），开头不再把编码器预热静音帧送到车机。CarPlay 分隔条命中改为与 AA 相同的缝宽和上下 80dp 留白。Host 画布补「收起键盘」，抬手走已有 `hideIme`。握手停在 Detect 的 inbound `…cafe` 也会拆掉，下一次 accept 不再被当成已有会话丢掉。
- **断开 CarPlay 后手机音量键失效，关模块也要重启：** 播放采集用 system uid `setStreamMute` 把各流静音，并在音量变化时立刻打回 0。会话 live 以前只在 FGS 销毁时清掉，车机断开后前台服务仍在听，静音就留在 `system_server`。现于媒体链路真正结束后约 4 秒恢复音量（期间若重新 SETUP 则取消）；采集已卸但静音还在时，`setEnabled(false)` 也会清 mute / 细音量。
- **Audi MIB3 4026 首次连不上 / 断连后 62fps 空转（Xiaomi 15 API 36）：** inbound-preferred 在 2.5s cafe 等待窗内就把 `headUnitDialPending` 置位，4s `dialBonded` 抢拨 `…caff`，SYN 无 ACK 的 zombie socket 挡住车机 `…cafe`。改为等满 2.5s 再置 pending，ACL 不再 schedule dial，SYN 耗尽拆 RFCOMM。HU 走后 4s 停 Host/编码器（FGS 仍 listen）。API 36 `setStreamVolumeIndex` 走 4 参/`AS`；`createAudioRecordSource` 不写死 1 参；mute 日志只在 MUSIC 被抬起时打。可发现改经 system_server `armBluetoothDiscoverable`（须重启换 Stub）。

### Changed
- **Version bump to `0.3#17.8-r4`** (`versionCode` 3104)。

## 0.3#17.8-r2 — 有线 USB 归档 · CarPlay-only · 空闲不占 CPU

versionCode **3102** / versionName **0.3#17.8-r2**。相对 r1：公开主线只保无线 CarPlay + AA；有线假 iPhone 进 archive；无 AA 可默认走 CarPlay；切通道/武装不再空转吃满 CPU。

### Fixed
- **切无线 CarPlay 进程崩溃循环：** 未申请 `BLUETOOTH_SCAN`/`CONNECT` 时 `BluetoothAdapter.getScanMode` 抛 `SecurityException`，sticky FGS 反复重启。状态页切无线前弹运行时权限；BT 链路与 ProjectionService 对缺权限软失败，不再杀进程。
- **CarPlay 空闲占满 CPU 导致手机卡顿：** AutoArm / 切通道后在无车机时仍跑 720p60 MediaCodec + Host 双 VD + 音频采集（`streaming=false`、`dropMedia` 狂涨）。改为 listen-only（BT cafe + RTSP）；`prepareMediaPipeline` 延到 AirPlay dial / screen SETUP；GL renderTick 仅在 encoder attach 后启动；listen 时 `releaseOrphansIfIdle` 清掉 force-stop 残留 VD（抖音/地图继续 60fps 渲染）。

### Changed
- **无 Android Auto 也能用无线 CarPlay：** 未装 gearhead 时 `CarPlayChannel` 默认 `CHANNEL_CARPLAY`（首次写盘）；STOP 不再回落 AA；状态页隐藏 AA 电台并 arm FGS；BootArm 无持久化时同样按是否安装 AA 默认。README / AGENTS 拆出 CarPlay-only 安装路径（LSPosed 仅需系统框架）。
- **有线 CarPlay（USB）归档开源准备：** 实验室工程与文档移至 [`archive/usb-carplay/`](archive/usb-carplay/)；`CarPlayChannel.USB_CARPLAY_ENABLED=false`，状态页隐藏 USB 通道，残留 `cp-usb` 回落 Android Auto（不误触 FGS / USB 传输）。App 侧 `CarPlayUsb*` 仍保留便于日后开启。推进细节见 archive 内 `USB_CARPLAY.md`。
- **Version bump to `0.3#17.8-r2`** (`versionCode` 3102)。

## 0.3#17.8-r1

### Fixed
- **Android Auto 17.8 AutoOpen 失效：** gearhead 删除 `CarSystemUiControllerService`；`CarUiEntry.startCarActivity` 又会 `Unsupported intent` 拒 OEM 壳。改为 DexKit 定位 GH.ProjectionContext 启动器（针 `startCarActivity(intent:%s, activityOptions:%s)` → `(Intent, Options)V` + 静态单例 getter）；勿选同班 lifetime 空操作 `(Intent)V`。≤17.4 仍走 SysUi。媒体白名单 DexKit 针补充 `allow_unknown_sources` 等 17.8 字符串。（自 `AADisplay-Split-Lite` 合入）

### Changed
- **Version bump to `0.3#17.8-r1`** (`versionCode` 3101)。CarPlay 分支基于 r17，目标 Android Auto **17.8**；主版本 `0.24` → `0.3`。

## 0.24#17.4-r17 — 奥迪无线 CarPlay 音频稳定

versionCode **3077** / versionName **0.24#17.4-r17**。相对 r14：建连可靠、AAC 长播、方控语义、MainAudio 资源管理收口。

### Verify（r17 · Audi MMI + Z Flip）
- 长播不再每隔约半分钟糊几秒；方控短按切歌后再长按交换分屏，车机仍有声（AAC 仍流时不误 POST `modesChanged`）
- 开机 / 重装后 `…cafe` listen；bonded 配对不弹框、不掐 Auth
- Wi‑Fi handoff 不因 `setAcceptUnvalidated(false)` 拆掉 NetworkAgent
- 倒车 BORROW / 归还后喇叭与方控恢复

### Fixed
- **CarPlay 隔一会糊几秒 / 方控切歌后无声：** (1) 去掉 MIB3 每 30s `modesChanged` heartbeat（只保留建连 4/12/30s burst）— 周期性 POST 让奥迪混音器抖；(2) cadence-gap **不再** POST modesChanged — 切歌/交换分屏时源端 PAUSED+RTP 空窗再 reclaim 会闩死喇叭而 AAC 仍在发；(3) 方控长按在按住阈值（550ms）触发，松手不再兼发短按切歌。
- **车机配对手机无反应 / Auth 中途掉链：** inbound-preferred（Audi）在已 bonded 时每 4s 狂拨 `…caff`，与 `…cafe` accept / SSP 抢 ACL；配对过程被掐、Auth challenge 被记成 20/32 永久拉黑。改为 cafe 优先、bond 后 2.5s 一次性 fallback、BONDING 禁拨；新 RFCOMM 会话清 challenge 黑名单。
- **开机第一连 CarPlay 不上、再连才好：** app 停着时收不到 `BT STATE_CHANGED`（`Background execution not allowed`），`…cafe` 要等晚到的 `BOOT_COMPLETED`；车机已 SDP 失败退避。改为 system_server `CarPlayBootArm` 在 BT ON / 解锁时直接起 FGS。
- **Audi 无线 CarPlay 卡在 `WiFi handoff already pending`：** `preferCellularOverUnvalidatedWifi` 调 `setAcceptUnvalidated(false)` 会拆掉 WIFI NetworkAgent，wlan0 仍关联但 `getAllNetworks()` 无 Wi‑Fi，AirPlay 无法 `bindSocket`。去掉该调用；handoff 在 Specifier 无 Network 时主动 re-request。
- **CarPlay 方控左键短按/长按无效（奥迪 MMI）：** 左摇杆 HID `usage=6`（REWIND），不是 NEXT(4)/PREV(5)；右键 PREV(5)+invert 正常。把 REWIND/FF 并入左/右语义（短按切歌、长按左=应用栈、长按右=交换分屏）。
- **CarPlay AAC 播一会车机无声：** 根因是 `onCadenceGap` 在 AAC 发送线程里同步 `POST modesChanged`（`exchangeEncrypted` 等 control 回包），RTP 停转→再 realign→死循环；严重时 `pulledMedia` 卡死、`dropMedia` 狂涨。改为异步 reclaim + 8s 节流；空窗用缓存静音 AU；轻度落后只加速补发不 snap 时钟。
- **CarPlay 方控短按左右切歌无效：** `CarPlayWheelController.mediaKeyCode` 误写 130/129（`KEYCODE_MEDIA_RECORD`/`EJECT`），不是 87/88（`NEXT`/`PREVIOUS`）；日志里已 `pressKey(130)` 但 QQ 不响应。改为 `KeyEvent.KEYCODE_MEDIA_*`。
- **QQ 音乐播着切到抖音，无线 CarPlay 无声：** 抖音曾被单发声 pause 后，用户置顶时 `finishLaunchOnPane` 不 settle Av，且 `resumeFront` 因粘性 QQ 仍 PLAYING 直接 skip → 两边都停。改为用户置顶 AvMedia 时 `claimSpeakerForFront`（先 pause 其它再 play），`resumeFront` 同样抢权。
- **CarPlay 方控短按切歌无效 / 左右长按反了：** (1) `pressKey` 只往焦点 VD 注入，地图在前时 QQ 车载收不到 → 媒体键一律再走 `AudioManager.dispatchMediaKeyEvent`；(2) 奥迪 MIB3 物理右键报 HID PREV（左报 NEXT），长按右变成应用栈。MIB3 下 NEXT↔PREV 对调，与 AA 一致：长按右=交换分屏、长按左=应用栈。
- **CarPlay 倒车 BORROW 后无声+方向盘失效：** `changeModes` 对 `BORROW_CHANGE`（如 `backupCamera` 借 MainScreen）一律回「手机仍持有」，违反资源管理器；车机 RTP 还在收但喇叭/方控死掉。改为按 transferType 授予/归还（3=BORROW→entity=accessory，4=UNBORROW→permanentEntity），仅在资源回到手机后才 POST `modesChanged` 重申（中途抢回仍会打成上次那种 BORROW 死循环）。
- **音频/媒体加固：** (1) `CarPlayAudioCapture` 增加 session `desired`：MediaProjection `onStop` / 5min 无 pull 在会话仍要采集时 **keep-mute rearm**，不再中途 unmute；(2) `AvMediaArbiter.enforceSingleSounder` 成唯一 pause 入口，Mirror 仅在多路 PLAYING 时 pause（`force=false`），避免与分屏 restore 双 pause 抖；(3) 纠正「视频发声时仪表仍跟三源」的过时注释。
- **CarPlay 已配对仍每次弹蓝牙配对框：** app 无 `BLUETOOTH_PRIVILEGED`，`setPairingConfirmation` 失败后落到 Settings；更糟的是 `PAIRING_REQUEST` 在已 `BOND_BONDED` 时还会 `removeBond`，逼着重配。改为已知 HU 走 `prepareCarPlayHeadUnit`（system_server 静默确认 + abort）；已 bonded 车机跳过「可被发现」对话框。
- **状态页有时能滚有时不能：** edge-to-edge 只垫了顶栏 inset，ScrollView 按含导航栏的全高算「能放下」，底栏区域实际被挡且无滚动余量；CarPlay 状态文案变长时又突然能滚。改为四边 + IME inset。
- **打开状态页就闪一下「CarPlay 通道出画」然后像退出：** 通道已是 CarPlay 时 `ensureArmedIfCarPlay` / FGS 会拉起同 task 的 `CarPlayHostActivity`，800ms 后 `moveTaskToBack` 把整栈（含状态页）压到后台。Host 改独立 `taskAffinity`；状态页 re-arm 只起 FGS/Runtime、不再弹 trampoline。
- **CarPlay 奥迪 BORROW 之后一直无声：** 车机 `BORROW_CHANGE` 后除了 200 应答，又 POST 一次 `modesChanged` 把主音频抢回。车机立刻再 BORROW，随后 RTP 仍在发、喇叭不再出声。BORROW 只保留声明手机持有屏幕和主音频的 200，不再补发抢回。
- **CarPlay AAC 放着放着杂音然后没声：** RTP 时间戳经微秒往返整除后每帧 1024 样本变成 1023，抖动缓冲慢慢被拖空。改为每发出一帧精确 +1024。随后「落后就丢帧」在编码超过 21ms 时连着掏空发送队列，奥迪空窗后不再出声；改为先按节拍睡再编码，只在落后超过约 170ms 时对齐时钟，不丢已编码帧。
- **重启后 CarPlay 连不上：** `CarPlayAutoArmReceiver` 在 targetSdk 36 下 `exported=false`，系统不投递 `BOOT_COMPLETED` / `USER_UNLOCKED` / BT `STATE_CHANGED`，`…cafe` 无 listen，DiPlay SDP 失败。改为 `exported=true`。
- **DiPlay 音频落后好几秒（只改手机 Source）：** 真机 `DiPlay-AudioStats` 坐实 HU `queue=192`（≈3.8s）满时丢最新留最旧；首包触发 DiPlay `createTrack` 时我们仍在灌包，队列钉死。车机不动：media 首包后 settle 1.2s（丢采集），再 8s 按 22ms 软慢发泄残量；TX 前冲预缓冲；水位 100→40ms；`audioLatencyMs` 80 / screen `latencyMs` 60。
- **CarPlay 又突然没声音（手机也静音）：** `PcmRing.pull` 环绕拷贝误用 `available - off`，在 `available == 2*(capacity-tail)` 时 `chunk==0` 持锁死循环；Binder `pullCarPlayAudio` 全堵、采集写线程一起堵，egress 发不出 PCM 且 session-live 本地静音不解除。改为按 `min(take-off, available, capacity-tail)` 拷贝并 `chunk<=0` 退出；write 丢弃改为向上对齐，避免 `available > capacity`。
- **DiPlay 连上后声音仍在 Z Flip 扬声器（adb 坐实）：** `MediaProjection` 获取失败 → `remote-submix` → `skip local mute`（`phoneMuted=false`），车机有 PCM 但本机 SPEAKER 仍响。根因：system_server 同进程调 `BinderService.createProjection` 时 `checkCallingPermission` 因 `callingPid==myPid` 恒拒绝；三星无 `createProjectionInternal`。改为直接构造 `MediaProjectionManagerService$MediaProjection`（绕过 Binder 门禁）走 playback-capture + 本地静音。顺带：采集启停跟 FGS `reportCarPlaySessionLive`，软重连 TEARDOWN 不再 disarm；mute 跳过三星不认的 `STREAM_VOICE_ASSISTANCE(13)`（曾 `Bad stream type` 把整次 arm 打回 idle）。
- **Z Flip 重启后先有声、DiPlay 画面很久才上：** (1) 开机 BT/A2DP 常早于 `BOOT_COMPLETED`，DiPlay 先挂经典音频并对 `…cafe` SDP 失败退避 → AutoArm 增听 `BT STATE_ON` / `USER_UNLOCKED` + 0/3/12/30s 阶梯；(2) RTSP 线程 `onVideoSetup` 只 post 编码器、随即 SETUP 音频 → 改为 `ensureEncoder` 同步等待（≤8s）再开音频；(3) 开机 `CoreApi` 桥未就绪时 `onCreateSplitDisplay` 静默跳过 → HostRuntime 500ms×12 重试；默认应用启动 2s→400ms。
- **CarPlay 音频积压越来越长：** TX 在落后 0–60ms 时会紧循环补发多包，DiPlay 抖动缓冲被越堆越厚（视频仍取最新帧）。改为严格每 20ms 至多一包；落后则丢本地 PCM 对齐 live、绝不 burst。顺带：media 环硬上限约 350ms、积压 >240ms 剪到约 120ms（write+pull）；AudioRecord minBuf×2；`audioLatencyMs` 140。
- **分屏条变粗黑条 / CarPlay Recents 未对齐 AA：** `DIVIDER_DP` 8→2（`dividerPx` 下限 8→1），避免窗格间隙读成粗黑条；CarPlay Host 改用 `ENCODE_UI_DENSITY_DPI`（不再用手机 dpi 放大间隙）。`CarPlayRecentsOverlay` 去掉 LEFT/RIGHT/PHONE，补两道竖分割线、底栏「添加应用」、卡片 × close（`CoreApi.removeTask`）；× 对齐 AA `ib_close` 56dp 并加大命中区。
- **CarPlay 音频落后画面数秒（抖音连刷）：** media PCM 环曾达 768KB（约 4s），TX/Binder 掉队后堆旧 PCM，而视频始终取最新帧；egress resync 只重置发包节拍、不剪环。改为 media/speech 环缩至约 1s/2s 硬上限；积压超过约 500ms 时剪到约 200ms（hysteresis drop-to-live）；resync 打诊断日志。
- **CarPlay remotesubmix 路径无声：** 回退到 `REMOTE_SUBMIX` 时仍 `setStreamVolumeIndex(0)` 本地设备，三星上会把 submix 采成全零 PCM（pull 在涨但车机无声）。改为仅 playback-capture 路径本地静音；remotesubmix 跳过 mute。
- **CarPlay 有包无声（RTP 墙钟跳变）：** 音频 RTP 每包取 `MediaClock` 墙钟；TX/Binder 一旦落后 resync，时间戳大跳，DiPlay 按间隙插静音。改为首包用 MediaClock 对齐后按 `framesPerPacket` 递增。
- **DiPlay↔Z Flip 连上瞬间闪断循环：** 两处叠加——(1) DiPlay Bonjour `/ctrl-int` probe 打进我们 RTSP listen 后 `attachPeer` 覆盖 `peer/output` 并在 probe 关闭时 `releaseMediaStreams`，把正在播的 AirPlay source 拆掉；(2) 只 adopt 三星已存的 `Carplay`（带 INTERNET）无 specifier 回调，~2–3s 被 `CMD_UNWANTED_NETWORK score=0` 拆网 → control `Connection reset`。改为 probe 与 source 会话隔离；handoff 用 `removeCapability(INTERNET)` 的 specifier **全程 hold**，adopt 只作临时 dial 随后补 hold。
- **DiPlay↔Z Flip 握手卡在「等待 iPhone」：** `WifiNetworkSpecifier` 收到 `onUnavailable` 后仍留着 zombie `callbackRef`，后续 `0x4301` 只打 `WiFi handoff already pending — queue dial` 却永不 dial（L2 已在 `Carplay`、有 `10.38.199.x`）。改为 unavailable 清回调、用 `ConnectivityManager` 认领已关联网络并 `bindProcessToNetwork`；pending 时若已 ready/已关联立即 flush；StartSession 仅有 `fe80` 时回退 DHCP 网关 IPv4。
- **CarPlay 重音：** 手机扬声器/有线/A2DP 与 CarPlay WiFi 音频叠播；无独立导航 tap 时仍 SETUP speech 也可能被 DiPlay 混进媒体。武装时对本机 OUT 设备（含 A2DP，不含 REMOTE_SUBMIX）`setStreamVolumeIndex=0`；仅 `audio-policy-mix` 就绪才 SETUP speech。
- **CarPlay 音画不同步：** 视频用 frameIndex PTS、音频 RTP 从 0 自增，且 audio SETUP 晚于编码器启动，两条时间轴错位。新增 `CarPlayMediaClock`（共用 elapsedRealtime 纪元）；GL/屏幕 NTP 与音频 RTP 同钟；`audioLatencyMs=180` / screen `latencyMs=120`。
- **CarPlay 无画面只见测试图：** Host GL/VD 绑在半透明 Activity 上，BAL 起不来或 Activity 销毁会拆合成器；SETUP 与 Host 异步竞态会先 `feed=tee-canvas` 且不再切回。改为 FGS 拥有 `CarPlayHostRuntime`（Activity 仅 trampoline）；编码器武装前 **同步** `ensureStartedSync`；GL attach 失败重试 3 次并每秒回绑最多 15 次。
- **CarPlay 声音卡顿/重音（仍保留 media⊕speech 双通道）：** 媒体与导航内容必须互斥。去掉 media→speech 镜像与 speech 第二路 remotesubmix（同 PCM 双发 = DiPlay 重音）；speech 仅 AudioPolicy nav/assistant，失败则 speech 静音。remotesubmix 改 speaker-device 静音。音频 RTP 绝对 20ms 节拍，落后过多 resync。
- **CarPlay 有画面无声音（采集 vs 协议）：** REMOTE_SUBMIX 回退路径在武装时仍 `setStreamMute`，采到的是静音 mix，egress 却持续发静音 RTP。改为仅 playback-capture 媒体路径本地静音；remotesubmix 跳过 stream mute、改 speaker-device mute。排障看 `armed: media=… phoneMuted= speakerDeviceMuted=` 与 `pulled=`。
- **CarPlay 采集武装失败（MANAGE_MEDIA_PROJECTION / REMOTE_SUBMIX 未初始化）：** `setCarPlayAudioEgressEnabled` 经 AIDL 时 calling uid 仍是 app，`createProjection` 抛 SecurityException，REMOTE_SUBMIX 同失败。`setEnabled` 武装窗口内 `Binder.clearCallingIdentity()`；MediaProjection 优先反射 `createProjectionInternal`（绕过 Binder `checkCallingPermission`），成功则走 playback-capture + 本地静音。
- **DiPlay 反复「等待 iPhone / 连接中断」：** `CarPlayWifiHandoff` 在 `0x5703` 已连上后，`0x4301` 再 ensure 时因三星 `WifiInfo` 常为 `<unknown ssid>` 误走 `unregisterNetworkCallback`，把 ephemeral `Carplay` 热点拆掉（日志 `CMD_UNWANTED_NETWORK score=0` → AirPlay `Connection reset`）。改为记住 `boundSsid`/`boundNetwork`、同 SSID 合并不拆回调；`onLost` 400ms 内自动 rejoin。
- **CarPlay 应用选择器无法点击/滚动：** Host 无 Window，AA View 截图 + `dispatchTouchEvent` 让 RecyclerView 触控失效。改回 **Canvas** 直绘 + 归一化命中（与 Recents 同路径），并补拖动滚动；观感对齐 AA（scrim、「选择应用」、「最近/全部应用」、BitmapShader 圆角图标、底栏「关闭」）；AA 仍用 `view_split_app_picker`。
- **DiPlay 出画约数秒后自动重连：** FGS / session / 状态页同 tick 各 `startActivity(Host)`，250ms 内两个 `CarPlayHostActivity`；后者 `onDestroy` 拆掉前者的 GL/VD，控制通道被 DiPlay reset。改为 `CarPlayHostActivity.launch` 单飞 + `singleTask`，重复实例不拆直播会话。
- **CarPlay Host 画面/选择器倒置：** OES 窗格只靠 `SurfaceTexture` 变换（不再额外 `flipV`）；Canvas 选择器/Recents/chrome 单独用翻转 UV，与正立应用对齐。
- **DiPlay 老是「连接中断」：** 控制通道 / RFCOMM 断线后音频 UDP 与 screen 仍继续打（日志 `audio packet` 在 `transport closed` 之后还在涨），重连卡在 Auth。`releaseMediaStreams` 在 control/RFCOMM/TEARDOWN 时停音频+关 screen+event；iAP `onTransportClosed` 全量 reset；Host `startActivity` 节流；RFCOMM dial 不再抢已建立的 inbound 会话。
- **DiPlay 主动回连 / 重启后连不上 Z Flip：** 开机与 `adb install -r` 后 `CarPlayProjectionService` 未自动拉起，`…cafe` RFCOMM 无 listen → DiPlay SDP `scn=0` 失败。新增 `CarPlayAutoArmReceiver`：通道为 CarPlay 时在 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 自动 start FGS（并尽量开 Host）。
- **重启后 DiPlay 仍连不上（BT 晚就绪）：** AutoArm 已起 FGS，但开机瞬间 `BluetoothAdapter.isEnabled=false`，`CarPlayBluetoothLink` 只打 “deferred” 就永久放弃 listen。改为监听 `ACTION_STATE_CHANGED`，`STATE_ON` 后再武装 `…cafe`；listen 异常退出也会重新等待；AutoArm 额外 8s 补一次 FGS。
- **重启/重装后 DiPlay 连不上（通道未持久化）：** Z Flip 上 `SharedPreferences` 从未写出 `shared_prefs/`，`CarPlayChannel` 一直回落默认 AA → AutoArm 跳过、无 `…cafe` listen。改为持久化到 `files/aadisplay_carplay_channel`；状态页打开时若通道为 CarPlay 自动 re-arm FGS。
- **CarPlay Host 无把手/三点、选择器分辨率乱、应用倒置：** Host VD 误用 1920 而编码 1280 导致挤压；GL 未做 MediaCodec Y 翻转。改为 Host=`ENCODE` 1280×720 1:1；OES/Canvas chrome 统一 Y 翻转；`CarPlayChromePainter` 画分隔缝+三点与全屏 peel 短把手。
- **CarPlay 断线重连后 DiPlay 画面卡死：** screen SETUP 重武装时 `ensureEncoder` 见编码器仍在跑就 `feed=gl-compositor (kept)`，GL 继续画但 MediaCodec `in=0` 不再出帧。`onVideoSetup` 改为 `forceSurfaceRefresh`：强制 `detachEncoder` + `encoder.start` 换新 Surface。
- **CarPlay 主媒体声道对齐 DiPlay entertainment：** 对照 `DiPlay-CN` `AudioStreamCodec` / `AirPlayInfoPlist`（`entertainmentSampleRate=48000`，media 位 `0x8000`=PCM/48000/16/2）。SETUP 从误用语音位 `0x10`（16 kHz mono）改为 `0x8000`；采集/egress 同步 48 kHz stereo；本地静音加 `setStreamMute`。
- **CarPlay 有画面无声音（Z Flip / API 33）：** `CarPlayAudioCapture` 误反射不存在的 `AudioPlaybackCapture` / `AudioPlaybackCaptureClient`，三星上 `apiResolved=false` 一直发静音。改为真实 API：`MediaProjection`（优先 `semGetMediaProjection`，否则 `IMediaProjectionManager.createProjection`）+ `AudioPlaybackCaptureConfiguration` + `AudioRecord`；失败回退 `REMOTE_SUBMIX`。采集 USAGE_MEDIA/GAME/UNKNOWN；武装期间仍本地静音 MUSIC 等流。
- **CarPlay 手机仍发声：** `CarPlayAudioCapture` 本地静音 / 还原误写成 `setStreamVolume(0, stream, 0)`（实改 VOICE_CALL），MUSIC 从未静音；改为 `setStreamVolume(stream, index, 0)` + `setStreamMute`。
- **CarPlay Host 分屏条 / Recents / 选择器未对齐 AA：** 分隔条改为短按交换、长按 Recents、拖动 UP settle、边缘进全屏 peel；`CarPlayRecentsOverlay` 列序/几何与 AA 一致并补「添加应用」；新增 `CarPlayAppPickerOverlay`；方向盘媒体键松手用上次 usage（release `extra=0` 不再丢事件）。

### Changed
- **CarPlay 真音频出口（AudioPolicy RENDER）：** media 优先 `AudioPolicy` `ROUTE_FLAG_RENDER|LOOP_BACK` 绑 `DEVICE_OUT_REMOTE_SUBMIX`，娱乐/通知/闹钟/**通话下行·铃声** 改道离扬声器再出站（通话走 media，因 MIB3 不开 speech）；speech（nav）同样 RENDER。成功则**不再** mute。失败回退 playback-capture（mute 含 VOICE_CALL/RING；采不到通话）→ remotesubmix。无车机麦上行。排障：`armed: media=audio-policy-render phoneMuted=false`。
- **CarPlay Source / HU Profile 化：** BT 名、iAP/AirPlay 身份与 MIB3/Generic 行为收敛到 `carplay/profile/`（`SourceIdentity` + `HuProfile` + Registry/Resolver）；协议 RFCOMM UUID 仍在 `CarPlayConsts`。默认仍为 iPhone14,2 + mib3/generic，行为不变；扩展多手机/多车机改注册表，不再在链路里写死 vendor 名。
- **状态页按出口通道切换配置：** 「出口通道」始终可见；选 Android Auto 只显示 VD DPI 等 AA 配置，选 CarPlay 只显示会话/Host 等 CarPlay 配置。
- **Recent 卡片图标美化：** 放大 × 居中盖在图标上（不再独占上方一行）；图标约 72dp（分屏列按槽高放大，`fitCenter` 不形变）；CarPlay Canvas Recents 同几何与命中对齐。
- **出画互斥（AA XOR CarPlay）：** 不再「AA 壳 tee 双通道」。通道单选：AA 停 Source+Host；CarPlay 只走 `CarPlayHostActivity` GL。`ProjectionSessionGate.shouldTeeFromAaShell` 恒 false；FGS/视频 SETUP 无 Host 时自动拉起，避免长期停在测试图。
- **CarPlay / AA 包边界分离：** AA 车机 UI（`AaMainFragment`）经 `egress/ShellFrameEgress` + `egress/ProjectionSessionGate` 提供壳帧，不再 import carplay 包；状态页 CarPlay 控件抽到 `carplay/CarPlayStatusUi`；system_server 音频采集 / cgroup 提权迁至 `xposed/carplay/`；`DisplaySessionPolicy` 以 `onSecondaryEgressLiveChanged` 表达二次出口（AIDL 名保持不变）。见 [docs/EXECUTION.md](docs/EXECUTION.md) §14.2。

### Added
- **CarPlay media + speech 双音频通道：** RTSP 恒开 `audioType=media`（`0x8000` 48 kHz stereo）与 `audioType=speech`（`0x10` 16 kHz mono）。speech 采集优先 AudioPolicy loopback（NAVIGATION/ASSISTANT），失败再 remotesubmix；AIDL 追加 `pullCarPlaySpeechAudio`。
- **CarPlay Host GPU 零拷贝 60fps：** `CarPlayGlCompositor`（VD → SurfaceTexture OES → GLES 双窗合成 → MediaCodec `createInputSurface`）；去掉 Host ImageReader CPU sticky（原 ≈30Hz 内容上限）；`carplay/gles/` 精简 EGL 栈；编码器 `KEY_LOW_LATENCY`/`CAPTURE_RATE`/`OPERATING_RATE`/`REPEAT_PREVIOUS_FRAME_AFTER`、CBR；GOP 见 Fixed「IDR 峰」；降级窗 4→8×10s。AA 壳 tee 仍走 Canvas。验收：`feed=gl-compositor` + `CarPlayGlCompositor rate ≥55fps`；DiPlay 须设帧率=60。
- **无线 CarPlay Source 出口（双轨 AA + CarPlay）：** 本模块进程内 `carplay/` — Bonjour `_carplay-ctrl._tcp.`、BT RFCOMM iAP2 骨架、RTSP 控制、AVC 编码、测试图案 / AA 壳 tee、`CarPlayProjectionService` FGS、`CarPlayHostActivity`（仅 CP 时 ImageReader → `CoreApi.onCreateSplitDisplay`）。触控经 `CarPlayInputBridge` → `touchAaDisplay`；音频出站占位。AIDL 追加 `reportCarPlaySessionLive` / `isCarPlaySessionLive`；`DisplaySessionPolicy` 感知 CP 会话。状态页可启停。协议为自研骨架（不 vendor CatPlay）；外参见 [docs/CARPLAY_REFS.md](docs/CARPLAY_REFS.md)（[WirelessCarPlay](https://github.com/45clouds/WirelessCarPlay) MIT：iAP2/CarPlay/认证 PDF 只读对照）。
- **CarPlay RTP interleaved + 本地 loopback：** `RtpH264Packetizer`（RFC 6184 / FU-A）经 RTSP `$` 通道送 AVC；RECORD 后 `sendVideoAccessUnit`；debug 下 `CarPlayLocalLoopback` 自驱 SETUP/RECORD。FairPlay/HU 强校验仍待真机。
- **CarPlay iAP2 Link Sync / Identification（对 diplay RFCOMM）：** 正确 detect（`FF55…`）+ `FF5A` 成帧/校验和；Source 侧 SYN/ACK 协商；发 `0x1D00`、收 `0x1D01`、回 `0x1D02`。`CarPlayBluetoothLink` dial bonded iAP2 UUID + listen 兜底。
- **CarPlay iAP2 Auth + Wi-Fi / StartSession：** Source 走 `0xAA00…0xAA05`（收证+挑战，发 `AA05`，不验签）；随后 `0x4E0E`/`0x4E0D`/`0x5702`/`0x4300`；解析 `0x5703` 与 `0x4301`；`CarPlayWifiHandoff`（API29+ specifier）+ 有 IP 时 dial 控制口。FairPlay / pair-setup 仍待真机。
- **CarPlay 联调修正（DiPlay 真机）：** phone UUID `…cafe` listen + accessory `…caff` dial；RFCOMM 连通后主动重发 detect；`GET /ctrl-int/1/connect` 回 binary-plist MIME。旧 APK 仍打 `iAP inbound` 日志时说明未装上本改动。
- **CarPlay 真机推进：** 对 DiPlay 已跑通 iAP2 Ident/Auth/`0x5703`/`0x4301`；StartSession 后以 AirPlay **客户端**探测 `GET /info`/`OPTIONS`。已连同网时跳过 WiFi specifier。
- **CarPlay AirPlay Pair-Setup + Pair-Verify（对 DiPlay）：** TLV8 + SRP-3072/SHA-512（PIN `3939`）走 **persistent M1–M6**（DiPlay 忽略 transient FLAGS，且只在 M6 持久化 controller LTPK）；随后 **pair-verify** X25519 → `Control-Salt` Write/Read 密钥。Ed25519/X25519 用 BouncyCastle（与 DiPlay 同，规避三星 API33 无平台 Ed25519）。对照 DiPlay `PairSetup` / `PairVerify` / `AirPlaySession`（ControlCipher 仅在 verify M4 之后启用）。
- **CarPlay 加密控制帧 + SETUP/RECORD：** pair-verify 后用 Control-Write/Read 做 HAP 成帧（`u16le` + ChaCha20-Poly1305，长度作 AAD、分向计数 nonce，帧长上限 `0x4000` 对齐 DiPlay）；主动发 SETUP(session) → RECORD → SETUP(screen type=110)。
- **CarPlay screen dataPort 出画：** SETUP 回 `dataPort` 后 TCP 连上；用 **pair-verify X25519 shared**（非 SRP sessionKey）派生 `DataStream-Salt{id}` / `DataStream-Output-Encryption-Key`（DiPlay `CarPlayMediaEngine.outputKey`）；128B 头 + AVC（CONFIG/VIDEO，ChaCha AAD=头）。无 FairPlay 的 HKP 路径先跑通；legacy AES/`fp-setup` 仍待真机。
- **CarPlay dial 等 WiFi：** StartSession 后先等 accessory WiFi `onAvailable`/已同 SSID，再 probe+重试 dial `IP:7000`（避免 handoff 未完成时 `ECONNABORTED`）。
- **CarPlay bplist 端口解析：** 不再对 1/2/4 字节整数做符号扩展（DiPlay 把 `dataPort`/`eventPort` 写成无符号位型）；修掉 SETUP screen「有 body 却缺 dataPort」假阴性。
- **CarPlay screen CONFIG→avcC：** DiPlay `detectConfig` 把 Annex-B/长度前缀 SPS+PPS 误判为 H265（rx 有帧、shown=0 黑屏）；改为发 ISO `AVCDecoderConfigurationRecord` + `avcC` fourcc 包装。真机对 DiPlay 已出画（`first frame rendered`）。
- **CarPlay event 通道：** SETUP `eventPort` 上用 `Events-Salt` Write/Read 密钥（对照 DiPlay `acceptEvent`）；处理 `forceKeyFrame`→`MediaCodec` IDR、`hidSendReport` 触控→`CarPlayInputBridge`；/info `widthPixels`/`heightPixels` 作 HID 坐标空间。
- **CarPlay 对 DiPlay 真机全链路（屏 + 音 + 触控）：** 双机联调（手机 Source ↔ DiPlay Host）跑通：screen 128B 头 **NTP 32.32 改小端**（对照 DiPlay `CarPlayMediaEngine` 解密布局）；event 通道密钥**互补交换**（`Events-Input/Output` 对调后事件流不再 `BAD_DECRYPT`）。音频 v1 = **16 kHz mono LPCM 440 Hz 音源**（DiPlay `onAudio` 直接渲染 PCM，无解码依赖；真实应用音频采集待 system 侧）。触控回传双模式：AA 在线走 `touchAaDisplay`，仅 CP 时按 `primaryRatio` 切左右窗 → `touchPane` 窗内像素。真机验证：`first decrypted frame` → 解码出画、tone 持续播放（`authFailures=0`）、OnePlus 触控 → 三星 `event touch` 往返。
- **CarPlay 视频管线性能（1.4fps → 11+fps）：** tee 复用 canvas bitmap（原先每帧 5.5MB 分配压垮主线程 GC）；`CarPlayVideoEncoder` 双输入模式 — Surface 输入优先（`lockHardwareCanvas` 零拷贝），Qualcomm C2 `createInputSurface` `-38` 时回退 **buffer 输入**：旋转 ARGD8888 槽 + 后台线程 NV12 转换（主线程只 `getPixels`），转换跟不上即丢帧；编码分辨率 `ENCODE_*` = **1280×720**（16 对齐、与 tee 画布一致免缩放；HU 自行放大，像素数决定转换成本）；pump / worker 速率 + 丢帧计数心跳日志。
- **CarPlay 稳定性：** `drainLoop` 整次迭代隔离异常 — codec 被并发 `start()/stop()` 释放后 `getOutputFormat` 不再 FATAL 崩进程（真机 re-SETUP 触发）；`ensureEncoder` 去重（`onVideoSetup` + `onSessionReady` 同 tick 双 start 与释放竞态）；`FrameEgressTee` start/stop 串行化到主 looper（worker 线程 re-SETUP 不再与 pump 竞态回收 bitmap）。
- **出口通道选择（AA / CarPlay）：** `CarPlayChannel` app 侧持久化选择（默认 **AA** — 真车行为不变）；状态页新增「出口通道」单选（Android Auto / CarPlay）：选 CarPlay 启 Source（AA 未附时自动开仅 CP Host 分屏），切回 AA 即停 Source；FGS 系统重启（null intent）仅在用户选 CarPlay 时重新武装，杜绝 zombie 重启乱拨。状态行显示通道 / 会话 / 内容源（AA 壳 tee 双通道、Host 分屏、测试图）。
- **CarPlay 60fps（对齐 AA）：** 默认 60fps 供给（16 Mbit）/ 降级 30fps（8 Mbit）；`FrameEgressTee` 独立 pump 线程（Choreographer @60Hz vsync 驱动，壳帧回主 looper）；`CarPlaySourceSession` 速率状态机：2×10s 劣化窗（实际 <50% 目标或编码器丢帧 >15%）60→30，6×10s 良好窗 30→60；降级不重发 SETUP。**注意：DiPlay 解码帧率自持（忽略 Source SETUP fps）——车机需 DiPlay 设置→显示→帧率=60 才能全速解码**；供给侧自适应只解决手机编码/链路压力。
- **CarPlay 全应用音频出车机（对齐 AA）：** system_server `CarPlayAudioCapture`（`AudioPlaybackCapture` 反射装配 — 本机 android.jar 缺该类，按 `PhoneHidRedirect` 惯例反射；MUSIC/NOTIFICATION/VOICE_ASSISTANCE/SYSTEM/ALARM，16 kHz mono PCM16，256KB SPSC ring；**启用期间本地静音**这些流、停用还原；5min 无 pull 看门狗自卸兜底 app 被杀）；`CarPlayAudioEgress` 每 20ms 经新 AIDL `pullCarPlayAudio` 拉 640B → LE→BE → 现有 ChaCha/RTP；无内容发静音；`SEND_TEST_AUDIO` 降为调试开关（默认 false）。通话（VOICE_CALL/DTMF）留手机。AIDL 追加 `setCarPlayAudioEgressEnabled` / `pullCarPlayAudio`；`CarPlayRtspSession` 音频 SETUP 改为恒开（`setupAudio`）。
- **CarPlay 仪表歌词 + 封面（对齐 AA 仪表链）：** `CarPlayNowPlaying` 观察 `ClusterLyricStore`/`ClusterArtStore`（与 AA 仪表横条同源）→ iAP2 `0x5001` 增量 NowPlaying（title=当前歌词行触发 DiPlay 8s 字幕；artist/album/source-app/duration/elapsed，elapsed 1s tick）；封面经 iAP2 v2 文件传输（LSP 增 `0x0C kind 1 ver 2`；SETUP→START→DATA+FLAG_LAST→SUCCESS，≤2MiB，`Iap2Session` 发送窗口流控尊重对端 maxOutgoing）。`ClusterLyricStore` 增 `aadisplay_cluster_np_source_pkg`（只增不改）。
- **CarPlay 方向盘（HID 对齐 AA 方控语义）：** event 通道 `hidSendReport` 解析 media/telephony/knob（DiPlay uuid `2a2a2a2b/c/d`）→ `CarPlayWheelController`：媒体键短按 `pressKey`（play/pause/next/prev）、NEXT 长按 `swapSplitPanes`、PREV 长按开 Recents、滚轮 `scrollPane` 焦点窗滚动（新 AIDL oneway）；`PhoneHidRedirect` 门控扩为「AA 会话 **或** CP 会话」。
- **CarPlay 会话策略对齐（180s / keep-awake / cgroup）：** `DisplaySessionPolicy` 记录 AA UI 附着态 — CP 存活期间 Delay Destroy 挂起（5s 复查），CP 结束且 AA 未附 → 重启 180s 倒计时；`CarPlayHostActivity.onDestroy → onDestroyDisplay` 原已接 180s。system_server `CarPlayProcessBoost`：CP 存活期把 app pid 移入 top-app cgroup（v1/v2 自适应，30s 重断言，失败仅降级）缓解后台 2–4fps。
- **CarPlay 车机侧 UI（无 UI 壳 → tee 画布）：** 合成按 ratio 铺两窗格 + 分隔条 / 全屏 peel；手势对齐 AA（短按交换、长按 Recents、UP settle、边缘全屏）；`CarPlayRecentsOverlay` 三列（左/右窗/手机）+「添加应用」；`CarPlayAppPickerOverlay` 网格选应用；空窗格短按开选择器；`LastSplitStore` 快照恢复（ratio / fullscreen），无快照回退演示应用。

### Fixed
- **CarPlay 周期性卡顿（供给已 ~60fps）：** 根因是周期 IDR（~200KB）经 WiFi 同步 flush 砸到 DiPlay。曾 2s→5s；现优先 `FEATURE_IntraRefresh`（`I_FRAME_INTERVAL=-1` + 约 1s 渐进刷新），否则 GOP=10s（对齐 scrcpy）；`forceKeyFrame` 仍可要 IDR。曾试异步合并 flush 灌爆控制通道，已回退同步每帧 flush。日志：`AVC gop=intra-refresh…` / `AVC gop=10s…`。
- **CarPlay Host 黑屏 / rtpSent=0（GL attachEncoder EGL_BAD_ALLOC）：** Canvas tee 先 `lockCanvas` 过的 MediaCodec input Surface 在三星上仍挂着 `NATIVE_WINDOW_API_CPU`，Host 再 `eglCreateWindowSurface` 报 `already connected` → GL 合成器挂不上、Display 狂要 keyframe。`bindGlOrTee` 切 GL 时强制 `encoder.start` 换新 Surface；`attachEncoder` 同步返回成败，失败回退 tee。
- **CarPlay 对 DiPlay 画面一直闪 / ~10–15fps：** Host/FGS 无可见窗时 OEM 把 Choreographer vsync 压到 ~15Hz，`FrameEgressTee` 改 **Handler 定时泵**；窗格 CPU 拷贝限 ≈30Hz（sticky 仍按供给 fps 画，避免每帧 ~5MB 拷贝把泵压死；三星 `wrapHardwareBuffer` 会泄漏故不用）；`encodeCanvas` 每帧清黑底；降级窗 2→4 减轻编码器重启闪屏；`CarPlayProcessBoost` 同时写 `cpu`+`cpuset`+`freezer` top-app（仅写 cpu 时三星进 `cpuset:/abnormal` 只给 0–3 核），看门狗 3s。
- **低 DPI（144 类）车机 Coolwalk 布局放大裁切（左裁切 / 右 peel 消失）：** `AaCoolwalkLayoutHook` 重写 LayoutInfo dp↔px 密度结算 — 优先存储的 HU 上报 DPI（`aadisplay_vd_density_hu_last`，经活体显示宽度交叉验证：dp 折算须回中 widthPixels 或单轨 slot 缺口），再手动 pin，活体 `densityDpi` 仅在自洽时采信 — 杜绝 160-default VirtualDevice 把 1422dp 放大成 1422px（@144 应为 1280px）导致 profile / DrawingSpec 超物理表面。
- **亚轨残余 `content_insets`（~16px @144dpi）造成触控整体平移：** 仅在 Reclaiming / FullBleed 相位清零（新增 `CoolwalkRailMath.shouldZeroResidualCoolwalkInset`，Bootstrapping / RailPresent 不动）；亚轨残余**不再**写入 touchRail（只有轨带内边沿更新），避免污染 HU 触控偷渡带。
- **全幅后 compositor inset 陈旧重映射：** `compositorLeftInsetPx` 在 layout 已晋升 full HU 且有效轨已饿死（`effectiveRailWidthPx==0`，Reclaiming / FullBleed）时返回 0 — 视觉 blX 已 0，继续减 stale slot 会整体偏 touch inject；compositor `systemInsets` 清除仅限 Coolwalk 尺寸边沿（32–160px 带），不再误伤无关 chrome。

## 0.24#17.4-r14

### Added
- **手机蓝牙键鼠 → 焦点窗 VD：** `PhoneHidRedirect`（system_server）。AA 会话活跃时偷物理键盘 / 鼠标事件并 `inject` 到焦点 VirtualDisplay（鼠标主键按触控注入）；Delay Destroy 期间归还手机。方控媒体键路径不变。折叠屏：光标会先画在合盖外屏 / 展开主屏，需 `setVirtualMousePointerDisplayId` 绑到 AA VD + 隐藏实体屏光标；绝对坐标按 `event.displayId` 缩放。W7023：AA VD 缺 Input viewport（touch NONE）时 override 仍落主屏——加 `SUPPORTS_TOUCH` + hook `setDisplayViewports` 注入窗 viewport + `forceHideCursor`。双窗：光标在壳画布连续移动，可停在分隔带（不传送到另一 VD）；分隔带拖比例 / 长按堆栈 / 点按对调走 `touchAaDisplay`；堆栈打开后触控继续打壳。键盘：Ctrl+方向/WASD 经壳改比例再 settle VD，Ctrl+R/Tab 开堆栈，Ctrl+S 对调。中键切换对侧窗。壳上 `HidCursorOverlayView` 自绘指针。

### Changed
- **Recent 逻辑重构：** VD 列以 `PaneAppStack` 为顺序唯一源（ATMS 只补 taskId/图标）；新增 `RecentTaskProvider` / `RecentTaskActions` / `RecentTasksCoordinator`；拖拽结束走 `reorderPaneStack` IPC 持久化整栈；操作后重载快照、去掉跨列乐观更新；已打开时再按 Ctrl+R/长按只刷新不关闭；ATMS 变更经 `ACTION_RECENT_TASK_DIRTY` 防抖刷新。
- **Recent 回归修复：** `RecentTaskProvider` 读路径不再 `trimToAlive` 误清栈，并补 ATMS 在途任务；关闭恢复乐观移除 + 过滤 `mExplicitlyClosedPackages`；× 按钮防 `ItemTouchHelper` 抢触控；`ensurePanePackages` 跳过已关闭包；用户点选/选择器启动时清除关闭标记；Recent「添加应用」改直接调 `AaMainFragment.showAppPickerFromRecent`。
- **全屏下点选即切侧：** Recent / 应用选择器 `startActivityOnPaneForUser` 若当前全屏在对侧，promote/launch 成功后直接 `setSplitFullscreen` 到目标窗。
- **Recent VD 列点空白关面板：** 左 / 中 / 右列空白 tap 均可 `onExit`（不再仅右列）。
- **AutoOpen 短链失败兜底：** full-bleed 短链耗尽且仍未 `AA_DISPLAY_SHOWN` 时武装长 AutoOpen；`IllegalStateException` / `NullPointerException` 均按控制器 not-ready 处理。
- **Version bump to `0.24#17.4-r14`** (`versionCode` 3074)。用户说明见 [docs/archive/RELEASE_NOTES_0.24-17.4-r14.md](docs/archive/RELEASE_NOTES_0.24-17.4-r14.md)。

### Fixed
- **LineageOS Android 16 分屏 VD 与手机 PowerGroup 绑死：** `PaneDisplayGroupForce` 将 AA pane LogicalDisplay 拽出 default DisplayGroup 0（否则 `OWN_DISPLAY_GROUP` / `ALWAYS_UNLOCKED` 被忽略，keyguard / ColorFade 同步到车机窗）；挪组后就地同步 `mOverrideDisplayInfo.displayGroupId`（勿清 `mInfo`，A16 `DisplayInfoProxy` 赋 null 会重启 system_server）。
- **虚拟屏软键盘高度为 0（仅「收起键盘」）：** 蓝牙硬键盘连接时会话期临时打开 `show_ime_with_hard_keyboard`（销毁后恢复）；去掉 `OWN_FOCUS`；IME policy 仅 FALLBACK→LOCAL，不改写 HIDE/INVALID；壳芯片只认 VD 上真实 IME 窗（不再信 `mInputShown`）。
- **「收起键盘」点到空格：** HU 触控仍落 TextureView——芯片矩形内改走 `hideIme`；蓝牙鼠标经 `HidSplitLayout` 芯片几何同样收键盘。
- **Recent / 显式关闭不再 forceStop：** close / swipe-off 仅 ATMS `removeTask`；`forceStopPackageAsUser` 保留给 Delay Destroy（180s）`onDestroy` teardown。
- **冷启动 / 主线程忙时长按分隔条误对调：** 长按 Recent 以输入 `heldMs≥550ms` 为准（Handler 超时可补震）；去掉 480–550ms fallback tap-swap，避免假长按被交换。
- **分隔条点按交换 / 拖动 settle 抖动：** tap-swap 窗口 420→480ms，长按前（<550ms）无 slop breach 也交换；ratio 微变优先 swap 而非 settle；点按交换设 `ratioSettling` + `swapInFlight` 避免重复 layout/rebind；拖动松手 Shell-first layout → `setSplitRatio`，同步清 GPU 预览，去掉 300ms occupancy / 600ms settling。
- **分屏比例 settle 错位 / 黑边：** Shell 与 VD 共用 `SplitPane.dividerPx`；ratio-settle 仅对 **变窄** 的 pane 做 WM nudge（减黑边且避免双 pane 1px 抖动风暴）。
- **Open/Close 交互卡顿（精简热路径）：** ATMS 栈变更的 notify + Recent dirty 合并为单次 `scheduleStackSettle`（200ms）；`AaMainFragment` occupancy 未变时跳过 overlay 刷新；Recent 列 `DiffUtil` 替代全列 `notifyDataSetChanged`；显式 close 后跳过一次 debounced dirty reload（乐观 UI 已更新）。
- **应用记忆恢复失效（Recent 重构回归）：** VD 显式关闭只记 `mExplicitlyClosedPackages` + debounced persist，不再 `LastSplitStore.dropPackage` 清 durable 快照；手机列关闭不再误伤分屏记忆。用户点选/Recent 启动不再 cancel 进行中的 restore/ensure。`onDestroy`/`verify` 的 force persist 跳过 ATMS refresh，避免断连时空 VD trim 栈导致写盘 skip。
- **拖分屏条 VD 内应用分辨率重建卡顿：** ratio settle Shell-first；TextureView 尺寸变化不再触发 `setPaneSurface`→ensure；`ratioSettling` 缩至 180ms 仅挡 rebind；resize 期间跳过 `onTaskMovedToFront` promote。
- **拖动分屏 / Recent 拖拽栈顺序乱（如汽水抢顶）：** `refreshPanePackagesFromAtms` 不再按 ATMS visible top 覆写栈顶；merge 保留 intentional 顺序（resize 时 ATMS 漏报 buried）；`onTaskMovedToFront` 改 `promoteStackFronts`；ratio settle suppress 延至 2s；Recent 拖拽 reorder 改同步 IPC。
- **Recent 关闭 VD 要等数秒才消失：** `removeTask` 改后台执行；显式 close 记入会话内 `mExplicitlyClosedPackages`，backfill/restore 跳过；close 路径跳过同步 Av 仲裁。incomplete persist 仅 skip 不清快照。
- **应用选择器点选闪「请选择应用」 / 卡顿：** 点选后保持乐观占位至 launch 确认；先 `hide()` + 乐观占位再 `runIO` 启动；Binder/广播仍为空时忽略 downgrade。
- **交换分屏卡顿：** `swapPanes` 不再在 handler 上同步跑两侧 `ensureTasksFillDisplay`，改 80ms 后异步 settle；AvMedia 仲裁同样延后。
- **分隔条点按偶发不交换 / Recent 卡顿 / 长按误对调：** 放宽 tap-swap；未满长按超时的假长按不再交换；Recent 点选先关面板再异步 launch；图标 IPC 缩到 64px、手机列最多 8 条。
- **退出全屏后中间分屏条点不上（回归修复）：** 恢复同步 `exitFullscreen` + `applySplitLayoutWeights(force)`，并 `requestLayout`。
- **蓝牙鼠标跨 VD 跳中心 / 分隔条跳变：** `HidCursorOverlayView` 自绘指针，`forceHideCursor` + 各 VD 藏 OS sprite；不再向 pane 注入 `HOVER_MOVE`；悬停不按分隔带扩区抢路由，左键才命中 seam/peel。

### Verify（r14）
1. 手机蓝牙键鼠：窗内可点；分隔带拖比例 / 短按交换 / 长按 Recent；Ctrl+WASD / S / R；中键切窗；Delay Destroy 后键鼠归还手机
2. 全屏时在 Recent / 选择器点对侧应用，画面切到该侧全屏
3. Recent 左/中列空白 tap 可关面板
4. 偶发冷连不进分屏场景：短链后仍能靠 AutoOpen 长兜底进入
5. LineageOS A16：息屏 / 解锁后车机分屏窗不被手机 ColorFade / keyguard 拖死
6. 选应用 / 交换 / Recent 关闭无明显数秒卡顿；断线记忆仍可恢复
7. 蓝牙硬键盘接入时，VD 内输入框能弹出完整软键盘；点「收起键盘」收起（不点到空格）；断开会话后 `show_ime_with_hard_keyboard` 恢复

## Pre-r13 unsectioned notes

以下条目在 r13 打点时已在树中，但当时仅做了版本号 bump，未单独成节；保留作技术对照。

### Removed
- **藏车机媒体壳图标：** 删除 `AaClusterMediaIconHideHook`（不再 hook `queryIntentServices` 过滤本包壳）。全屏投影下桌面列表本就会闪，隐藏收益低且增加 hook 面。

### Changed
- **音视频粘性焦点（r16）：** AvMedia（音乐 ∪ 视频）正在播放时不因地图/浏览器压顶或空闲 Av 栈顶而自动丢发声权；仅在**停止播放**、**移出栈**、或**另一 AvMedia 开始 PLAYING** 时让出。同窗 `SplitBuriedPlayback` 仅在栈顶 Av 正在播时 pause 埋栈。**栈恢复 / ensure / 双窗 promote** 走 `enforceSingleSounder`（立即 + 延迟重试），多 Av 同恢只留一个发声；`resumeFront` 若已有其它 Av 在播则跳过。清理：去掉 soft-idle 选主 / 仅音乐埋栈启发式、`eligibleControllers` 无用 layout 参数、重复 isPlaying/isIdle、无调用方的 `CoreManagerService.buriedPackagesOnAaDisplays`。
- **音视频互斥 + 仪表三源重定义（r15）：** AvMedia = 音乐 ∪ 视频（抖音）。`AvMediaArbiter.pickWinner` 只保留一个发声源（焦点窗正在播的栈顶优先，否则三源 / 其它音乐 / 视频）；`pauseLosers` 停其它 PLAYING。仪表只跟 QQ 车载 / HD / 汽水里**正在播放**的那一个（`pickClusterSource`）；视频抢到发声权时清空仪表。三源彼此互斥。
- **同窗叠栈：** 栈顶 AvMedia **正在播放**时对埋栈 AvMedia 显式 `pause`；非 Av / 空闲 Av 压顶时埋栈音视频可续播。
- **FacetBar 重构文档与试验代码清理：** 新增 [docs/COOLWALK_FACETBAR.md](docs/COOLWALK_FACETBAR.md)（状态机、四路回收、profile settle、坑点）；移除 `ReconnectSizingTrace`、未使用的 `:car` presentation resize 广播、`CoolwalkRailMath` 死代码；`AaDisplayPresentationResize` 改为 AADisplay 进程懒加载 `DrawingSpec` hook 入口；同步 [EXECUTION.md](docs/EXECUTION.md) §5.4。
- **仪表歌词补进度包 +1s：** `pushPlaybackNow` 在 Store 外推整秒上再 **+1s**（clamp duration）；`getPlaybackState` 不加。见 `docs/CLUSTER_LYRIC_CLOCK.md` §5。
- **仪表歌词 T2-A 外推整秒补包：** `pushPlaybackNow` 用 `ClusterLyricStore.extrapolatePosition` 当下整秒克隆 Gearhead `AaPlaybackState`，不再重放 `play_l` 过期快照（失败 fallback T1）。见 `docs/CLUSTER_LYRIC_CLOCK.md` §5。
- **仪表歌词时钟文档：** [docs/CLUSTER_LYRIC_CLOCK.md](docs/CLUSTER_LYRIC_CLOCK.md) 更新为 **T2-A** 当前落地。
- **分屏应用选择器加速：** launchable 列表进程内缓存 + 会话预热；图标懒加载；「最近」改用 `LastSplitStore`/occupancy 包名，不再拉完整 `recentTask` Bitmap IPC。
- **热路径减负（歌词 + 触控）：** 同句歌词跳过 Settings.Global 三写（靠 5s `touch` keepalive）；LRC 按 mediaId+blob 缓存解析，300ms tick 只二分取句；`:cluster` 不再观察 `updated_ms`；`injectInputEvent` 缓存 `InputEvent.setDisplayId` Method。
- **r11-T→r12 审计收敛：** 去掉试验叠层——重连缩窗仅服务端 `shrink-auto`（删客户端 900/1700ms `lastCreate` bust）；soft-reconnect 同 profile 跳过 VD resize；AutoOpen 梯子收为 `0/1.5/5/12/24s` 且 `REARM_GAP≥末档`，保留 car-connected kick；`AaClusterLyricEgressHook` 仅改写 `aadisplay.cluster:` 壳 MEDIA_ID；歌词 `warmStart` 不再每句触发；Allowlist unknown-sources pref 仅在 pkg-bool 未命中时回退。
- **仪表歌词 / 封面 / 进度热路径：** 进度 Settings 去重（seek / 状态变化立刻写，其余最多 1s）；tick 上 `getActiveSessions` 1s 节流；Egress 200ms Fresh/Progress 缓存 + Compat `getString` Method 缓存；LRC unwrap/`mBundle` 缓存；封面 JPEG 后台线程 + `inSampleSize` + 原子 rename；快句歌词 pending flush。
- **仪表歌词主路径：** `ClusterLyricMirror` → `ClusterLyricStore` → `:cluster` `ClusterLyricMediaService` Title；Egress 为壳会话兜底。优先包 QQ 车载 / HD / 汽水（`com.luna.music`）。
- **仪表横条隐形媒体壳（免未知来源）：** `ClusterLyricMediaService` + `AaMediaAllowlistHook`（本包 pkg-bool）；Dashboard starve + Presentation 隐藏保留。
- **AutoOpen 事件驱动：** 武装后立即 + 稀疏 CAMS 重试；Hook SysUi car-connected 立刻再踢 `a(Intent)`；仅 `AA_DISPLAY_SHOWN` 停。
- **DexKit 方法坐标缓存：** gearhead 冷启缓存命中跳过 `libdexkit` 扫包；失败回退 live DexKit。
- **少打 ATMS：** `ensureTasksFillDisplay` / `bringTaskToFront`（press-key、promote）复用同一次 display snapshot。
- **热路径日志降级：** 成功与诊断用 `logDebug`；错误仍 `log`。
- **MediaCarApp 与 Dashboard UI 拆分：** 保持 MediaCarApp 出站；Dashboard VD starve + Presentation 隐藏保留。
- **重连分辨率单一结算：** `DisplayProfileSettle` 取代 grow/shrink confirm；有活 FacetBar 条带用 `HU−rail`，否则全宽；450ms rail-settle 重试。
- **左轨 ensure 窗口收敛：** FacetBar 回收 poll 从 8s/400ms 收为 2s/250ms；inject 成功即停；collapse/starve 只做一次全窗 reclaim，不再把 deadline 续满。晚到 chrome 仍靠 LayoutInfo / `windowAttach` 再武装。

### Fixed
- **操作手机时被伪熄屏强制锁屏：** `DisplaySessionPolicy` 挂钩 `PowerManagerService.userActivity(Internal)`，仅 `DEFAULT_DISPLAY` 的 TOUCH/BUTTON/ACCESSIBILITY 重置伪熄屏计时；车机 / VD 触控仍只保活虚拟屏，不续手机 idle。手机主屏前台为用户应用（如地图）时直接跳过 `goToSleep`，回到桌面 / SystemUI 后才按超时伪熄屏。
- **QQ 车载约 45s 灰屏重建：** 手机息屏心跳 `surfaces-ready` 把永久 LAUNCHER（`AppStarterActivity`）误判为 splash 并 cold relaunch；`isPackageFrontStaleOnReconnect` 去掉 AppStarter/Loading/Launcher 启发式，并对 MAIN/LAUNCHER 组件豁免。
- **仪表歌词 clear no-session：** `qqmusiccar` 无 MediaBrowserService/APP_MUSIC，`MusicAppClassifier` 判非音乐导致 `AvMediaArbiter` 选不中；已知歌词源包强制算音乐，metadata 在 duration≤0 时仍认 artist/album；布局未命中时回退到正在播放的会话；无赢家时不再对所有 AvMedia 群 pause。
- **埋栈音乐被强制停播：** 同窗地图/浏览器压在音乐上时不再 `MediaSession.pause` 音乐类会话（按能力识别，非包名白名单）；仪表歌词仍可绑定埋栈音乐源。
- **软重连左侧黑条（1280×720 / gresolution）：** `:projection` 已把 `content_bounds` 扩到全宽 1280，但 gearhead 仍用 content slot `DrawingSpec`（1173=1280−107）建 Presentation / 分屏 VD，左侧留下 107px 合成器空槽。补 hook `ResourcesImpl` **long** 资源 ID 版 dimen（`:projection` 重启后 rail dimen 不再回弹）；新增 `CoolwalkDrawingSpecWiden` 在 gearhead（`:car`/`:projection`）把 `DrawingSpec` 扩到已观测全宽；`content_bounds` reclaim 后若 presentation 已按 content slot 创建则 `scheduleFullBleedRelaunch`；已 reclaim 的重复 `content_bounds` 幂等跳过（只改 rect，不再叠 reclaim / AutoOpen，避免 `:car` 主线程 ANR）。
- **左侧导航栏黑条重连复发：** FacetBar 窗口一打 tag 就停掉回收，真正占着 ~107px 的 GhostActivity 宿主从未被扫到。改为每次 ensure / attach / collapse / starve 都扫进程内全部窗口；inject 成功即停 poll，晚到 chrome 靠 LayoutInfo / `windowAttach` 再武装。
- **左侧黑条重连后不消（content 1305 / HU 1412）：** `:car` 未把轨宽 dimen 打成 0，`GhLifecycleService` 仍发 `Rect(0,0,HU−rail)`；FacetBar 已饿成 1px，合成器留下 107px 空槽。`:car` 同样 zero dimens + VD starve/expand；`content_bounds` 把内容槽扩回已观测全宽。
- **仪表进度双外推：** 壳 session 写入原始采样 + `positionAtElapsedMs`；Egress `getPlaybackState` 写入已外推位置 + `elapsedRealtime()`；位置不超过 duration。
- **仪表换句时间反复（T2-A）：** `pushPlaybackNow` 改为 Store 外推整秒克隆 `AaPlaybackState`，消除 T1 快照 push 导致的 `0:29→0:30→0:29` 回退（窗前 natural 包若仍打架见 T2-B）。
- **封面 recycled bitmap：** Egress 解码缓存只丢引用不 `recycle`；session 封面一律 `ARGB_8888` copy。
- **歌词 MSM 空启动卡死：** `MediaSessionManager` 为空时 `started=false` 并 2s/10s 重试（上限 8）。
- **快句歌词被 200ms 节流丢掉：** 间隔内记下最新一句，到期 flush。
- **QQ / 汽水同时后台抢 Now Playing：** 双方都报 PLAYING 时，`onSessionsChanged` 与 tick 共用 1.5s freshness 死区，避免会话列表抖动闪烁；`unbind` 取消上一源的 120s `pausedClear`；跨源（QQ↔汽水）无封面时立刻丢掉旧 JPEG，同包仍保留 2s 晚到封面窗口。
- **长前奏封面切换慢：** LRC tick 在曲目尚无 JPEG 时每 1.5s 节流重试拉图（歌名不变 / 前奏滚词也能出封面）；`:cluster` 封面 revision 变化时不再走 lyric-only，确保 HU 收到带封面的完整 MediaInfo。
- **断开重连分辨率 800/720 反复错位：** 具名 `GhFacetBar` VD 饿成 `1×H`（触控仍用观测轨宽），与 `content_bounds` 扩满共用全宽真值；服务端按 rail 观测结算 profile。
- **方控长按对调：** 长按上一曲/快退开 Recent；长按下一曲/快进换分屏；去掉长按播放/暂停。
- **仪表横条歌名兜底过期：** 播放中定期 touch `aadisplay_cluster_np_updated_ms`。
- **奥迪仪表「未知专辑」：** 壳 session 补 `DISPLAY_DESCRIPTION` + Egress 向 HU/GAL `MediaInfo` 注入 `aadisplay_cluster_np_album`（Store 有专辑名但出站 album 参数为空）。

### Verify（仪表歌词 + 重连）
1. Soft-reconnect（含 1280×720 gresolution）：`content_bounds` 扩满全宽；`DrawingSpec`/profile 稳定 1280（非 1173 content slot）；日志可见 `displayProfile relocked(settle|rail-settle)` / `starve FacetBar` / `DrawingSpec ctor widen`；无左侧 107px 黑条
2. 冷连 AutoOpen：仍能进 AaDisplay；connected kick 后无 100ms 级刷屏重试
3. QQ 车载/HD/汽水横条歌词随句切换（快句不丢）；同句 hold 时 Settings 标题无每 300ms 刷、进度最多约 1s 一写；三源同时只一个播、仪表跟正在播的源；播抖音时音乐停且仪表清空；媒体列表出现壳图标可接受
4. 方控短按仍控真实播放器（Egress 不再改写 QQ Title）
5. QQ 与汽水同时后台：只跟正在播的源；切到汽水未出封面时不残留 QQ 封面；QQ 车载↔HD 同曲仍可晚到封面；暂停 120s 清空不会误清刚切过去的源
5b. AA 顶栏与奥迪仪表歌词随句刷新；换句不闪 0:00；无 `0:29→0:30→0:29→0:32` 式时间反复；真切歌才重置
5c. 音视频互斥：音乐播时抖音停；抖音播时音乐停；分屏一窗音乐一窗视频时只一个发声
6. 分屏栈切换 / Recent 置顶 / 触控滑动无明显变慢
7. 左轨触控仍能注入 AaDisplay（starve 后 hit 带宽用观测轨宽）

## 0.24#17.4-r10

### Added
- **FRX 必装 Google 应用绕过：** `AaFrxRequiredAppsHook`（`:projection` + `:car`）将 Google App / Maps / TTS 的 FRX 安装与版本检查强制判为就绪。
- **导航占位页禁用：** `AaNavFallbackHook` 仅禁用 `NavigationFallbackCarActivityService`，避免缺 Maps 时占位布局崩进程。
- **Coolwalk 空媒体卡抑制：** `AaUiHook` 将名为 `Dashboard` 的 VirtualDisplay（create/Builder/resize）饿成 1×1；`AaMediaPlaceholderHook` 禁用 `MediaCarAppService`，并在 `addView` 前隐藏残留 Presentation 窗口。

### Fixed
- **手机锁屏后全屏 peel 拉不出 / 长按无响应：** AaDisplay presentation 无 `ALWAYS_UNLOCKED`，Keyguard 会挡住 `touchAaDisplay` 注入，而双 VD App 仍可点。上报 presentation id 时 `setShouldShowWithInsecureKeyguard(true)` + 尽力打上 `FLAG_ALWAYS_UNLOCKED`；锁屏且全屏时改由 system_server 直接解析 peel（退出 / 点按切换 / 长按广播开 Recent），不依赖 presentation 命中。

### Changed
- **Version bump to `0.24#17.4-r10`** (`versionCode` 3066)。用户说明见 [docs/archive/RELEASE_NOTES_0.24-17.4-r10.md](docs/archive/RELEASE_NOTES_0.24-17.4-r10.md)。

### Verify (真机)
1. 未装 / 未更新 Google App、Maps、TTS 时，AA FRX 仍能过安装检查并进入车机界面
2. 缺 Maps 时不再落入导航占位页导致 `:car` 崩
3. 冷连接 / 重连后不再长时间闪空媒体卡（Dashboard VD）；媒体卡区域保持干净
4. 手机锁屏 + 全屏 peel：可拖出退出 / 点按切换 / 长按开 Recent；解锁后 peel 仍正常

## 0.24#17.4-r9

### Added
- **虚拟屏多应用栈（每窗最多 3 个）：** Primary / Secondary 不再「选新即杀旧」。应用压入栈，栈顶显示；同栈切换（Recent 点选 / 长按拖到顶部）只 `moveTaskToFront`，无需 close 冷启动。满 3 再加则挤出栈底。Recent 左/中列底部「添加应用」打开选择器（`EXTRA_KEEP_OCCUPANCY`）。`LastSplitStore` 新增有序栈 CSV（底→顶），旧单包 key 仍为栈顶兼容。

### Fixed
- **会话中偶发亮手机屏（优先保三星车机不黑）：** 去掉 VD `ACQUIRE_CAUSES_WAKEUP` pulse（OEM 上易泄漏到主屏）；`userActivity` 只走带 `displayId` 的重载，禁止全局回退。灭屏时仍立即再断言 + 约 3s burst，且手机灭屏期间 heartbeat 缩短为 5s 并用 TOUCH 事件，继续压住三星 `OWN_DISPLAY_GROUP` doze。

- **分屏满幅 VD + crop 回退：** 曾尝试分屏也保持满幅 HU VirtualDisplay 再 TextureView center-crop，结果半窗出现窄条内容 + 大块黑边（HU 截图）。已改回 **VD 缓冲 = 分屏窗格尺寸**（随车机 profile × ratio，非写死分辨率）；去掉 `setDefaultBufferSize(HU)` / crop 矩阵 / 触控映射。QQ 音乐竖屏半窗卡顿需另案处理，不能用满幅裁切牺牲左右分屏布局。

- **全屏 peel 退出后应用分辨率错误：** peel 松手退出时先 `setSplitFullscreen(NONE)`（按进入前 ratio 缩 VD）再 `setSplitRatio(松手 ratio)`，两次 resize 后 OneUI 常把 Window **Requested** 卡在中间宽度（ADB：短信 `Requested 343` 而 VD/frame 已是 `518×480`）。改为退出前先 `setSplitRatio` 暂存到 `mRatioBeforeFullscreen`，再一次性 exit resize；并对两 VD 做与 swap 相同的 1px nudge（`ensureTasksFillDisplay`）。

- **全屏 peel 偶发点不动：** 长按分隔条/peel 在手指仍按下时就 `add` Recent，注入的 UP 打不到 `SplitDividerView`，presentation 指针序列残缺后后续 `touchAaDisplay` 易被丢弃；长按出后台再滑动又能恢复。改为长按只震动标记，**松手 UP 后再开 Recent**；开面板前 `resetGesture` + 清拖动预览。另：`touchAaDisplay` 解析不到 presentation id 时防抖清 ATMS 门闩并广播 `REQUEST_AA_UI_DISPLAY_ID` 让 UI 补报，避免会话内永久哑火。

- **分隔条 swap 后窗体不铺满：** ADB 可见 Secondary 上任务窗口帧仍停在旧宽度（如 386×480，而 VD 已是 436×480）。`moveRootTaskToDisplay` + `VirtualDisplay.resize` 后 OneUI 常不重算 Window frame，且全屏根任务上 `resizeTask` 无效。swap 后对两 VD 做 1px nudge 强制配置下发并 re-front 栈顶；UI 侧乐观镜像 `1-ratio`，`SPLIT_STATE_CHANGED` 带上 `EXTRA_RATIO` 同步 TextureView。

- **全屏背后窗 Recent 置顶不生效：** 第二分屏全屏时点第一分屏栈内应用，列表会置顶，但 peel 切回后仍显示旧应用。原因是背后 VD 的 ATMS 顶滞后，`restoreFocusAfterFullscreen` / `getPanePackage` / persist 刷新都按 ATMS 旧顶把 `PaneAppStack` 写回去。改为全屏 focus restore 走 `promoteStackFronts`（栈顶优先）；`getPanePackage` 在栈顶仍存活时不再被 ATMS 降级；persist 刷新保留 intentional 栈顶；背后窗 `startActivityOnPane` 置顶后把焦点还回可见全屏窗。

- **栈顶记 Google、画面仍是高德：** 三星 OneUI 上 `getAllRootTaskInfosOnDisplay` / dumpsys 常为 **顶→底**（可见任务在前），代码却按 AOSP 习惯用 `lastOrNull` 当栈顶，把埋在底下的 Google Maps 写成 front，并用错误的 `isTaskTopmost` 让失败的 `bringTaskToFront` 误报成功。改为优先认 `RootTaskInfo.visible`，并把列表规范成底→顶后再同步栈 / Recent / persist；高德 `MainMapActivity` 重排不再强加 `CATEGORY_LAUNCHER`（LAUNCHER 仍是 `UsbFillActivity`）。

- **栈顶记高德、画面仍是 Google 地图：** 高德 LAUNCHER 是 `UsbFillActivity`，VD 根任务却是 `MainMapActivity`；`bringTaskToFront` 用 MAIN/LAUNCHER REORDER 对不上现有 task，却仍 `moveToTop` 写栈。改为优先按 task `topActivity` 重排并校验真成顶，失败则清僵尸再冷启，不再把失败切换写成栈顶。
- **同栈切换被写回栈底：** `getAllRootTaskInfosOnDisplay` 为底→顶，但 `getPanePackage` / `refreshPanePackagesFromAtms` 误用 `firstOrNull` 当栈顶，点选切换后 bookkeeping 与 `promote` 会把底层应用抢回前台。改为 `lastOrNull`；`bringTaskToFront` 补 `setFocusedTask` + MAIN/REORDER 回退；Recent 同栈点选/拖顶走 `startActivityOnPane`（front-existing）。

### Changed
- **文档 / 命名对齐：** 手机悬浮 UI 已彻底移除；`DisplayWindow` 重命名为 `DisplaySessionPolicy`（仅 Delay Destroy + keep-awake）。同步 `AGENTS.md`，去掉过时的 `SHOW_PHONE_OVERLAY` 描述。
- **Recent 栈列紧凑 + 点选置顶：** 左/中列每项均分高度，去掉 9:16 高卡片，三应用同屏无需上下滚；整项（含灰底）点击即 `startActivityOnPane` 拉到栈顶并关闭面板（Close 仍只关任务）。
- **Version bump to `0.24#17.4-r9`** (`versionCode` 3065)。用户说明见 [docs/archive/RELEASE_NOTES_0.24-17.4-r9.md](docs/archive/RELEASE_NOTES_0.24-17.4-r9.md)。

### Verify (真机)
1. 左栈依次加 3 个应用，切顶无需重新加载，画面状态保持；三应用同屏可见、无需滚动
2. 点非栈顶应用（图标/标题/灰底均可）→ 立即置顶显示并关掉 Recent
3. 加第 4 个 → 栈底旧应用被关掉，新应用在顶
4. 中↔左滑动移动单应用；满栈挤出正确
5. 分隔条 swap 后两栈整体对调且画面正确；两侧应用窗口铺满各自 VD（无黑边/旧尺寸残留）
6. 断线 &lt;180s 重连 / 冷启动 restore：栈序与栈顶恢复
7. Close 栈顶后自动显示下一应用；关光后出现空窗选择器
8. 同应用不能同时出现在左、中两栈
9. 左栈同时有高德+Google：画面是谁，`LastSplit` left / left_stack 末项就是谁（可用 `settings get global aadisplay_last_split_left` 对照 `am stack list` 的 visible）
10. 第二分屏全屏 → 打开堆栈 → 点第一分屏非栈顶应用 → peel 切回第一分屏：应显示刚置顶的应用
11. 全屏 peel：长按仅震动，松手后才出 Recent；再点 peel 仍灵敏（无「点不动」粘滞）
12. 全屏 peel 拖出分屏：两侧应用铺满各自 VD；`dumpsys window windows` 中 Requested 宽应等于 frame/VD（无 Requested 卡在中间宽度）

## 0.24#17.4-r8

### Changed
- **隐藏桌面图标：** `MainActivity` 改为 `MAIN` + `CATEGORY_INFO`（去掉 `LAUNCHER`）；激活状态页从 LSPosed → AADisplay 打开。
- **移除手机侧悬浮框：** 删除悬浮控制布局与 inflate 路径；会话策略（Delay Destroy 180s、keep-awake）仍在无头路径运行，断开后仍会如期释放双 VD。
- **Version bump to `0.24#17.4-r8`** (`versionCode` 3064)。

## 0.24#17.4-r7

### Changed
- **r6→r7 逻辑收敛（行为冻结）：** peel 命中几何收拢到 `SplitPane.peelHitContains`；`touchAaDisplay` 的 ATMS 1..64 改为会话一次性门闩；Facet rail reclaim 去掉 10s 推迟链，依赖「永不 GONE DecorView」不变量立即折叠 + 一次 layout settle；`reportAaUiDisplayId` 在 surface 就绪时补报。不改贴边 peel / 双 VD 全屏 / GPU 预览 / AutoOpen 重试语义。
- **全屏 peel 拖动手把位置 / 条宽：** 全屏把手只作入口；一旦拖动即 morph 成与分屏相同的通长缝+三点，clip 缝也用同一套 `DIVIDER_DP` 几何。分屏条 elevation 用空 outline，去掉周边 Material 阴影。
- **全屏 peel 把手对齐：** 命中区不再通高/通宽（避免 elevation 投出第二条“分屏条”阴影）；短胶囊与命中框同中心贴边。
- **全屏 peel 短胶囊把手：** 全屏不再画通高/通宽加亮缝（易像坏屏亮线），改为外缘吸附的抽屉式短把手（内侧圆角）+ 三点；深色半透明底 + 浅描边/点，亮暗画面都更好认。两端触摸穿透到全屏 app。点按切换 / 向内拖退出 / 长按最近任务；**贴真左/顶缘**（不再 inset 80dp 悬空）。Coolwalk rail steal 在 peel 命中带改走 `touchAaDisplay` 注入 AaDisplayActivity，避免贴边后点不到。
- **车机合成减负：** 分屏 TextureView 标为不透明；全屏时垫后 pane 用 `INVISIBLE`（不断 Surface）跳过合成，背后导航/直播仍继续渲染。
- **分屏条拖动 GPU 预览：** 拖动中不再每帧改 pane `layoutParams` / TextureView 尺寸；分屏用 scale+translate 预览，全屏 peel 用 `clipBounds`。松手再 layout + 一次 VirtualDisplay.resize。
- **触控注入改为 oneway AIDL：** `touchPane` / `touchPrimaryPane` 不再阻塞 AA / `:car` UI 线程等 `injectInputEvent`；DOWN 不再额外打 `setFocusedPane`（服务端已设）。**安装后需重启**，否则新 client 等不到旧 system_server 的 two-way reply。
- **Coolwalk 栏 steal 缓存全屏：** MOVE 不再同步查询 `splitFullscreenPane`；DOWN 查一次，并用 `ACTION_SPLIT_STATE_CHANGED` 更新缓存。
- **Version bump to `0.24#17.4-r7`** (`versionCode` 3063)。

### Fixed
- **全屏 peel 松手比例对不上：** 过退出阈值后原先一律恢复进全屏前比例，预览却按手指位置画，松手后要二次拖。改为把松手比例 clamp 后写回 UI + `setSplitRatio`（仍先 `setSplitFullscreen(NONE)`）。
- **AutoOpen 调了但进不去 AaDisplay：** 收左侧 rail 时 `applyZeroWidthGone` 沿父链把 `DecorView` 也 GONE，Coolwalk `CarSystemUiControllerService.a()` 对 OEM 启动变成静默空操作。改为禁止折叠 DecorView / window root，并保留 AutoOpen 分档重试窗口（reclaim 不再依赖 10s 推迟）。
- **system_server SIGSEGV（进车机直接重启）：** `SplitPresentationGuard` 对已无 Surface 的 `WindowState` 调 `removeImmediately` → OneUI `SurfaceControl.Transaction.reparent` 空指针。改为先走 `WMS.removeWindow`，并在无 live SurfaceControl / 已 `mRemoved` 时跳过 `removeImmediately`。
- **全屏 peel 触控条进导航栏点不动：** `AaDisplayActivity` 的 presentation VD 是 `FLAG_PRIVATE`（应用 uid），system_server 的 `DisplayManager.getDisplays()` / `getDisplay(id)` 都枚举不到。上报的 id 若再经 `getDisplay` 校验会被误丢，`touchAaDisplay` 一直 “display not found”。改为信任 `reportAaUiDisplayId`；ATMS 扫任务仅作会话一次性兜底（失败即门闩，不在每次 touch 盲扫）。
- **AA `:projection` 崩于 `RailStatusBarFragment` / `status_bar`：** 收左侧 rail 时 `removeView`/重挂载拆掉了 Coolwalk 的 `R.id.status_bar` 容器，FragmentManager 报 `No view found for id …/status_bar`。改为原地 GONE/零宽折叠，不再拆树。
- **全屏记忆进车机只剩「点击选择应用」：** 恢复 `LastSplit` 全屏时，`initViews` 过早把垫后 pane 的 TextureView 设为 `INVISIBLE`，第二个 Surface 永不就绪 → 双 VD 不创建 → 自动拉起记忆应用失败。Surface 未齐前保持双 pane 可见，创建后再隐藏垫后层。
- **部分机型 Coolwalk 菜单栏触控被误吞（r6 回归）：** `:car` 用瘦长几何扫 DisplayManager 时，竖屏手机主屏（如 1080×2340）会被当成 FacetBar 并锁死 `displayId=0`；`CarDisplayId` 反射又扫到 `describeContents()==0`，整屏 HU 触控被 steal 后 `param.result=null`，原栏点不动。改为：禁止 DEFAULT_DISPLAY；优先 named FacetBar；无 LayoutInfo 只用绝对窄条（≤120px）；`CarDisplayId` 只认白名单 accessor；binder 注入失败不吞事件。`setSplitFullscreen` AIDL 挪到接口末尾以免旧 Stub 事务号错位。
- **全屏后媒体无声（抖音 LivePlay）：** 进全屏双 VD resize 触发三星 `ExtraDisplayController.positionChildAt`，垫后 pane 丢失 top-resumed / window focus → 抖音 `silence audio`。全屏切换后对两 pane `moveTaskToFront` + `setFocusedTask`（0/120/400ms）；仅 resize 尺寸真正变化的一侧。

## 0.24#17.4-r6

### Added
- **双 VD 全屏（一显一隐）：** 拖分屏条越过左右/上下边缘阈值后松手，一侧铺满、另一侧叠在背后继续渲染（开车导航全屏 + 影音背后；停车可反过来）。两 VirtualDisplay 都保持满屏缓冲，不销毁背后任务。全屏时分隔条变为贴边 peel（固定左/顶缘，inset 避开 Coolwalk rail）；向内拖 peel 过阈值退出并恢复进入前比例；点按 peel 切换可见全屏 pane；长按仍打开最近任务。`LastSplitStore` 持久化 `fullscreenPane`（ratio 仍存分屏比例）。Coolwalk rail steal 在全屏时注入可见 pane，不再固定 PRIMARY。

### Fixed
- **全屏/分屏左侧原导航栏（Coolwalk rail）触控失效：** FacetBar VD 常保留各车机自己的宽度但窗口 GONE，Coolwalk 仍把该带触控打进去 → InputDispatcher 直接丢弃。steal 改为优先按 **目标 display**（FacetBar / 瘦长 rail 几何）整段截获，再用 **观测到的 rail 宽度**（`content_bounds` / FacetBar VD，不用写死 px、不再 0.9 收缩留死缝）做 x 带兜底；注入前 `rewriteMotionEvent`（uptime + TOUCHSCREEN），全屏仍打可见 pane。

### Changed
- **全屏 peel 把手固定左侧：** 点按切换可见全屏 pane 时分隔条不再左右跳；左右分屏始终贴左缘（inset ~80dp 避开 Coolwalk LHD rail，方便驾驶员够到），上下分屏贴顶缘。向内拖仍退出全屏；全屏时线条/三点略加亮加粗便于找。
- **分屏容器改为 FrameLayout 定位：** 以显式宽高/边距替代 LinearLayout weight，便于全屏叠层与 peel 预览共用同一套 pane 视图。
- **Version bump to `0.24#17.4-r6`** (`versionCode` 3062)。

## 0.24#17.4-r5

### Changed
- **最近任务去掉缩略图：** 不再通过 ATMS `getTaskSnapshot` 拉硬件截图；卡片只保留图标 + 名称 + 灰色底板。打开 Recent 时 Binder 更轻，车机列表更不容易卡。
- **关闭按钮放大居中：** 灰色卡片中央放 56dp 圆形 Close；点卡片空白不再打开任务（只点图标/标题才切换），减少车机误触。
- **去掉 Material / AppCompat：** Main / AA / 悬浮窗改用平台 `Theme.AADisplay`；去掉 `material`、`rikkax.appcompat`，并拦截传递依赖的 `androidx.appcompat`。APK 更瘦，主题路径更短。
- **依赖与打包精简：** 自实现 ViewBinding inflate，去掉 `ViewBindingUtil` 与未使用的 Lifecycle；打包排除 `DebugProbesKt.bin` / `kotlin-tooling-metadata.json`；开启 R8 optimized resource shrinking。
- **清理无效钩子：** 删除无操作的 `isCallerAllowedToLaunchOnDisplay` 补丁；移除 `AaBasicsHook`（Play 安装来源伪装，当前 AA 路径不再需要）。
- **Version bump to `0.24#17.4-r5`** (`versionCode` 3061)。

## 0.24#17.4-r4

### Fixed
- **分屏分隔条两端误触交换：** 分隔条加宽命中区原先盖住虚拟屏四角/边角控件；两端留出 inset 不消费触控，避免点到角上被当成点分隔条而交换分屏。

### Changed
- **减轻日志压力：** 热路径减少噪声日志，降低连接/分屏期间的 log IO。
- **主题逻辑精简：** 收敛多余 theme 资源与包装，Main / AA / 悬浮窗主题路径更直接。
- **移除 `OtherHook`：** 删除无效的状态栏高度补丁，并从 `xposed_scope` 去掉 `com.autonavi.amapauto`；模块仅注入 System Framework + Android Auto。
- **`Utils` 包对齐：** `log` / `logDebug` 归属 `io.github.nitsuya.aa.display.xposed.util`。
- **Version bump to `0.24#17.4-r4`** (`versionCode` 3060)。

## 0.24#17.4-r3

### Fixed
- **Douyin LivePlay covers the other pane + 方控 dead (r3):** LivePlay uses MediaRouter / `createWindowContext(TYPE_PRESENTATION)` to attach a fullscreen presentation onto the sibling AA VD (e.g. task on SECONDARY while PRIMARY/高德 is covered and keys have no focus sink). Drop `VIRTUAL_DISPLAY_FLAG_PRESENTATION` on AA VDs; block foreign presentation attach/`addWindow` in `AndroidHook.PanePresentationGuard`; harden `SplitPresentationGuard` eviction (`removeImmediately`, dynamic delays). Before key inject, evict + bring task to front.
- **方控「下一曲/上一曲」切直播间 (r3):** on live-style top activities, `MEDIA_NEXT`/`MEDIA_PREVIOUS` inject a vertical fling using **current** VD `getRealSize` (split ratio / resize safe) instead of media-session fallback (which was driving QQ 车载音乐). Feed still uses normal media keys. After pane swap, resolve the LivePlay VD across both panes (do not trust `mFocusedPane` alone — focus can stay on 高德 and wrongly drive QQ).

### Changed
- **Version bump to `0.24#17.4-r3`** (`versionCode` 3059).


## Historical notes (pre-r3)

Shipped archaeology moved to [CHANGELOG_ARCHIVE.md](CHANGELOG_ARCHIVE.md).
