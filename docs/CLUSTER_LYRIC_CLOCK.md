# 仪表横条歌词与进度时钟（奥迪 / Android Auto）

真机问题记录与后续试验备忘。改 `AaClusterLyricEgressHook` / `ClusterLyricMediaService` 前先读本文。

**验证环境（截至 2026-08）：** Gearhead 17.4、奥迪仪表横条、QQ 车载 / HD / 汽水。

---

## 1. 现象

| 现象 | 典型表现 | 与根因 |
|------|----------|--------|
| 换句闪 0:00 | 同曲下一句歌词时，剩余时间先归零再恢复 | HU MediaInfo `song` 变 → 车机内部重置 |
| 换句跳秒 | `-2:59` → `-3:00` → `-2:59`，整秒 ±1 抖动 | 换句多包 PlaybackStatus，秒数不一致 |
| **换句时间反复** | 如 `0:29` → `0:30` → `0:29` → `0:32`（先前进、回退、再前进） | 快照补包 + 窗前的自然 progress 打架（T2-A 已修 push 路径） |
| 已解决（T2-A） | 歌词随句滚；**不闪 0:00**；真切歌才应重置时钟 | MediaInfo 后补 PlaybackStatus（Store 外推整秒） |

三个时钟问题**根因不同**，不要混为一谈：

- **0:00** → MediaInfo 触发重置；需 **极短时间内正确的 PlaybackStatus** 盖住
- **跳秒** → 换句时 **多包** PlaybackStatus，整秒差 1
- **时间反复** → **过期快照** push + **500ms 窗管不到 MediaInfo 前** 的自然包

---

## 2. 数据流（简图）

```
QQ / 汽水 MediaSession
    → ClusterLyricMirror（system_server，LRC 取句）
    → ClusterLyricStore（Settings.Global，含 extrapolatePosition）
    → :cluster ClusterLyricMediaService（Title = 当前歌词行）
    → Gearhead 读壳 session（getPlaybackState 被 Egress 外推改写）
    → GAL 出站（T1 不 hook GAL，Gearhead 自然行为）
         ├─ MediaInfo（song/artist/duration/art…）  → 仪表横条歌词
         └─ PlaybackStatus（整秒 position）         → 剩余时间 / 进度条
    → AaClusterLyricEgressHook（gearhead :projection / :car）
         ├─ metadata getter / getPlaybackState 从 Store 注入
         ├─ HU meta_p：注入 song / album；lyric-only 开 500ms playback 窗
         ├─ play_l：缓存 lastPlaybackState（作模板）；play_q：窗内限 1 包
         ├─ meta_p afterHook：若无自然 playback → pushPlaybackNow（Store 外推整秒）
         └─ setTitle 原地改字（AA 顶栏，不驱动仪表歌词）
```

**关键约束：** 仪表横条歌词来自 **HU MediaInfo.song**（= 壳 Title），不能只 hook 顶栏而不发 MediaInfo（v5 对照）。

---

## 3. 车机侧语义（经验归纳）

### 3.1 MediaInfo

- **song** 来自 MediaSession **Title**；我们用它传「当前歌词行」。
- 每换一句 Title 变 → Gearhead 再发 HU MediaInfo。
- 奥迪侧：**HU `song` 变仍会触发内部重置**（v5 拦 HU 则不重置、但歌词不滚）。
- 肉眼「不闪 0:00」≠ 没重置，而是 **重置后被 PlaybackStatus 立刻拉回**（T1 / 恭喜版）。

### 3.2 PlaybackStatus

- 协议里进度是 **整秒**，无毫秒。
- 车机在包与包之间 **本地 extrapolate**；收到新整秒会重画。
- 换句时多包秒数不一致 → 跳秒或 **时间反复**（旧秒覆盖新秒）。

---

## 4. 壳 session 约定（`ClusterLyricMediaService`）

| 项 | 约定 |
|----|------|
| `MEDIA_ID` | `aadisplay.cluster:` + 按**曲目**稳定 id（**不含** `artRevision`） |
| 同曲换句 `lyricOnly` | **只** `setMetadata`；**不** `applyProgress` / **不** `scheduleProgressReassert` |
| 真切歌 | 完整 metadata + `applyProgress` pre/post meta |

