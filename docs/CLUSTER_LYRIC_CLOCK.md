# 仪表横条歌词与进度时钟（奥迪 / Android Auto）

真机问题记录与后续试验备忘。改 `AaClusterLyricEgressHook` / `ClusterLyricMediaService` 前先读本文。

**验证环境（截至 2026-08）：** Gearhead 17.4、奥迪仪表横条、QQ 车载 / HD / 汽水。

---

## 1. 现象

| 现象 | 典型表现 |
|------|----------|
| 换句闪 0:00 | 同曲下一句歌词时，剩余时间先归零再恢复 |
| 换句跳秒 | 如 `-2:59` → `-3:00` → `-2:59`，或先退 1s 再进 2s |
| 已解决（旁路） | Title 能随句刷新；真切歌才应重置时钟 |

两个问题**根因不同**，不要混为一谈：

- **0:00** → MediaInfo 被车机当成「新歌」
- **跳秒** → 换句时多包 **PlaybackStatus**，且整秒与车机当前显示差 1

---

## 2. 数据流（简图）

```
QQ / 汽水 MediaSession
    → ClusterLyricMirror（system_server，LRC 取句）
    → ClusterLyricStore（Settings.Global）
    → :cluster ClusterLyricMediaService（Title = 当前歌词行）
    → Gearhead 读壳 session
    → GAL 出站
         ├─ MediaInfo（song/artist/duration/art…）  → 仪表横条歌词 + 曲目身份
         └─ PlaybackStatus（整秒 position）         → 剩余时间 / 进度条
    → AaClusterLyricEgressHook（gearhead :projection / :car）
         ├─ 改写 shell MEDIA_ID 的 Title/Artist/封面
         ├─ setTitle 原地改字（AA 顶栏，避免 StatusBar 切歌动画）
         └─ 同曲换句（v10）：HU 完整 MediaInfo；skip GAL；不抑制 PlaybackStatus
```

**关键约束：** 仪表横条歌词最终来自 **MediaInfo.song**（= 壳 Title），不能只 hook 顶栏而不发 MediaInfo。

---

## 3. 车机侧语义（经验归纳）

### 3.1 MediaInfo

- **song** 来自 MediaSession **Title**；我们用它传「当前歌词行」。
- 每换一句 Title 变 → Gearhead 会再发一包 MediaInfo（协议上像新歌名变了）。
- 奥迪侧（本机经验）：**HU MediaInfo 的 song 文本变化** → 换句时 **稳定闪 0:00**（v8～v10），与事后 PlaybackStatus 补包无关（v9）。
- 早期假设「剥 GAL duration/art 即不当新歌」在 **v3～v6** 未单独解决 0:00；**歌词必须走 HU song**（v5/v6 对照）。

相关常量（历史 GAL proto 剥字段，`v10` 未启用）：

- `PROTO_HAS_SONG` `0x01` — 保留，歌词要能出去
- `PROTO_HAS_ART` `0x08`、`PROTO_HAS_DURATION` `0x20`、`PROTO_HAS_RATING` `0x40` — lyric-only 时剥掉

### 3.2 PlaybackStatus

- 协议里进度是 **整秒**，无毫秒。
- 车机剩余时间 ≈ `duration − floor(positionSeconds)`，收到新包会按新秒重画。
- 换句时若多包秒数不一致（差 1），就会看到剩余时间「跳一下」。

---

## 4. 壳 session 约定（`ClusterLyricMediaService`）

| 项 | 约定 |
|----|------|
| `MEDIA_ID` | `aadisplay.cluster:` + 按**曲目**稳定 id（**不含** `artRevision`，晚到封面不能当新歌） |
| 同曲换句 `lyricOnly` | 先 `applyProgress(force)` 再 `setMetadata`；**不** `scheduleProgressReassert` |
| 真切歌 | 完整 metadata + `applyProgress` pre/post meta |

`lyricOnly` 判定：duration / subtitle / album / artMediaId / shellMediaId 不变，仅 `title` 变。

---

## 5. 方案与状态

### ✅ 当前落地（v10，2026-08-23 晚）

