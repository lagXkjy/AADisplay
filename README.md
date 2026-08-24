# AADisplay Split-Lite — Community Fork R1.0

> **Direct upstream:** [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay), branch `AADisplay-Split-Lite`
> **Upstream chain:** `lagXkjy/AADisplay` → `Stashboy/AADisplay` → `Nitsuya/AADisplay`
> **License:** GNU GPL v3.0

这是基于 **lagXkjy/AADisplay 的 `AADisplay-Split-Lite` 分支**继续修改的社区版本。该直接上游仓库本身 Fork 自 Stashboy/AADisplay，而其 README 继续致谢 Nitsuya/AADisplay 原作者。

本仓库不是原创重写。发布和再分发时请保留 GPL-3.0 许可证、上游版权/署名与本仓库的修改说明。

## 当前版本

- **公开版本：Community Fork R1.0**
- Android `versionName`：`Community Fork R1.0`
- `versionCode`：`3067`
- 上游源码快照：`0.24#17.4-r11`（**未正式发布 / post-r10 development snapshot**）
- 当时最近的正式发布说明：`0.24#17.4-r10`
- 直接源码基础：`lagXkjy/AADisplay@AADisplay-Split-Lite`
- 实测环境详情：[TESTED_ENVIRONMENT.md](TESTED_ENVIRONMENT.md)
- 本 Fork 修改：[FORK_CHANGES.md](FORK_CHANGES.md)
- R1.0 发布说明：[RELEASE_NOTES_COMMUNITY_FORK_R1.0.md](RELEASE_NOTES_COMMUNITY_FORK_R1.0.md)

### 上游源码版本说明

本 Fork 使用的是当时从 `lagXkjy/AADisplay` 的 `AADisplay-Split-Lite` 分支取得的源码快照。该快照中：

- `aa-display/build.gradle.kts` 已标记 `versionName = "0.24#17.4-r11"`、`versionCode = 3067`；
- 上游 README 已写“当前模块版本 `0.24#17.4-r11`”；
- 但上游 `CHANGELOG.md` 中 r11 相关内容仍位于 **Unreleased**；
- 源码包仅带有 `RELEASE_NOTES_0.24-17.4-r10.md` 和 r9 发布说明，没有独立的 r11 Release Notes。

因此，本仓库将该直接上游准确描述为：**`0.24#17.4-r11` 未正式发布的 post-r10 开发快照**；而获取源码时最近有正式发布说明的上游版本为 **`0.24#17.4-r10`**。这两者不应混为一谈。详见 [UPSTREAM.md](UPSTREAM.md)。

## 已实测环境

Community Fork R1.0 已在以下组合上进行实际车辆测试：

| 项目 | 已实测环境 |
|---|---|
| 手机 | Samsung Galaxy S24 Ultra |
| 系统 | Android 16 / One UI 8.5 |
| Root | KernelSU |
| 框架 | LSPosed |
| 车辆 | 2022 Audi A4 allroad |
| 投屏 | Android Auto + AADisplay Split-Lite |
| DHU | 已用于开发测试 |

在这套组合上已验证的重点包括：双分屏显示与触摸、手机输入桥、`←` 返回、`⌨` 手机输入入口、应用选择器字母索引、Android Auto 断开清理，以及 Samsung Screen Curtain（“幕布”）开启状态下断开 AA 的稳定性。

> **兼容性声明：** 当前只把上表组合视为“已实测”。其他三星机型、其他 Android / One UI / Android Auto 版本、其他 Root/LSPosed 版本、其他车型和车机可能可以使用，但尚未验证，不作兼容保证。

## Community Fork R1.0 的主要改动

在尽量保留直接上游显示、VirtualDisplay 尺寸和分屏逻辑的前提下，本 Fork 增加或调整：

1. **手机输入桥**：车机点击输入框后，可在手机侧输入，再发送到当前 AAD 分屏。
2. **Android Auto 安全断开**：加强 Samsung Android 16 / One UI 环境下的 VirtualDisplay、任务和唤醒资源清理，降低断开 AA 后卡死或软重启风险。
3. **Samsung Screen Curtain 兼容**：避免 AA 已退出后仍继续维持与幕布冲突的显示唤醒行为。
4. **车机控制按钮**：`←` 返回、`⌨` 打开手机输入。
5. **应用选择器 A–Z 索引**：全部应用按字母/拼音首字母排序并支持快速跳转。
6. **16 KB page-size 兼容**：DexKit 使用 2.0.7，并适配相应 API。
7. **去调试应用标记**：Debug 构建不再声明为 `android:debuggable=true`。
8. **Android Studio 兼容构建配置**：AGP 8.11.2 + Gradle 8.13 + JDK 17。

