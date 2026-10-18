# 安卓 0.6.3：五行日期滚轮

日期：2026-10-15。版本号 0.6.3 / code10。

账单日期弹窗采用五行预览，常规屏每行44dp。中间日期用森林绿文字和浅绿色底突出，首尾文字向边缘渐隐；标题、关闭入口及底部按钮保持居中、对齐。横屏根据可用高度收紧行高和留白，确认与取消按钮保持48dp高度。

年、月、日支持滑动后自动停靠和点击相邻日期。切换短月、闰年时自动校正日期；窗口变化后按已选日期恢复位置，布局重新测量不会改变用户选择。重新打开、取消和旋转后，日期与星期标题保持一致。

## 验证

23项Kotlin单元测试通过，Lint无错误。Android16 / API36开发模拟器使用默认字号，完成以下专项：

| 屏幕 | 结果 |
| --- | --- |
| 393×873dp常规屏 | 通过，含旋转与重新打开 |
| 320×720dp窄屏 | 通过 |
| 873×393dp横屏 | 通过 |

三组配置共5项界面流程、35张截图及对应布局，核对五行实际绘制间距、上下渐隐、居中、按钮可见性、滑动与点击选择、2月及闰年校正、取消和查询结果。日期与星期居中检查使用截图像素，避免Android重新测量后缓存的无障碍条目边界影响判断。

结果见`artifacts/android-0.6.3-browse-ui/api36/results.json`，每组记录同一实际安装APK摘要。Android15/17的历史基线见[0.6.2记录](ANDROID-0.6.2.md)。本轮只在开发模拟器操作合成账单。

复现：启动API36开发模拟器后，先运行`tools/build-android.ps1 -Mode Check`及Gradle任务`:app:assembleDebugAndroidTest`，再运行`python tools/run-ledger-browse-ui-check.py --api 36`。

## 安装

安装包：`artifacts/YiyuLedger-0.6.3-android-debug.apk`，20,208,404字节。

SHA-256：`663affc689daa79de261a78200b290e3faec156aecd39bf8052c9bba9e37412c`。

沿用原应用ID、数据格式及签名，直接覆盖安装即可保留账本、预算和加密密钥；不要先卸载。APK签名、ZIP完整性和16KB ZIP对齐检查通过。交付清单见`artifacts/release-android-0.6.3.json`，效果预览为`artifacts/android-0.6.3-date-picker.png`。