| 层 | lyric-only 行为 |
|----|-----------------|
| **ClusterLyricMirror** | 方案 C：LRC 换句 `publish` 延迟到下一整秒（`lyricPublishDelayMs`） |
| **ClusterLyricMediaService** | 先 `applyProgress(force)` 再 `setMetadata` |
| **Egress HU `llv#p`** | **完整出站**（song + duration + 封面） |
| **Egress GAL MediaInfo** | **跳过**（`param.result=null`，800ms 窗） |
| **Egress PlaybackStatus** | **不抑制、不补包**（v9 证伪 egress 事后 push） |
| **稳定 MEDIA_ID** | 按曲目，不含 `artRevision` |

**真机（v10）：** Title + 进度 ✅；换句仍 **稳定闪 0:00**（方案 C + 壳 pre-progress 无改善）。

### 试验结论矩阵（奥迪横条，2026-08-23）

| 代号 | HU metadata | GAL MediaInfo | PlaybackStatus | Title/歌词 | 进度 | 0:00 闪 |
|------|-------------|---------------|----------------|------------|------|---------|
| **A** | 完整 + GAL 剥 bit | 剥 duration/art | 1200ms **全丢** | ✅ | 偶发丢 | ❌ 仍闪 |
| **B** | 完整 | — | 放行未改写 0 秒包 | ✅ | — | ❌ **更久** |
| **D** | **剥 art+duration** | 剥 bit | 全丢 | ✅ | ❌ `0:00\|-0:00` | 常显 |
| **v3–v4** | 完整 | 剥/改 GAL + 补包 | 抑制+push/改写 | ✅ | 差 | ❌ 稳定 0:00 1～2s |
| **v5** | **拦截** | **拦截** | 正常 | ❌ 不滚 | ✅ | ✅ **不闪** |
| **v6** | 拦截 | **剥 bit 放行** | 正常 | ❌ | ✅ | ✅ |
| **v7** | 完整但 **剥 duration** | 跳过 | 正常 | ✅ | ❌ **丢失** | — |
| **v8** | **完整** | **跳过** | 正常 | ✅ | ✅ | ❌ **稳定闪** |
| **v9** | 同 v8 | 同 v8 | +push/改写/丢 0 包 | ✅ | ✅ | ❌ **无变化** |
| **v10** | 同 v8 + 壳 pre-progress | 同 v8 | 同 v8 + **方案 C** | ✅ | ✅ | ❌ **无变化** |

### 推理备忘（由矩阵归纳）

1. **歌词通道**：仪表横条读 **HU `llv#p` 的 song**（= 壳 Title）。GAL 剥/发均不能单独驱动歌词（v5/v6）；拦截 HU 则歌词不更新。
2. **进度通道**：HU metadata 须带 **duration**（v7 剥 duration → 进度丢失）；PlaybackStatus 正常通行即可维持走时（v5/v8）。
3. **0:00 触发**：**HU MediaInfo 换 song 出站** 即闪（v8），与事后补 PlaybackStatus 无关（v9 无改善）。拦截 HU 可不闪（v5）但无歌词。
4. **GAL 角色**：skip GAL 时 v8 已能 Title+进度；补 GAL 剥/改/推均未单独修好 0:00（v3–v4、v6）。
5. **setTitle 原地**：不能替代 HU MediaInfo 驱动仪表歌词（v5）。
6. **方案 C / 壳 pre-progress**：未减轻 0:00 闪（v10）；闪可能发生在 MediaInfo 到达瞬间，对齐整秒不够。
7. **不可兼得三角（当前奥迪）**：`不闪 0:00` ∧ `歌词随句` ∧ `进度正常` — 尚未同时满足；最接近 **v8**（后两者 ✅，闪 0:00 待接受或另找 HU 只改字协议）。

### ❌ 方案 D（真机证伪，2026-08-23 早）

**假设：** HU `llv#p` 换句仍带 duration/封面 → cluster 当新歌 → 0:00；与 GAL 一样剥掉即可。

**做法：** lyric-only 时 HU `args[3]=null`、`args[4]=-1`（GAL 仍剥 art/duration bit）。

**真机结果（奥迪横条，换 Title / 换句）：**

| 现象 | 表现 |
|------|------|
| 封面 | **丢失**（换句后横条无封面） |
| 进度 / 剩余时间 | **丢失**，显示 **`0:00 \| -0:00`** |
| 歌词文字 | 能随句更新（Title 通道仍通） |

**日志：** `HU metadata lyric-only drop art/duration` 每次换句均触发。

**推论：**