`lyricOnly` 判定：duration / subtitle / album / artMediaId / shellMediaId 不变，仅 `title` 变。  
换句进度由 **Egress playback 窗 + push** 负责，壳侧不额外造 progress 包。

---

## 5. 当前落地：T2-A（2026-08-23 晚）

在 T1（playback 500ms 窗 + `pushPlaybackNow`、不 hook GAL）基础上，**方案 A**：`pushPlaybackNow` 不再重放 `play_l` 过期快照，改为用 `ClusterLyricStore.extrapolatePosition` 当下整秒克隆 `AaPlaybackState` 再 push（反射失败 fallback T1 快照）。

### 5.1 行为表

| 层 | lyric-only 行为 |
|----|-----------------|
| **ClusterLyricMirror** | LRC 取句写 Store；200ms pending flush（**无**方案 C 整秒延迟） |
| **ClusterLyricMediaService** | 只 `setMetadata` |
| **Egress HU `meta_p`** | 完整出站 + 注入当前歌词 / album |
| **Egress GAL** | **不 hook** |
| **Egress `play_l`** | 持续缓存 `lastPlaybackState` / `lastPlaybackPkg`（push 模板） |
| **Egress `play_q`** | lyric-only **500ms 窗**：首包放行，后续 drop |
| **Egress `meta_p` afterHook** | 窗内尚无 natural playback → **`pushPlaybackNow`**（**Store 外推整秒** 克隆 state） |
| **Egress `getPlaybackState`** | Store `extrapolatePosition` 外推（供 Gearhead 读壳 session） |

### 5.2 换句事件链（T2-A）

```
LRC 换句 → Mirror.publish → 壳 lyricOnly setMetadata
    → Gearhead 可能先 play_q（窗外，不拦截）     ← 若仍反复，见 T2-B
    → meta_p beforeHook：判定 lyricOnly，开 500ms 窗
    → MediaInfo 出站（song=新歌词）→ 车机可能内部归零
    → meta_p afterHook：
         若窗内尚无 play_q → pushPlaybackNow(Store 外推整秒)  ← 不再用过期快照
         若窗内已有 play_q → 跳过 push
    → 500ms 内后续 play_q 全 drop
    → 500ms 后自然 progress 恢复
```

`pushPlaybackNow` **仅事件驱动**（非定时）：唯一调用点在 `meta_p` afterHook；真切歌不走此路径。

### 5.3 真机（T2-A，奥迪横条，待验证）

| 项 | 结果 |
|----|------|
| 歌词随句 | ✅（继承 T1） |
| 换句闪 0:00 | ✅ **不闪**（继承 T1） |
| 经典跳秒 `-2:59⇄-3:00` | 多数 **不明显** |
| **时间反复** `0:29→0:30→0:29→0:32` | **待真机**（T2-A 目标消除） |

### 5.4 三层模型（分析用）

```
1. 触发层   HU song 变 → 车机内部重置（难消）
2. 掩盖层   MediaInfo 后极短窗口内有效 PlaybackStatus → 0:00 肉眼不可见（T1/T2-A 核心）
3. 副作用层 快照秒数 vs 本地走时 / 窗前包 → 跳秒或时间反复（T2-A 修 push；窗前包仍待 T2-B）
```

---

## 6. 沿革：恭喜版 → T1 → T2

### 6.1 恭喜版（`e25ce7a`，8/22 23:02）

8/22 约 22:11 真机：**歌词滚、不闪 0:00**，剩 `-2:59⇄-3:00` 小跳变。

| 通道 | 行为 |
|------|------|
| HU MediaInfo | 完整（song=歌词 + duration + 封面） |
| GAL MediaInfo | 仍发包，lyric-only **剥** duration/art/rating bit |
| PlaybackStatus | 500ms 窗 + **`pushPlaybackNow`** |

22:19 **去掉补 progress** → **0:00 回来** → 补包是必要条件。  
8/23 下午讨论结论：**GAL 剥字段对 0:00 可能非因果**；核心是 **playback 补包策略**。

