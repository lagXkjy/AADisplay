# CarPlayAudioCapture 拆分与同步审计

原 `xposed/carplay/CarPlayAudioCapture.kt` 单文件 2593 行，arm 路径（`enableLocked`）、
watchdog、AudioPolicy 反射、mute 机制混在一个 object 里，方法过重、改动易漏审计。
本次按关注点拆分并做行为保持审计。**无 AIDL / Binder / 日志 TAG / 隐藏 API stub 契约变化**。

## 1. 拆分结果（同包 `xposed.carplay`）

| 文件 | 行数 | 内容 | 状态归属 |
|------|------|------|---------|
| `CarPlayAudioCapture.kt` | 948 | 门面：install / setEnabled / enable·disable / rearm / readers / watchdog / pull；会话状态 | 保留会话状态（armed/desired/records/policy/mode/rings） |
| `CarPlayPhoneMute.kt` | 619 | stream + OEM fine + 设备索引 mute、CSM 250ms enforce 线程、VOLUME_CHANGED receiver、`AudioSystem.setStreamVolumeIndex` 反射 | **状态迁入**：phoneMuted / speakerDeviceMuted / savedVolumes / mutedStreamTypes / savedFineVolumes / savedSpeakerVolumes / savedSpeakerStreamTypes / volumeIndexFns / muteEnforceActive / muteMonitorThread / volumeReceiver |
| `CarPlayAudioPolicy.kt` | 719 | `AudioMix` / `AudioPolicy` 反射：buildAudioMix / bindMixRenderDevice / register·unregister / createAudioRecordSource(ForMixManual) / `openRenderArm` / `openSpeechMix` / `AudioPolicyRenderArm` | 无状态 object（`AudioManager` 走参数；projection 经 Acquire） |
| `CarPlayMediaProjectionAcquire.kt` | 285 | MediaProjection 获取阶梯：direct ctor / createProjectionInternal / binder / OEM `sem*` / 解包 / IMediaProjection 包装 | 无状态 object |
| `CarPlayAudioFormats.kt` | 82 | 48k stereo / 16k mono 采样率、通道掩码、环容量/水位、读帧大小、RENDER/SPEECH usages | 常量 object |
| `CarPlayPcmRing.kt` | 110 | 原嵌套 `PcmRing` 提升为顶层 `CarPlayPcmRing`（drop-to-live 水位环） | 自持（head/tail/available/dropped） |

外部调用面不变：`CoreManagerService` 只用 `install` / `setEnabled` / `pull` /
`pullSpeech` / `isSpeechCaptureReady`。ProGuard keep `io.github.nitsuya.aa.display.**`
覆盖全部新类；日志 TAG 统一保持 `AAD_CarPlayAudioCap`（logcat 过滤契约）。

## 2. 行为保持审计（逐项）

- **`openSpeechRecord`**：删掉恒为 null 的 `mediaProjection` 参数（唯一调用方
  `enableLocked` 传 null，原内部逻辑本就回退读字段）；现直接以字段为
  `sharedProjection` 传 `CarPlayAudioPolicy.openSpeechMix`。`createdProj` 的
  stop 条件 `sharedProjection==null && mediaProjection==null` ⇔ 新
  `sharedProjection==null`（同一值），等价。
- **mute 门控**：原内联 `(armed && mode==playback-capture) || (desired && phoneMuted)`
  → 引擎构造注入 `liveGate` / `holdGate` 两个 lambda（同文件可访问私有字段），
  `shouldEnforce()` 为同一谓词；enforce 线程与 receiver 行为一致（仍不取 opLock，
  VOLUME_CHANGED 同步派发死锁历史注释已迁入引擎头）。
- **`clearHeldMuteLocked` vs `disableLocked` restore 块**：两个原本近乎重复的
  清 mute 序列保留各自语义 → 引擎 `clearHeldMute(reason)`（未持 mute 时早退，
  arm 路径用）与 `teardown()`（无条件停 enforce 线程——覆盖 `phoneMuted=false`
  但线程仍活的边缘，disarm 路径用）。
- **`enableLocked` 局部 `proj` 变量**：当前 arm 路径下恒 null（remotesubmix 不建
  projection）的遗留量，**原样保留**（含 dormant playback-capture fallback），
  保证与旧代码 1:1，不借拆分顺手删。
