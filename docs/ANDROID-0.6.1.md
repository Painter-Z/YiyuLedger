# 安卓 0.6.1：对齐鸿蒙 0.5.2

日期：2026-10-12。安卓版本 0.6.1 / code 8；参考鸿蒙 0.5.2 / code 1000008。

## 修改

- 左滑展开直角长方形删除按钮，按钮与账单之间留出 16dp 空白。字号放大时按钮加宽，滑动本身不会删除，点击后仍需确认。一次只展开一笔，点账单、竖向浏览或切换筛选时收起。
- 月预算圆环在标准 208dp 尺寸下采用 22dp 线宽；底环、已消费弧和超支弧一致。中心文字按实际内径调整，长金额完整显示，大字体下不会与圆环重叠。
- 统计卡片恢复“净支出 / 收入”左右双列，与鸿蒙口径一致；长金额在列内自动缩小。下方显示收支结余、原始支出及退款。
- 页面采用 `#F5F5EF` 暖灰，卡片 `#FEFEFA` 暖白，筛选与输入区域 `#E9EEE0` 浅绿。统计和预算卡片颜色相同；主题生成脚本保留两端各自配置。
- 预算弹窗在自身窗口中清除输入焦点，保存按钮继续固定并避让键盘。

预算计算、按月继承、关闭额度、删除联动、整数分及 v3 备份格式沿用原规则。鸿蒙 0.5.2 源码及安装包未改动。

## 验证

15 项 Kotlin 单元测试、48 项共享规则测试通过；Lint 为 0 errors / 4 warnings，警告均为固定依赖的更新提示。Android 15、16 各十三项基础设备测试通过。

专项界面测试全部通过，共六种配置、十二次完整界面测试流程，保存六十张截图及对应布局。每组覆盖未设置、剩余额度、恰好用完、150% 超支、300% 超支、长金额、左滑按钮、删除后刷新及键盘显示；核对双列金额及圆环文字的实际字符位置，并从截图检查16dp留白、直角按钮、22dp圆环线宽与卡片色值。

| 系统 | 393×873dp / 100% | 320×720dp / 100% | 360×800dp / 200% |
| --- | --- | --- | --- |
| Android 15 / API35 | 通过 | 通过 | 通过 |
| Android 16 / API36 | 通过 | 通过 | 通过 |

机器记录为 `artifacts/android-0.6.1-ui/api35/results.json` 与 `api36/results.json`；全部结果记录同一安装包的 SHA-256。基础设备测试日志为 `artifacts/android-0.6.1-api35-baseline.log` 与 `api36-baseline.log`。APK签名、ZIP完整性、16KB ZIP及原生库对齐检查通过；已核对0.6.0与0.6.1的签名证书一致。

所有合成账单只写入 `Yiyu_API35` / `Yiyu_API36` 开发模拟器，没有修改用户手机账本或密钥，也没有发起云端识别请求。

专项脚本为 `tools/run-budget-ui-check.py`，依赖 Python 的 Pillow。每个系统检查 393×873dp 标准屏、320×720dp 窄屏和 360×800dp / 200% 字体；每个配置执行两项完整界面流程并保存十张截图及对应布局。脚本核对实际安装的 APK 摘要，并恢复测试前的屏幕、字号与旋转设置。定向重跑仅合并相同 API、相同 APK 摘要的已有结果。

在对应模拟器启动后复现：

```powershell
.\tools\build-android.ps1 -Mode ConnectedTest -TestClass 'com.yiyu.ledger.KeyStoreTest,com.yiyu.ledger.LedgerUiTest,com.yiyu.ledger.RepositoryTest,com.yiyu.ledger.VoiceGestureTest' -ReportName android-0.6.1-api36-baseline.log
python tools\run-budget-ui-check.py --api 36
```

Android 15 使用 `--api 35`，基础测试日志使用对应的 `api35` 文件名。只启动一个模拟器，避免同时运行占用内存影响界面测试。

## 更新

安装包为 `artifacts/YiyuLedger-0.6.1-android-debug.apk`。与 0.6.0 使用同一应用 ID 和调试签名，直接覆盖安装，保留账本、预算和加密密钥；不要先卸载。

安装包20,104,739字节，SHA-256：`fe95c308b9dcb4e6c8142c8a22f0703fe50a84539d8c38538ec6132a0ae4775d`。交付清单见 `artifacts/release-android-0.6.1.json`。

本轮模拟器测试不替代小米澎湃不同机型的真机结果。最初交付时尚未安装到用户或朋友的手机；2026-10-12 用户确认一位小米 14 朋友已更新至 0.6.1，其金额输入键盘仍感觉偏慢，其他安卓用户未反馈此现象。专项对照与未确认事项见 [键盘排查记录](ANDROID-KEYBOARD-INVESTIGATION.md)。
