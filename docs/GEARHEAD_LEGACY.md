# Gearhead / Google Car SDK 原路 — 已硬下线

运行时已切断（AAP 替代）：

- LSPosed `xposed_scope` **无** `gearhead`
- `XposedInit` **仅**装 `AndroidHook`（system_server）；**无** `AndroidAutoHook` / `AaDisplayProcessHook`
- Manifest **无** `AaActivityService` / `gms.car.application` / `:cluster` MediaBrowser 导出
- `CHANNEL_AA` 仅作读迁移哨兵 → `defaultChannel()`
- 仪表歌词 / 方向盘由 AAP（`AapNowPlaying` / `AapInputKeys`）承担
- gearhead DexKit 钩子源码已删除（含 Frx Maps/TTS、`AaUiHook`/Coolwalk、签名/媒体/仪表 Egress）；无 DexKit 依赖
- Car SDK 投影壳 / Coolwalk 轨带结算 / Title 媒体壳 / `aauto.aar` **已删除**；出画仅 AAP / CarPlay Host GL
- 原壳广播总线（`SPLIT_STATE_CHANGED` / `SHOW_RECENT_TASK` / `AA_UI_RAIL_CONSUME` / …）已删；仅留 `IME_VISIBILITY`
- `automotive_app_desc.xml` / 状态页 `rb_channel_aa` / `androidx.media`（Title 壳）已删

## 仍必须保留

| 面 | 原因 |
|----|------|
| `aap/cert/*`、证书 Receiver / UI | 从 gearhead **APK** 提 CarService 证 |
| `CarPlayChannel.GEARHEAD_PACKAGE` | 证书与默认通道检测 |
| `CoreApi` / 双 VD / `DisplaySessionPolicy` | 出画核心 |
| `ClusterLyricMirror` / Store | AAP / CarPlay 歌词源 |
| Host GL / Input / AudioCapture | AAP 与 CarPlay 共用 |

## AIDL 孤儿方法（保留 ordinal，实现 no-op）

| 方法 | 说明 |
|------|------|
| `touchAaDisplay` / `reportAaUiDisplayId` / `touchPrimaryPane` | 原 Car SDK presentation / Coolwalk 偷触控 |
| `reportCoolwalkRailSnapshot` / `getCoolwalkRailSnapshot` / `getCoolwalkReconnectEpochMs` / `notifyCoolwalkFullBleed` | 原 FacetBar / content-slot settle |

历史文档：[`docs/archive/COOLWALK_FACETBAR.md`](archive/COOLWALK_FACETBAR.md)。
