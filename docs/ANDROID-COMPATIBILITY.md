# 安卓 0.5.1 兼容测试

当前0.6.3的五行日期弹窗专项见[更新说明](ANDROID-0.6.3.md)，验证范围为Android16的默认字号常规屏、窄屏、横屏及旋转。

这是0.5.1的历史矩阵。0.6.2账单日期与搜索验证见[更新说明](ANDROID-0.6.2.md)及`artifacts/android-0.6.2-browse-ui/api*/results.json`：Android15/16默认字号标准屏、窄屏、横屏和Android17标准屏通过。不能把本页旧APK的结果视为新版全部场景已通过。

日期：2026-10-11。安卓版本 0.5.1 / code 6，最低 API 26、编译及目标 API 37。配套鸿蒙界面更新为 0.4.1。

## 当前状态

当前 APK 为 0.5.1，SHA-256 为 `7fef44d092b8356383294554280a335e393672f7b4118401558d2fe8b9ec8356`。本次最终修正后，Android 16 的十项基础测试，以及窄屏、200% 大字体、宽屏三个场景均通过。可先作为朋友个人试用包；其余场景及 Android 15 最新构建的复测尚未完成。结果文件中的 `passed` 只代表该文件所记录 APK 的对应场景通过。

此前构建 `c65b70dd…` 的 Android 15 / 16 九个流程均通过；截图复查发现大字体统计金额及窄屏首页结余可能截断，已修正，并增加实际数字位置检查。此前结果不能作为当前 APK 的完整验收结果。

## 场景与检查方法

| 场景 | 竖屏基准尺寸 dp | 系统字体 | 方向与附加条件 |
| --- | --- | --- | --- |
| standard | 393 × 873 | 100% | 常规竖屏、手势导航 |
| narrow | 320 × 720 | 100% | 窄屏、手势导航 |
| wide | 440 × 956 | 100% | 宽屏、手势导航 |
| large_font | 360 × 800 | 200% | 大字体 |
| display_zoom | 320 × 640 | 130% | 较少可用空间，模拟显示放大 |
| landscape | 393 × 873 | 100% | 实际旋转为 873 × 393 |
| landscape_font | 393 × 873 | 150% | 横屏与模拟挖孔 |
| three_button_cutout | 393 × 873 | 130% | 三键导航与模拟挖孔 |
| dark_mode | 393 × 873 | 130% | 系统深色模式，应用保持浅色主题 |

每个场景都操作真实 Compose 界面和 Room 数据库，检查首页、确认页、自定义分类、账单、周/月/年统计与图表，并完成保存、编辑和删除。使用合成账单：9 元米线、321 元收入、极大金额、长名称及 12 字自定义分类；不使用用户账本或真实 API Key。

键盘检查核对保存按钮至少 48dp 的点击高度、位置与键盘可见内容的边界，并检查名称输入区仍有可读高度。金额检查使用系统无障碍接口返回的实际字符位置，核对首页结余、统计收支每个数字的横向范围。每个场景记录 10 张 PNG 和 10 份界面层级；脚本校验所有 PNG 的实际宽高，测试也校验真实显示方向。

结果记录安装 APK 的 SHA-256 和测试时间。APK 更换后不会沿用旧结果；重测时清理该场景旧截图，避免失败后误用旧画面。测试脚本拒绝物理手机，只允许指定的 `Yiyu_API35` / `Yiyu_API36` 开发模拟器。结束后恢复字体、屏幕尺寸、旋转、导航栏、挖孔及深色模式设置。

## 本次修正

- 首页和账单金额按实际空间自动缩放；大金额、窄屏或大字体时，账单名称和金额上下排列。
- 首页收支结余及统计收支卡片在金额过长时改为上下排列，避免两列宽度不足导致数字截断。
- 推荐分类按可用宽度及字体缩放减少数量，日期在必要时改为两行。
- 正文与保存栏分开，确认页避让键盘和系统边缘；极低高度时收起标题、移动关闭按钮，保留输入空间。
- 修正确认弹窗的输入焦点管理，展开分类时能正确清除表单焦点。
- 统计周期按钮均分宽度，图例可换行，横轴根据标签宽度减少显示数量并保留端点。
- 两端从同一份配色生成原生常量，安卓补齐 Material 弹窗、提示条、状态栏和图表颜色。见 [配色说明](UI-THEME.md)。

## 构建与基础检查

安卓最终 `tools/build-android.ps1 -Mode Check` 成功，11 项单元测试通过；Lint 为 0 errors / 4 warnings，警告均为依赖更新提示。当前 APK 的 Android 16 十项设备基础测试通过，覆盖 Room 事务与文件数据库、系统加密密钥、手动保存删除和录音取消手势；Android 15 的十项基础结果来自此前构建，最新修正尚需复测。

APK 签名验证、16KB ZIP 对齐及四个 ABI 的 ELF 加载段 16KB 静态对齐检查通过；本次没有验证 16KB 内存页的实际设备。0.5.1 与已安装的 0.5.0 使用同一签名，可覆盖升级。

鸿蒙原生编译和签名成功，40 项领域及存储测试通过。鸿蒙 0.4.1 新版配色尚未安装到真机进行观感验证。

## 本机环境与复测

SDK、系统镜像、AVD、Gradle 缓存和下载均位于 D 盘。Android 15 使用 Google APIs x86_64 API35 rev9，Android 16 使用 API36 rev7；SDK 管理的 API37 模拟器保持原样。

本机 API35 / 36 使用官方独立 Emulator 36.6.11，位于 `D:\Android\Tools\emulator-36.6.11`。下载脚本验证官方 SHA-256，再将其自身 DLL 放到独立 QEMU 可执行文件旁；使用软件图形路径与关闭 Vulkan 的无窗口进程，解决本机运行库加载及图形驱动问题。没有修改 SDK 原版模拟器文件。

```powershell
# 一个终端启动模拟器；另一终端运行安装和测试。
.\tools\start-android-emulator.ps1 -Api 35 -Foreground
.\tools\build-android.ps1 -Mode ConnectedTest -ReportName android15-baseline-tests.log
python tools\run-android-compatibility.py --serial emulator-5554 --api 35

# 先退出前一个模拟器，再使用 API36，避免占用过多内存。
.\tools\start-android-emulator.ps1 -Api 36 -Foreground
.\tools\build-android.ps1 -Mode ConnectedTest -ReportName android16-baseline-tests.log
python tools\run-android-compatibility.py --serial emulator-5554 --api 36
```

独立模拟器及镜像安装脚本为 `bootstrap-android-compatibility-emulator.py` 与 `bootstrap-android-emulator-image.py --api 35/36`。当前 DevEco 的原生 DLL 依赖采用 D 盘的独立构建 SDK 处理，不修改原安装目录；本机鸿蒙构建命令：

```powershell
.\tools\build-harmony.ps1 -SdkPath 'D:\Android\Tools\DevecoSdkCompatible'
```

## 验证边界

这是 Google Android 模拟器的系统与窗口适配测试，不能替代不同小米澎湃 ROM 的真机结果。小米 14 / Android17 / 澎湃4 的首版 0.5.0 已由用户验证断开 USB 后识别及保存成功；0.5.1 尚未覆盖安装到该手机。其他小米型号、真实折叠展开、分屏、厂商麦克风权限与后台中断仍需真机回归；Android 8–14 尚未完成设备测试。

原始结果在 `artifacts/android-compatibility/api35/` 与 `api36/`。测试只修改开发模拟器中的合成数据，未改动用户手机账本和密钥。
