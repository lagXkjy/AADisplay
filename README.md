# AADisplay-Split-Lite

[![基于](https://img.shields.io/badge/基于-Nitsuya%2FAADisplay-blue?logo=github)](https://github.com/Nitsuya/AADisplay)
[![分支参考](https://img.shields.io/badge/参考-Stashboy%2FAADisplay-blue?logo=github)](https://github.com/Stashboy/AADisplay)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK min 33](https://img.shields.io/badge/Android%20SDK-%3E%3D%2033-brightgreen?logo=android)
![Android SDK target 36](https://img.shields.io/badge/Android%20SDK-target%2036-brightgreen?logo=android)

在 [Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay)（原作者）与 [Stashboy/AADisplay](https://github.com/Stashboy/AADisplay)（分支作者）基础上 Fork 修改而来的 **精简分屏版**：通过 LSPosed 在 Android Auto 车机上投屏运行手机应用，支持自定义双窗分屏、多应用保活与方向盘 / 蓝牙键鼠操作。

## 版本与文档

| 项 | 说明 |
|----|------|
| 当前版本 | `0.24#17.4-r14`（`versionCode` 3074） |
| 变更记录 | [CHANGELOG.md](CHANGELOG.md) |
| 历史版本说明 | [docs/archive/](docs/archive/) |
| 开发者 / AI | [AGENTS.md](AGENTS.md)、运行时链路 [docs/EXECUTION.md](docs/EXECUTION.md) |

---

## 系统特点

### 精简设计

- **无配置界面**：Delay Destroy 固定 **180 秒**；Auto Open、Restore Last Split 始终开启；不支持调整 DPI。
- **不再申请 SU**：App 进程不申请 Magisk `su`（VirtualDisplay 等能力经 Xposed → system_server）。
- **无桌面图标**：激活状态页从 LSPosed → AADisplay 打开；手机侧悬浮框已移除。
- **不依赖第三方 Launcher**：内置应用选择器，不再纠结嘟嘟 MINI、氢桌面等选型。

### 分屏与多任务

- **自定义双 VirtualDisplay 分屏**，不依赖系统分屏壳；回收 Coolwalk 左侧导航轨，内容区更宽。
- **每窗最多 3 个应用保活**：选新应用时旧应用进栈；栈顶为当前画面；满 3 个再加则挤出栈底。
- **分屏比例 / 全屏 peel / 断线记忆**：拖动调比例、拖过边缘进全屏、下次连接自动恢复左右栈序与比例。
- **Recent 三列**：左 → 虚拟屏左，中 → 虚拟屏右，右 → 手机；左 / 中列底栏可「添加应用」。

### 交互与输入

- **分屏条三个小点**（详见下方使用教程）：
  - **拖动**：调整分屏比例（拖过边缘可进全屏；全屏向内拖可退出）
  - **点按**：交换左右分屏（全屏时点 peel 切换可见侧）
  - **长按**：进入 Task 应用栈（**松手后再打开**；全屏 peel 同理）
- **方向盘**：短按仍控媒体键；长按下一曲 / 快进 → 交换分屏或切全屏侧；长按上一曲 / 快退 → 开 / 关 Recent。
- **手机蓝牙键鼠**（连手机，AA 会话活跃时）：窗内当触控；分隔带可拖比例、短按交换、长按开 Recent；Ctrl+WASD 调比例、Ctrl+S 交换、Ctrl+R / Tab 开 Recent、中键切到对侧窗。

### 媒体与兼容

- **仪表横条歌词**：QQ 音乐车载、QQ 音乐 HD、汽水——三源同时只能一个播放，谁在播仪表跟谁；AA 顶栏同步歌词。
- **音视频单发声**：音乐 、 抖音（AvMedia）同时只留一个发声；地图 / 浏览器压顶不会自动抢掉正在播的音乐。
- **Android Auto 17.4 兼容**：**初次连接**须先装 Google App、Google Maps、Google 语音服务（TTS）完成 AA 首次设置；**过后可卸载**，模块会绕过 FRX 安装检查。抑制空媒体卡；锁屏下全屏把手仍可用。
- **虚拟屏后仍支持方向盘控制**（短按注入焦点窗；直播等场景 next/prev 可能改写为滑动）。

---

## 运行要求

- Android 13+（SDK 33+）
- 已 Root，并安装 LSPosed（或兼容 Xposed）
- Android Auto（`com.google.android.projection.gearhead`）

---

## 安装与激活

1. 编译或安装 APK（见下方「构建」）。
2. 在 LSPosed 中启用本模块，至少勾选：
   - **System Framework**
   - **Android Auto**
3. **重启设备**（system_server 侧钩子需重启才生效）。
4. 在 LSPosed → AADisplay 打开模块（无桌面图标），确认状态为 **已激活** / **未激活** / **需要重启**。
5. **首次连接车机前**，在手机上安装 **Google App**、**Google Maps**、**Google 语音服务（TTS）**，按 AA 走完首次设置；**首次连接成功并进入分屏后，这三项可删除**（后续重连不再依赖它们）。
6. 连接 Android Auto，等待 Auto Open 进入分屏壳后验证触控与任务切换。

改 AA 侧钩子后：可 `force-stop` gearhead 重拉；改 system_server 侧逻辑仍需重启手机。

---

## 使用教程

### 1. 首次连接与选应用

> **重要：Google 三件套（仅首次需要）**  
> 第一次连 Android Auto 时，手机上须已安装 **Google App**、**Google Maps**、**Google 语音服务（TTS）**，并完成 AA 自带的首次设置向导。  
> **首次进车机分屏成功后即可卸载这三项**——模块会绕过 FRX 的必装检查，日常重连不再要求它们。若从未装过就直连，可能卡在 AA 首次引导而无法进分屏。

连接车机后模块会自动尝试打开 AADisplay 分屏界面（Auto Open）。若某一侧为空窗，**点击空白区域**打开应用选择器，点选即可启动到对应窗格。左右两窗相互独立，同一应用不能同时出现在左右两栈。

### 2. 分屏条操作

分屏条位于两窗之间的 **三个小点** 上：

| 手势 | 分屏模式 | 全屏（peel）模式 |
|------|----------|------------------|
| **拖动** | 实时预览比例，松手后生效 | 向外拖可 peel；松手超过阈值则退出全屏 |
| **点按** | 交换左右两窗（含栈） | 只切换可见侧，不搬栈 |
| **长按** | 松手后打开 Recent 三列 | 同左；手机锁屏时仍可开 Recent |

注意：长按必须在 **手指抬起之后** 才打开 Recent，避免触控序列被打断导致 peel「点不动」。

### 3. Task 应用栈（Recent）

**入口**：分屏条长按（松手后）打开全屏 Recent 面板。

| 列 | 含义 |
|----|------|
| 左列 | 左窗（Primary）应用栈，底 → 顶 |
| 中列 | 右窗（Secondary）应用栈 |
| 右列 | 手机主屏上的任务 |

**常用操作**：

- **点整行**（图标 / 标题 / 灰底）：将该应用拉到栈顶并显示，同时关闭面板。
- **Close（×）**：关闭该任务；若关的是栈顶，自动显示栈中下一个。
- **左 / 中列底栏「添加应用」**：在不挤掉当前显示的前提下往栈里加应用。
- **拖拽排序**：调整同栈内顺序（松手后持久化）。
- **右列任务拖到左 / 中列**：把手机上的应用搬到对应虚拟屏。

### 4. 全屏 peel

- 拖动分屏条 **越过边缘** 可进入单侧全屏（另一侧 VD 仍存在，只是被 peel 盖住）。
- **点按 peel 把手**：在全屏时切换显示哪一侧。
- **向内拖动 peel**：拖过退出阈值后回到分屏。
- 全屏时 **长按 peel** 同样是在 **松手后** 打开 Recent。

### 5. 手机列

Recent 最右列为手机任务，可用于把手机上的应用 **滑到左 / 右窗**。在手机列 Close 任务 **不会** 清除分屏记忆快照。

### 6. 断开与重连

- 断开 Android Auto 后，虚拟屏默认 **延迟 180 秒** 再销毁（Delay Destroy），短断线重连可保留 VD 与应用状态。
- 超过 180 秒或进程被系统回收后，需重新选应用；若存在有效快照，会自动 **Restore Last Split**（栈序、比例、全屏状态）。

### 7. 方向盘

| 操作 | 效果 |
|------|------|
| 短按播放 / 暂停 / 上一曲 / 下一曲等 | 注入到当前焦点窗（控制正在显示的应用） |
| **长按** 下一曲 / 快进 | 与 **点按分屏条** 相同：分屏时交换左右；全屏时切换可见侧 |
| **长按** 上一曲 / 快退 | 打开 / 关闭 Recent 三列 |

### 8. 手机蓝牙键鼠

前提：键鼠连接 **手机**（非车机 USB）；AA 会话活跃且非 Delay Destroy 空窗期。

| 操作 | 效果 |
|------|------|
| 窗内移动 / 左键 | 当触控注入当前窗格应用 |
| 光标停在 **分隔带** 再拖动 | 调整分屏比例 |
| 分隔带 **短按** | 交换左右窗 |
| 分隔带 **长按再松手** | 打开 Recent |
| **中键** | 跳到对侧窗中心并切换焦点 |
| **Ctrl + 方向键 / WASD** | 分屏比例 ±5% |
| **Ctrl + S** | 交换左右窗 |
| **Ctrl + R / Tab / App Switch** | 打开 Recent |

折叠屏等设备上光标由车机壳自绘；更多细节见 [docs/EXECUTION.md §10.1](docs/EXECUTION.md#101-手机蓝牙键鼠操作说明)。

### 9. 音乐与仪表歌词

支持将歌词推到 **奥迪等车型的仪表横条** 与 AA 顶栏，当前绑定三源（同时只能一个播放）：

- QQ 音乐车载（`com.tencent.qqmusiccar`）
- QQ 音乐 HD（`com.tencent.qqmusicpad`）
- 汽水（`com.luna.music`）

**音视频互斥**：音乐与抖音等 AvMedia 同时只留一个发声；播抖音时音乐会暂停，仪表歌词也会清空。地图、浏览器等压顶 **不会** 自动暂停正在播放的音乐。

技术备忘见 [docs/CLUSTER_LYRIC_CLOCK.md](docs/CLUSTER_LYRIC_CLOCK.md)。

---

## 构建

```bash
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :aa-display:assembleDebug
./gradlew :aa-display:assembleRelease
```

产物：`aa-display/build/outputs/apk/*/aa-display-*.apk`（版本号中 `#` 替换为 `-`）。

---

## 开发者文档

| 主题 | 文档 |
|------|------|
| 项目约束 / 改动规则 | [AGENTS.md](AGENTS.md) |
| 运行时链路（进程、IPC、触控） | [docs/EXECUTION.md](docs/EXECUTION.md) |
| FacetBar / 重连分辨率 | [docs/COOLWALK_FACETBAR.md](docs/COOLWALK_FACETBAR.md) |
| Open/Close 卡顿分析 | [docs/OPEN_CLOSE_LAG.md](docs/OPEN_CLOSE_LAG.md) |
| 仪表歌词时钟试验 | [docs/CLUSTER_LYRIC_CLOCK.md](docs/CLUSTER_LYRIC_CLOCK.md) |
| 历史版本用户说明 | [docs/archive/](docs/archive/) |

---

## 免责声明

用爱发电。不同手机差异巨大，没有义务为你的机型单独适配；也无法保证能解释「为什么你不能用」。

使用本模块即代表自愿承担一切后果，包括但不限于设备损坏、驾车事故。  
任何由本项目衍生出的项目，本项目不承担任何责任。  
开发者可能在任何时间停止更新或删除项目。

- 请不要在行驶过程中使用视频应用。
- 请不要在行驶过程中操作应用。

## Thanks

- [Nitsuya](https://github.com/Nitsuya) — 原作者
- [Stashboy](https://github.com/Stashboy) — 分支作者

## License

继承上游许可证，GPL-3.0 license。