### 6.2 T1（2026-08-23 下午）

恭喜版 **只恢复 playback 窗 + push**，**去掉 GAL hook**。  
真机：**不闪 0:00** 与恭喜版一致 → **GAL 剥字段可省略**。换句偶发 **时间反复**（快照 push）。

### 6.3 T2

消除 **时间反复**，在保留「不闪 0:00」前提下：

| 优先级 | 做法 | 状态 |
|--------|------|------|
| **A** | `pushPlaybackNow` 用 **Store 当下外推整秒**，不用 `lastPlaybackState` 快照 | **已落地**（2026-08-23 晚） |
| **B** | 500ms 窗内 **drop 全部 natural play_q**，**只 push 一包** Store 秒数 | 待试 |
| **C** | push 前 **不倒退** guard：`pushSec >= lastSentSec`（含窗外已出站包） | 待试 |

勿再试：盲 **+1s**；仅加长抑制窗（管不到 MediaInfo 前 play_q）。

---

## 7. 历史试验矩阵（8/23 上午，v5～v11）

**说明：** 下列为 **无 T1 补包** 或 **错误补包** 时的记录；**不能**直接否定当前 T1。

| 代号 | HU metadata | GAL | PlaybackStatus | 0:00 | 备注 |
|------|-------------|-----|----------------|------|------|
| **恭喜** `e25ce7a` | 完整 | 剥 bit 仍发 | 窗 + push | ✅ 不闪 | 易跳秒 |
| **T1** | 完整 | 不 hook | 同恭喜 playback | ✅ 不闪 | 时间反复 |
| **v5** | **拦截** | 拦截 | 正常 | ✅ 不闪 | 歌词不滚 |
| **v8** | 完整 | skip | 不抑制不补 | ❌ 稳定闪 | 无 push |
| **v9** | 同 v8 | skip | push + 丢 0 包 | ❌ 无变化 | 观测/包序失败 |
| **v10** | 同 v8 | skip | 同 v8 + 方案 C | ❌ 无变化 | 无有效 push |
| **v11** | 歌词 | GAL 曲名 | — | ❌ 仍闪 | 已回滚 |
| **A** | 完整 | 剥 bit | 1200ms 全丢 | ❌ 仍闪 | 无补包 |
| **D** | 剥 art+duration | 剥 bit | 全丢 | `0:00\|-0:00` | 封面丢 |

**历史推论（仍成立）：**

1. 歌词须 **HU song**；setTitle 原地不能驱动仪表（v5）。
2. HU metadata 须带 **duration**（v7 剥 duration → 进度丢）。
3. **完全不补 progress** → 0:00 回来（22:19、方案 A）。

**需修正的历史结论：**

- ~~「事后 push 无法阻止 0:00」（v9）~~ → 在 **play_l 缓存 + HU 完整 + 正确窗** 下可盖住（恭喜 / T1）。
- ~~「不可兼得三角」~~ → T1 下 **不闪 0:00 ∧ 歌词 ∧ 进度走** 已兼得；剩 **时间反复** 待 T2。

---

## 8. 代码地图

| 文件 | 职责 |
|------|------|
| `xposed/cluster/ClusterLyricMirror.kt` | 绑 session，写 Store；快句 pending flush |
| `xposed/cluster/ClusterLyricStore.kt` | 歌词 + 进度；`extrapolatePosition` |
| `service/ClusterLyricMediaService.kt` | 壳 session；lyricOnly **只** setMetadata |
| `xposed/hook/aa/AaClusterLyricEgressHook.kt` | T2-A：meta_p / play_q / play_l / getter；`buildAaPlaybackStateForPush` |

**Egress 关键符号（T2-A）：**

- `LYRIC_ONLY_PLAYBACK_SUPPRESS_MS` — 500ms playback 窗
- `pendingLyricOnlyUntilElapsedMs` / `lyricOnlyPlaybackSent`
- `buildAaPlaybackStateForPush` / `pushPlaybackNow` — Store 外推整秒手补
- DexKit：`meta_p`、`play_q`（`Error updating playback status.`）、`play_l`（`playbackstate cannot be null`）
- **无** `gal_h` / `gal_k` / GAL skip