1. **剥封面** → 车机把 MediaInfo 当成「无封面条目」，横条清空封面（同曲封面其实未变，不应剥）。
2. **剥 duration + 方案 A 零进度包** → 车机无总时长、无 position → 剩余时间算不出来，出现 **`0:00` 与 `-0:00`**（比只闪一下更糟）。
3. 方案 D **不能**在「A 全丢进度」之上叠加；要补进度须 **B'**，且 **不能剥掉车机显示所需的 duration/封面上下文**（或只剥 GAL protobuf bit、HU 保留 art）。

**下一步（方案 D' + B'，未做）：**

- HU lyric-only：**只剥 duration（或仅 GAL 剥 bit）**，**保留封面字节**（`artLen` 未变时不改 `args[3]`）。
- 进度：**`playbackCacheMethod` 缓存上一包** → 换句后 **仅当能拿到有效秒数时** 补 **一包** PlaybackStatus；`sec<0` 则全丢。
- 勿再 HU+GAL 同时 `args[3]=null`。

### 回归备忘：为何「以前不闪 0:00、现在又闪」

| 时期 | 进度策略 | 0:00 | 跳秒 |
|------|----------|------|------|
| `e25ce7a` 等 | GAL 剥字段 + **留第一包 HU playback** + **`pushPlaybackNow`** | 常被拉回 | 易跳 |
| 8/22 方案 A | 删 `pushPlaybackNow`，进度 **全丢** | 回归 | 减轻 |
| 8/23 方案 B（未修好观测） | 放行未改写 0 秒包 | **更久** | — |
| 8/23 **方案 D** | A + HU 也剥 art/duration | **封面丢、0:00\|-0:00 常显** | 未测 |
| 当前 | **v10**（HU 完整 + skip GAL + 方案 C） | **稳定闪** | 待验 |

### ❌ 方案 B（真机证伪）

**假设：** 收到 MediaInfo 后须 **恰好一包** PlaybackStatus 才能从 0:00 拉回。

**真机结果（2026-08-23）：** `lastHuSecondsSent` 始终 `-1`（HU `AaPlaybackState` 反射取秒失败 / 平常播放未走观测路径）。实现仍 **放行第一包** 但未改写秒数 → Gearhead 发出 **position=0** 的进度包直达车机，0:00 **比方案 A 更久**。

**若再试 B：** 须先修好 `lastHuSecondsSent` 观测（如 `playbackCacheMethod`）；且 **`sec < 0` 时改回全丢**，绝不放行未改写的包。

### ⏳ 方案 C（已试，不能单独根治 0:00）

- LRC 换句延迟到下一整秒再 `publish`（`ClusterLyricMirror.lyricPublishDelayMs`）
- 壳 lyric-only 先 `applyProgress` 再 `setMetadata`
- **v10 真机：** Title + 进度仍 ✅，**仍稳定闪 0:00**

### ❌ 已证伪 / 勿再走

| 路径 | 为何失败 |
|------|----------|
| 换句完全不发 MediaInfo | 仪表歌词不更新 |
| 换句完全不发 PlaybackStatus（未剥 duration 时） | 仍 0:00；剥了之后看 A/B |
| 壳 `setPlaybackState` / `PROGRESS_REASSERT` 换句补进度 | 多造进度包 |
| Egress 用 Store 外推重造 `AaPlaybackState` | 秒与 Gearhead 不一致 |
| `snapClusterPositionMs` 四舍五入 / 只进不退 / floor | 与车机本地 +1 规则打架 |
| `lyricOnlySeconds` 来自外推再写 GAL `args[2]` | 仍跳秒 |
| 方案 B 在 `lastHuSecondsSent=-1` 时仍放行 HU playback | 0 秒包直达车机，0:00 比方案 A 更久 |
| **方案 D：HU+GAL 换句剥 art+duration，且零进度包** | 封面丢；`0:00 \| -0:00` 常显 |
| 留第一包 HU playback + `pushPlaybackNow` 第二包 | 两包整秒打架 → 退 1 进 2 |
| **v9：metadata 后 `pushPlaybackNow` + 改写/丢 0 秒 HU playback** | 与 v8 无差别，仍稳定闪 0:00 |
| **v10：方案 C 整秒对齐 + 壳先推 progress** | 仍稳定闪 0:00 |
| **v5/v6：拦 HU 或仅靠 GAL 剥字段** | 时钟对、歌词不滚或 GAL 不传字 |
| **v7：HU 只剥 duration** | 歌词 OK、**进度丢失** |
| setTitle / StatusBar 原地改字 | 不驱动仪表横条歌词（v5） |

