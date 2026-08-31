> **已归档。** 当前用法见 [README.md](../../README.md)。

# AADisplay 0.24#17.4-r14 版本更新

相对上一版 r13，这次重点是「手机蓝牙键鼠操作车机分屏」、Recent / 开关键应用更顺手，以及 LineageOS Android 16 上的兼容修复。

## 新功能 / 体验优化

### 手机蓝牙键鼠操作车机

- AA 会话活跃时，连在**手机**上的蓝牙键盘 / 鼠标会转到当前焦点虚拟屏（Delay Destroy 期间归还手机）。
- 鼠标：壳上自绘指针，可跨左右窗连续移动；落在分隔带上可拖比例、短按交换、长按开 Recent。
- 键盘：Ctrl+WASD / 方向键调比例，Ctrl+S 交换，Ctrl+R / Tab 开 Recent；中键切到对侧窗。
- 方向盘媒体键路径不变。

### Recent / 全屏 / Auto Open

- Recent 逻辑重构：VD 列以应用栈为顺序源，拖拽可改栈序并持久化；已打开时再长按 / Ctrl+R 只刷新不关。
- 全屏时在选择器或 Recent 点选另一侧应用，会**直接切到该侧全屏**（不必先退出全屏）。
- VD 列点空白区域也可关闭 Recent。
- Auto Open：短链打完仍未进分屏时，会走长重试兜底；控制器未就绪（含 NPE）按 not-ready 处理，减少偶发不自动进 AADisplay。

## 问题修复

### 开关键应用 / 分屏手势

- 选应用、交换分屏、Recent 关闭 / 滑动卡顿明显减轻；显式关闭不再误清断线记忆。
- 分隔条点按交换更稳，假长按不再误对调；拖比例后黑边 / 抖动减少。
- 退出全屏后中间分屏条可点。

### 蓝牙鼠标

- 跨窗不再跳中心；分隔带悬停不再乱跳；自绘指针替代 VD 上幽灵系统箭头。

### LineageOS Android 16

- 分屏 VD 强制进独立 DisplayGroup，并同步 override 组 ID，避免与手机 PowerGroup / 锁屏 / ColorFade 绑死。
- 虚拟屏软键盘：会话期兼容蓝牙硬键盘；「收起键盘」可点（不再落到空格）。

## 使用提示

1. 安装后请在 LSPosed 确认已启用 **System Framework** + **Android Auto**，并重启一次（system_server 侧键鼠钩子需重启）。
2. 无桌面图标：从 LSPosed → AADisplay 打开激活状态页。
3. 详细技术变更与真机核对项见 [CHANGELOG.md](../../CHANGELOG.md)。