完整差异请见 [FORK_CHANGES.md](FORK_CHANGES.md)。

## 稳定区域：请谨慎修改

以下区域对真实车辆/车机兼容性非常敏感：

- VirtualDisplay width / height
- DPI / density
- 分屏比例计算
- TextureView / Surface 尺寸
- 触摸坐标映射
- Android Auto viewport / Coolwalk 显示区域

**Community Fork R1.0 刻意保留直接上游的 VirtualDisplay 尺寸与分辨率行为。** 当前版本没有加入开发过程中尝试过的固定 Audi viewport、强制 1280×600、固定 DPI 或其他针对 DHU 的强制显示方案。

原因很简单：DHU 上“看起来更满”的显示参数，不一定适合真实车机。任何涉及这些区域的修改，都建议同时在 **DHU + 实车**重新测试。

## 当前已知问题 / 行为

- Android Auto 底部原生导航栏在当前实测环境中可能保持可见。本版本接受这一表现，因为电话、语音、应用入口和媒体切换更方便。
- 真正关闭手机物理屏幕可能导致部分 ROM 停止 VirtualDisplay 合成；Samsung 设备建议优先使用 Screen Curtain（幕布）。
- 手机输入桥当前通过手机侧输入并将文本发送到目标分屏；极少数限制剪贴板/粘贴行为的 App 可能无法正常接收文字。
- 某些 App 自身禁止或不适配 VirtualDisplay，可能无法启动、黑屏或布局异常。
- Android Auto 更新可能改变内部类/方法，导致 Xposed/DexKit Hook 失效；升级 AA 后如突然失效，应先回报版本与日志，而不是先改分辨率。
- 当前 Samsung Android 16 / One UI 路径经过重点修复，但其他 OEM 的 system_server 行为可能不同。

## 运行要求

- Android 13+（`minSdk 33`）
- Root
- LSPosed
- Android Auto
- 推荐首次测试在停车状态完成

## 快速使用说明

### 1. 安装与启用

1. 在 Android Studio 中构建 APK，或安装与当前 Git tag 对应的 Release APK。
2. 在 LSPosed 中启用 AADisplay 模块。
3. 至少勾选：
   - **Android System Framework**
   - **Android Auto** (`com.google.android.projection.gearhead`)
4. 涉及 system_server Hook 的版本首次安装或更新后，建议完整重启一次手机。

### 2. 连接 Android Auto

正常连接车辆 Android Auto 后启动 AADisplay。Community Fork R1.0 不使用固定 Audi viewport、固定 1280×600 或强制 DPI。

### 3. 双分屏与应用选择器

- 左右两个 pane 可以分别运行应用。
- 应用选择器保留“最近”区域。
- “全部应用”按 A–Z / 拼音首字母排序。
- 使用右侧字母索引可快速跳到对应首字母。

### 4. 车机控制按钮

- `←`：向当前 AAD 分屏发送返回操作。
- `⌨`：手动打开手机输入桥。

### 5. 手机输入桥

推荐流程：

1. 在车机 AAD 分屏中点击需要输入文字的输入框。
2. 手机出现 AADisplay 输入页；若未自动打开，在车机点 `⌨`。
3. 使用手机正常系统输入法输入文字。
4. 点击 **“发送到车机”**。
5. 文本发送到当前目标 AAD 分屏中的输入框。

当前版本不再采用“强制把系统 IME 跨 Display 移到手机”的方案，以避免 One UI / Android 16 下的焦点与输入法 Display 混乱。

### 6. Samsung Screen Curtain（幕布）

在已实测的 Galaxy S24 Ultra 环境下，可在 AAD 工作期间使用 Samsung Screen Curtain。当前版本针对 AA 断开过程做了更保守的 VirtualDisplay / wake 资源清理。

首次在自己的设备与车辆上测试时建议：车辆停稳 → 确认 AAD 正常 → 开启幕布 → 断开 AA → 确认手机能正常恢复。

## 构建环境

当前公开版本按以下组合整理并测试：

| 组件 | 版本 |
|---|---|
| Android Gradle Plugin | 8.11.2 |
| Gradle | 8.13 |
| JDK | 17 |
| Kotlin | 2.2.10 |
| compileSdk | 36 |
| targetSdk | 36 |
| minSdk | 33 |
| DexKit | 2.0.7 |

> 不建议为了“追新版本”随意升级 AGP / Gradle / Kotlin / DexKit。尤其 DexKit 或 Android Auto 相关依赖变化后，应重新验证 AA Hook 是否正常加载。

Windows 构建：

```powershell
.\gradlew.bat :aa-display:assembleDebug
```