---

## 6. 代码地图

| 文件 | 职责 |
|------|------|
| `xposed/cluster/ClusterLyricMirror.kt` | 绑 QQ/汽水 session，写 Store |
| `xposed/cluster/ClusterLyricStore.kt` | 歌词 + 进度 Settings；`extrapolatePosition` |
| `service/ClusterLyricMediaService.kt` | 壳 session；`lyricOnly` 先 progress 再 metadata |
| `xposed/hook/aa/AaClusterLyricEgressHook.kt` | HU 完整 + skip GAL；setTitle 原地 |

**Egress 关键符号（v10）：**

- `isLyricOnlyGalPacket` / `LYRIC_ONLY_GAL_SKIP_MS` — lyric-only 跳过 GAL MediaInfo
- `hookGearheadTitleAndProgress` — setTitle / metadataPush / galMediaInfo skip
- DexKit：`meta_p`、`gal_h`、`gal_k`（`play_q`/`play_l`/`gal_i` 解析保留，v10 未 hook playback）

**日志标签：** `AAD_AaClusterLyricEgressHook` — `HU metadata lyric-only`、`skip GAL MediaInfo lyric-only`

---

## 7. 真机验证清单

连接 AA 后逐项看仪表横条（非手机顶栏）：

1. **同曲换句**：歌词随句变；**不**闪 0:00；剩余时间**不**跳 1～2 秒
2. **真切歌**：时钟归零合理；duration / 封面正常
3. **快句 LRC**：不丢句（与时钟无关，属 Mirror 节流）
4. **QQ ↔ 汽水切换**：源切换正常，无旧封面残留

若 **仍闪 0:00** → 见 §11 试验矩阵；事后补 playback / 整秒延迟均无效时，考虑：**接受短暂闪烁**、或 **HU 拦截 + 另寻歌词通道**（目前无）、或调研 HU 是否另有「仅改显示字、不带曲目身份」字段。

若 **仍跳秒** → `adb logcat` 看是否还有未 hook 的 playback 出站路径。

---

## 8. 调试命令

```bash
# 看壳写的歌词与进度（需 root / adb shell）
adb shell settings get global aadisplay_cluster_np_title
adb shell settings get global aadisplay_cluster_np_position_ms
adb shell settings get global aadisplay_cluster_np_duration_ms

# Egress 日志
adb logcat -s AAD_AaClusterLyricEgressHook AAD_ClusterLyricMedia

# 改 hook 后重启 gearhead 即可；改 system_server 侧需重启
adb shell am force-stop com.google.android.projection.gearhead
```

安装 debug：

```bash
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :aa-display:assembleDebug
adb install -r aa-display/build/outputs/apk/debug/aa-display-*.apk
```

---

## 9. 变更记录（摘要）

| 日期 | 变更 |
|------|------|
| 2026-08 | 剥 GAL MediaInfo duration/art；稳定 MEDIA_ID |
| 2026-08 | 去掉外推改秒、pushPlaybackNow、双包取整等错误路径 |
| 2026-08 | **方案 A**：lyric-only 窗口 HU+GAL PlaybackStatus 全丢 |
| 2026-08 | 方案 A 抑制窗 500ms → 1200ms |
| 2026-08 | 方案 B 试验失败（`lastHuSecondsSent=-1` 仍放行 0 秒包）→ 回退方案 A |
| 2026-08-23 | **v5～v10** 矩阵试验；**v10** 当前：HU 完整 + skip GAL + 方案 C + 壳 pre-progress；**0:00 仍闪** |

详见 `CHANGELOG.md`。

---

## 10. 继续试验时建议顺序

```
瓶颈：v8/v10 — Title+进度 ✅，换句稳定闪 0:00

已证伪勿再投入：
  - egress 事后 pushPlaybackNow（v9）
  - 方案 C 整秒延迟 + 壳 pre-progress（v10）
  - 拦 HU + GAL strip / setTitle（v5/v6）
  - HU 剥 duration（v7）
  - 进度全丢 / 未观测的方案 B
  - **v11：HU 歌词 + GAL 原曲名**（仍闪 0:00；身份不听 GAL song）

可选方向（低把握）：
  - 壳 TITLE=稳定曲名、DISPLAY_TITLE=歌词，看 Gearhead 是否用 DISPLAY 填 HU song
  - 其他车型对比
  - 产品接受 v8 短暂闪烁

改代码时保持：同曲勿剥 HU 封面；歌词走 HU song；duration 与进度绑定。
```