- **`install` 时序**：CoreManagerService 在 system_server 钩子初始化时装载，
  早于任何 `setEnabled`；mute 引擎与 `audioManager` 同 guard 一次性创建，
  主对象所有 mute 调用走 `mute?.` 空安全，覆盖与旧 `audioManager==null` 分支一致。
- 其余（readers / rearm 节奏 / watchdog 判定顺序 / ring 水位 / projection 释放 /
  Binder identity 切换）逐段照搬，仅调用目标改为新类型。

## 3. 审计发现与处置

### 已修复

1. **`RENDER_UPGRADE_DELAY_MS` 死常量**：仅声明、零引用（deferred RENDER upgrade 已按
   「Never deferred-upgrade away」决策移除后的残留）→ 拆分时删除。
2. **RENDER 升级失败路径 projection 泄漏**（既有，潜伏，非拆分引入）：
   `tryUpgradeToAudioPolicyRenderLocked` 在 `startRecording()` 前把
   `renderArm.projection`（仅为 AudioPolicy.register 创建）赋入 `mediaProjection`
   字段；start 失败回滚只释放了 mediaRecord / speechRecord / policy，projection 未
   `stop()`，随后 `enableLocked()` 用局部 `proj`（null）覆盖字段 → 该 projection
   在 system 侧（MediaProjectionManagerService 的 canProjectAudio 表项）滞留至
   binder 死亡。**修复**：失败分支回滚补 `renderArm.projection?.stop()` +
   `mediaProjection = null`，arm 拥有的 4 个资源对称回滚。该分支当前不可达（arm 时无条件 `audioPolicyRenderUnavailable=true`，
   watchdog 升级门恒关），属潜伏风险修复，对现活路径零影响；若未来恢复 in-place
   升级，回滚不再留泄漏。

### 有意 dormant（非 bug，按原注释保留）

3. **playback-capture 整机 dormant**：`enableLocked` 固定 `remote-submix` 且 arm 时
   `audioPolicyRenderUnavailable=true` ⇒ 手机 mute 机制（stream/fine/device/enforce
   线程）、`enableLocked` 的 playback-capture fallback、watchdog 的 in-place RENDER
   upgrade 分支当前全部不可达。头注释早已声明「dormant helpers, do not re-arm」，
   故全部保留搬入 `CarPlayPhoneMute` / 门面，未删。

### 已清理（2026-11 确认删除，零行为影响）

4. **`ignoringProjectionStop` 只写不读 flag**（声明 + 5 对 true/false 写 = 11 行；其守护的
   MediaProjection onStop 回调链早已不存在）→ 已删（AUDIT E2）。
5. **`projectionVirtualDisplay` / `projectionImageReader` 永不被赋值**、
   `releaseProjectionVirtualDisplay()` 5 处调用点全 no-op（A 批 A3 删除后的二期）→
   2 字段 + no-op 函数 + 5 调用点 + 2 import 已删，净 -29 行（AUDIT E3）。

### 仅记录、保留

6. **`isEnabled()` / `isDesired()` 全仓无调用方**（公开 API，保留）。
7. **锁/竞争画像不变**：引擎内 `phoneMuted` 等仍为普通字段（写于 opLock 路径、
   读于 receiver/enforce 线程），与拆分前完全一致；未借机加锁，避免改变时序。

## 4. 验证

- `./gradlew :aa-display:assembleDebug` / `:aa-display:assembleRelease` 均通过。
- 无 CI / 自动化测试；上机回归清单：
  1. 安装 + LSPosed System Framework + 重启；
  2. 无线 CarPlay SETUP：车机出声、手机静音（remotesubmix 路径本就不 mute，
     确认扬声器不再出声的 OEM 行为与旧版一致）；
  3. 音乐 + 高德导航 TTS 同发（媒体环混音、speech 环）；
  4. 音量键按下 → 250ms enforce / VOLUME_CHANGED receiver 日志（`phone mute re-enforced`）；
  5. 断开（live=false）→ 手机音量恢复、`clear held mute` / `local-device mute restored` 日志；
  6. 5 分钟无 pull → `no pull … rearm keep-mute` 且手机保持 mute。