Linux/macOS：

```bash
./gradlew :aa-display:assembleDebug
```

当前 `versionName` 为 `Community Fork R1.0`，APK 输出文件名会整理为：

```text
AADisplay-Community-Fork-R1.0.apk
```

## Desktop Head Unit (DHU)

手机 Android Auto 开发者设置中启动 **Head unit server**，电脑执行：

```powershell
adb forward --remove-all
adb forward tcp:5277 tcp:5277
```

然后启动 Android SDK 中的 `desktop-head-unit.exe`。

DHU 主要用于开发验证；显示区域、比例与触摸相关修改最终应以真实车辆结果为准。

## FAQ / Troubleshooting

### AADisplay 没有正常出现在 Android Auto / 功能不生效

先检查 LSPosed 模块是否启用，并确认作用域至少包含 **Android System Framework** 与 **Android Auto**。更新模块后建议完整重启手机。

### Android Auto 升级后突然失效

先记录 Android Auto 版本。AA 内部类和方法可能变化，DexKit/Xposed Hook 可能因此失配。请先抓日志，不要先尝试强改 viewport、DPI 或 VirtualDisplay 尺寸。

### 手机输入页没有自动出现

先在车机目标 App 的输入框点一下，再按 AAD 的 `⌨` 按钮手动打开手机输入桥。

### “发送到车机”后没有文字

确认目标输入框仍处于可输入状态。个别 App 会限制剪贴板粘贴或特殊输入行为，这类 App 可能需要单独兼容。

### 为什么 Android Auto 底栏没有隐藏？

Community Fork R1.0 当前接受 Android Auto 原生底栏保持可见。在已实测车辆上，这样反而更方便使用电话、语音和媒体切换，因此暂不把“强制隐藏底栏”作为默认功能。

### 断开 AA 后手机异常 / 软重启怎么办？

请记录手机型号、系统版本、Android Auto 版本、Root/LSPosed 版本，并抓取日志。不要只描述“重启了”，日志对 system_server 问题非常重要。

### 如何抓基本日志？

```powershell
adb logcat -c
adb logcat > aad.log
```

复现问题后按 `Ctrl+C` 停止。公开日志前请先删除手机号、账号、联系人、位置、通知内容等个人信息。

## 隐私

Community Fork R1.0 本身不提供账号系统，也不以本 Fork 的名义收集遥测数据或上传手机输入桥中的文字。手机输入桥的文本处理发生在设备本地。

但本项目会在用户选择的第三方 App 与 Android Auto 环境中运行；第三方 App、Google Android Auto、系统组件的网络与隐私行为受它们各自的隐私政策约束，不属于本 Fork 的隐私承诺范围。

## 安全与驾驶免责声明

本项目涉及 Root、LSPosed、Android framework/system_server、Android Auto 与 VirtualDisplay。系统/Android Auto 更新、OEM 差异或错误修改可能导致应用崩溃、Android Auto 断开、界面失效，极端情况下可能导致 `system_server` / SystemUI 重启。

请在**停车状态**完成安装、配置、应用选择、输入功能和兼容性测试。驾驶过程中请遵守当地法律法规，不要操作会分散驾驶注意力的应用或界面。

本项目按 GPLv3 “as-is” 提供，不提供特定设备、车型或 Android Auto 版本的稳定性保证。

## Release 与源码对应

为了让二进制版本和 GPL 对应源码清晰匹配，建议每次公开 APK 都创建独立 Git Tag / GitHub Release。

Community Fork R1.0 推荐：

```text
Tag: v1.0-community
Release: AADisplay Split-Lite — Community Fork R1.0
APK: AADisplay-Community-Fork-R1.0.apk
```

发布 APK 时，应保证该 Tag 下的源码就是生成该 APK 的对应源码。

## 上游与致谢

本 Fork 的直接源码来源：

- **lagXkjy/AADisplay** — `AADisplay-Split-Lite` 分支（直接上游）
  https://github.com/lagXkjy/AADisplay/tree/AADisplay-Split-Lite
- **Stashboy/AADisplay** — lagXkjy 仓库所 Fork 的上游
  https://github.com/Stashboy/AADisplay
- **Nitsuya/AADisplay** — 原始项目作者/上游
  https://github.com/Nitsuya/AADisplay

感谢以上作者和贡献者。

## License

本项目作为 GPL-3.0 上游的修改版，继续以 **GNU General Public License v3.0** 发布。完整文本见 [LICENSE](LICENSE)。

如果分发本项目构建出的 APK，请同时遵守 GPLv3 对对应源码（Corresponding Source）提供方式的要求，并确保发布源码和 APK 版本相互对应。