---

## 11. 分版本试验记录（2026-08-23）

**环境：** `0.24#17.4-r12`，Gearhead 17.4，奥迪仪表横条，QQ 车载。  
**部署：** 改 `ClusterLyricMirror` / 壳 → **重启手机**；改 Egress → `force-stop gearhead`。

### v11 — HU 歌词 + GAL 原曲名（已回滚）

| 做法 | HU `song`=歌词；GAL `song`=QQ/汽水原曲名；GAL duration/封面完整、不 skip |
| 结果 | 歌词仍滚；换句 **仍闪 0:00** |
| 推论 | 奥迪切歌/时钟 **不跟 GAL song 是否稳定**；HU `song` 变仍重置。猜想错误，代码已回滚 |

### v5 — 拦截 HU + GAL MediaInfo

| 做法 | lyric-only 时 HU、GAL metadata 均 `param.result=null`；setTitle 原地改字 |
| 结果 | 进度 ✅；**不闪 0:00**；Title **不滚动** |
| 推论 | 闪 0:00 由 **出站 MediaInfo** 触发；setTitle **不能**驱动仪表歌词 |

### v6 — 拦截 HU + GAL 剥 duration/art

| 做法 | HU block；GAL `args[3]=null` + proto 剥 duration/art/rating |
| 结果 | 进度 ✅；Title **无变化** |
| 推论 | 仪表歌词 **不读 GAL 剥字段后的 song**（或非歌词主通道） |

### v7 — HU 剥 duration，GAL skip

| 做法 | HU song/art 保留，`args[4]=-1`；GAL skip |
| 结果 | Title ✅；**进度丢失** |
| 推论 | HU **duration 为进度显示必需** |

### v8 — HU 完整 + GAL skip（egress 基线）

| 做法 | HU 完整出站；GAL skip；playback 不抑制 |
| 结果 | Title ✅，进度 ✅；换句 **稳定闪 0:00** |
| 推论 | song 变 + duration 在 → 功能完整，但车机 **仍重置显示** |

### v9 — v8 + egress 进度校正

| 做法 | metadata 后 `pushPlaybackNow`；改写/丢弃 0 秒 HU playback |
| 结果 | 与 v8 **无肉眼差别** |
| 推论 | **事后补 playback 无法阻止** MediaInfo 已触发的 0:00 |

### v10 — v8 + 方案 C + 壳 pre-progress

| 做法 | Mirror 换句延迟到下一整秒；lyric-only 先 `applyProgress` 再 `setMetadata` |
| 结果 | Title ✅，进度 ✅；**仍稳定闪 0:00** |
| 推论 | 与 natural 进度整秒对齐 **不能消除** HU song 变更带来的重置 |

### 早期：方案 A / B / B' v1～v4 / D

| 代号 | 要点 | 结果摘要 |
|------|------|----------|
| **A** | 1200ms 内 HU+GAL playback 全丢 | 仍闪 0:00 |
| **B** | 放行未改写 0 秒首包（`lastHuSecondsSent=-1`） | 0:00 **比 A 更久** |
| **B' v1～v3** | 抑制进度 + push / GAL 改写秒数 | 稳定 0:00 1～2s |
| **v4** | skip GAL + 双通道 push | 稳定 0:00 |
| **D** | HU+GAL 剥 art+duration，零进度包 | 封面丢，`0:00\|-0:00` 常显 |

### 因果链（推理用）

```
LRC 换句 → 壳 setMetadata(新 Title)
         → Gearhead HU llv#p(song=歌词, duration, art)
         → 奥迪：显示归零闪 0:00
         → 后续 PlaybackStatus 恢复走时（v8 可见进度正常）

若拦截 HU llv#p → 不闪、歌词不更新（v5）
若 HU 剥 duration → 歌词可更新、进度上下文丢失（v7）
若事后 push playback（v9）或整秒延迟（v10）→ 仍闪
```

---
