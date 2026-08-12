# AADisplay-Split-Lite

[![基于](https://img.shields.io/badge/基于-Nitsuya%2FAADisplay-blue?logo=github)](https://github.com/Nitsuya/AADisplay)
[![分支参考](https://img.shields.io/badge/参考-Stashboy%2FAADisplay-blue?logo=github)](https://github.com/Stashboy/AADisplay)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK min 31](https://img.shields.io/badge/Android%20SDK-%3E%3D%2031-brightgreen?logo=android)
![Android SDK target 36](https://img.shields.io/badge/Android%20SDK-target%2036-brightgreen?logo=android)

在 [Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay)（原作者）与 [Stashboy/AADisplay](https://github.com/Stashboy/AADisplay)（分支作者）基础上 Fork 修改而来的精简分屏版。


## 版本

- 当前模块版本：`0.24#17.4-r2`
- 变更记录见 [CHANGELOG.md](CHANGELOG.md)

## 版本特点

### 精简

1. **配置写死**：去掉参数配置相关代码，使用默认配置；不支持调整 DPI，不再提供配置模块。
2. **不再申请 SU**：模块不再申请 Magisk `su` 等 root Shell 权限（VirtualDisplay 等能力仍经 Xposed → system_server）。
3. **Launcher 精简**：不再纠结嘟嘟 MINI、氢桌面等第三方 Launcher 选型。

### 亮点

1. **精简导航栏**，更充分利用车机屏幕。
2. **自定义分屏**，不再依赖系统分屏壳。
3. **Task 应用栈**右上角增加 Close 图标。
4. Task 应用栈调整为 **3 列**：左 → 虚拟屏左，中 → 虚拟屏右，右 → 手机。
5. **交互入口集中在分屏条上的三个小点**：
   - **长按**：进入 Task 应用栈，可杀应用、更换应用
   - **点击**：交换左右分屏
   - **拖动**：自由调整分屏比例
6. **分屏窗口记忆**：自动记忆最后一次分屏状态，下次连接车机自动恢复分屏比例。
7. **自定义应用选择器**，不再依赖 Launcher 启动应用。
8. 支持最新 **Android Auto 17.4**。
9. 增加虚拟屏后仍支持方向盘控制。

## 运行要求

- Android 12+（SDK 31+）
- 已 Root，并安装 LSPosed
- Android Auto（`com.google.android.projection.gearhead`）

## 快速开始

1. 编译并安装 APK。
2. 在 LSPosed 中启用本模块，至少勾选：
   - System Framework
   - Android Auto
3. 重启设备。
4. 打开 AADisplay，确认模块已激活。
5. 连接 Android Auto，验证分屏、触控与任务切换。

## 构建

```bash
./gradlew :aa-display:assembleDebug
./gradlew :aa-display:assembleRelease
```

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