**日志标签：** `AAD_AaClusterLyricEgressHook`

- `HU metadata lyric-only window`
- `pushPlaybackNow storeSec=N` / `fallback=true` / `drop duplicate HU playback`

---

## 9. 真机验证清单

1. **同曲换句**：歌词变；**不**闪 0:00；**不**出现 `0:29→0:30→0:29→0:32` 式反复
2. **真切歌**：时钟归零；duration / 封面正常
3. **快句 LRC**：不丢句
4. **QQ ↔ 汽水**：源切换正常

若 **仍闪 0:00** → 查 log `playQ`/`playL` 是否 hook 成功；DexKit 缓存是否 stale（`SCHEMA` bump 后 force-stop gearhead）。

若 **时间反复** 仍出现 → 见 §6.3 T2-B；log 是否 **窗外 play_q 后再 push**。

---

## 10. 调试命令

```bash
adb shell settings get global aadisplay_cluster_np_title
adb shell settings get global aadisplay_cluster_np_position_ms
adb shell settings get global aadisplay_cluster_np_duration_ms

adb logcat -s AAD_AaClusterLyricEgressHook AAD_ClusterLyricMedia

adb shell am force-stop com.google.android.projection.gearhead
```

```bash
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :aa-display:assembleDebug
adb install -r aa-display/build/outputs/apk/debug/aa-display-*.apk
```

改 `ClusterLyricMirror` / 壳 → **重启手机**；改 Egress → `force-stop gearhead`。

---

## 11. 变更记录（摘要）

| 日期 | 变更 |
|------|------|
| 2026-08-22 | **恭喜版** `e25ce7a`：GAL 剥 bit + playback 窗 + `pushPlaybackNow` → 不闪 0:00 |
| 2026-08-23 上午 | v5～v11 矩阵；v8/v10 无有效 push → 仍闪 0:00 |
| 2026-08-23 下午 | **T1**：恢复恭喜 playback 链路，**不 hook GAL** → 不闪 0:00；仍有时间反复 |
| 2026-08-23 晚 | **T2-A**：`pushPlaybackNow` 用 Store 外推整秒克隆 `AaPlaybackState` |

详见 `CHANGELOG.md`。

---

## 12. 已证伪 / 勿再走

| 路径 | 为何失败或过时 |
|------|----------------|
| 换句不发 MediaInfo | 歌词不更新（v5） |
| 换句 progress 全丢（方案 A） | 0:00 回来 |
| HU 剥 duration（v7） | 进度丢失 |
| skip GAL + 无有效 push（v8） | 稳定闪 0:00 |
| v9 式 push（观测失败 / 0 秒包） | 与 v8 无差别 |
| 方案 C 整秒延迟 + 壳 pre-progress（v10） | 仍闪 |
| 壳 lyric-only `scheduleProgressReassert` | 多造 progress 包 |
| 盲 +1s 快照 | 易过头；应用 Store 外推 |
| 仅加长 500ms 窗 | 管不到 MediaInfo **前** play_q |
| setTitle 驱动仪表歌词 | v5 证伪 |

**仍可试（T2-B/C）：** 窗内只发一包 Store 秒数；push 前不倒退 guard。

---

## 13. 因果链（当前理解）

```
LRC 换句
  → 壳 setMetadata(新 Title)
  → [可选] Gearhead play_q 窗外先出站 (秒 N+1)
  → HU MediaInfo(song=歌词) → 车机内部重置
  → T2-A: meta_p afterHook pushPlaybackNow(Store 外推整秒) 或 窗内首包 natural
  → 肉眼: 不闪 0:00；T2-A 消除 N+1→N 快照回退；窗前包仍可能打架 (T2-B)

若无 push / 无有效 playback → 闪 0:00 (v8)
若拦截 HU MediaInfo → 不闪、歌词不滚 (v5)
GAL 剥不剥 → 对 0:00 掩盖 **非必要**（T1 真机对照）
```

---
