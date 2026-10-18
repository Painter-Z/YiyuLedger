# 一语账安卓个人测试版

工程：`D:\AI_Project\YiyuLedger\android`。当前版本号0.7.0 / code11。最低 Android 8.0 / API26，编译及目标 Android17 / API37。小米澎湃4作为优先真机验收对象；安装包为标准APK，不依赖Google Play服务。0.7.0同步鸿蒙的口语金额、多笔原话核验与确认进度，见[本次更新](ANDROID-0.7.0.md)。原有账单查询与五行日期滚轮继续保留，见[账单浏览](ANDROID-0.6.2.md)及[日期滚轮](ANDROID-0.6.3.md)。

## 开发环境

| 项目 | 本机位置 / 版本 |
| --- | --- |
| Android Studio | `D:\Android-studio`，配套 JBR 25.0.3 |
| Android SDK | `D:\Android\Sdk` |
| SDK Platform | Android 15 / API 35、Android 16 / API 36、`platforms;android-37.0` Android 17 / API 37 revision 2 |
| Build-Tools | `37.0.0` |
| Platform-Tools / ADB | `37.0.1`，`D:\Android\Sdk\platform-tools\adb.exe` |
| 命令行工具 | `D:\Android\Sdk\cmdline-tools\latest` |
| Gradle | Wrapper 9.6.0，官方 SHA-256 固定；缓存 `D:\Android\Gradle` |
| Android 用户文件 / AVD | `D:\Android\User` / `D:\Android\Avd` |
| 下载和编译临时目录 | `D:\Android\Downloads` / `D:\Android\Temp` |

Android 用户环境变量已设置为 D 盘。已启动的 Studio 可能需要重新打开后才能读取新环境变量；工程 `local.properties` 已指向 D 盘 SDK。本机全局 `JAVA_HOME` 和用户 `TEMP` 未改动，构建脚本仅为自己的进程设置 JDK 和临时目录。

用 Android Studio 的 Open 打开 `D:\AI_Project\YiyuLedger\android`，等待 Gradle 同步。命令行在项目根目录执行：

```powershell
.\tools\build-android.ps1             # 生成个人调试 APK
.\tools\build-android.ps1 -Mode Check # 单元测试、Lint、APK
.\tools\check-android-environment.ps1
```

已锁定 AGP 9.4.0、内置 Kotlin / Compose 编译插件 2.2.10、KSP 2.3.12、Compose BOM 2026.09.00、Room 2.8.5、Lifecycle 2.11.0、Activity 1.13.0。协程 1.10.2、序列化 1.9.0、OkHttp 4.12.0 选择已验证的固定版本，Lint 的较新版本提示不表示构建失败；升级需重新验证整套工具链。

## 安装与日常使用

构建脚本将APK复制为`artifacts\YiyuLedger-0.7.0-android-debug.apk`。这是个人调试签名包，可在符合最低版本的安卓手机上测试；上架需使用长期维护的正式签名及发布配置。更新时直接覆盖安装同签名的新版本，保留原账本、预算和加密密钥；不要先卸载。

将 APK 传到小米手机，通过文件管理器打开并按系统提示允许对应来源安装。首次打开为空账本，不导入任何演示或开发者个人账单。日常使用不需要电脑或 USB。

1. 在“设置 → 个人 API Key”填写自己的百炼北京地域密钥，应用验证后加密保存。不回显密钥，不写入备份。
2. 首页按住语音键说出收支；首次麦克风授权完成后重新按住。松开识别，上滑松手取消，单次最多30秒。
3. 核对金额、名称、类型、分类及日期，点击底部“确认保存”。一次多笔时显示当前原话与进度，点击“保存，确认下一笔”继续。
4. 分类默认展示当前选择和备选，点“更多”展开全部分类及“自定义”。手动、文字记账入口位于语音键下方。
5. 查看周、月、年收支趋势和分类分析，点图表或分类可进入相关账单。退款单列，转账和往来本金不计普通收支。
6. 账单支持左滑展开红色删除按钮。统计页可设置月预算，圆环中心显示剩余或超支金额；设置后后续月份自动沿用。
7. 账单默认展示近三天，日历可查指定日期。未指定日期时关键词搜索全部历史；查询结果末尾点击“返回”恢复近三天并保留收支类型。

语音和智能文字识别会发送当次内容及自定义分类至百炼，用量计入个人账户；手动记账、保存、查询和统计可离线使用。API Key 的格式或权限不正确时不覆盖原密钥。

## 与鸿蒙版迁移

在原设备“设置 → 导出完整备份”选择JSON，在另一台设备导入该文件。安卓0.6.1和鸿蒙0.5.2使用`yiyu-ledger` v3，保留账单ID、金额分、日期、类型、自定义分类和月预算，继续读取v1/v2。相同账单ID跳过，本机同月预算优先；分类冲突或非法内容拒绝整个导入，原账本保留。CSV用于查看，不作为完整恢复格式。

录音、未确认提案和 API Key 不迁移。Android 关闭系统自动云备份及设备搬迁的数据提取，避免系统恢复旧账单或识别缓存；请主动导出 JSON 备份。用户已导出的文件由用户管理，删除应用内账单不会修改这些文件。

## 验证边界

自动检查包含跨端合成样例、真实 Room 事务、删除及关联清理、重复确认保护、导入冲突、失败录音清理、系统密钥加密、手动保存删除及上滑手势。可选云端探针只在开发模拟器使用合成语音，不保存账单，也不将密钥编译入 APK。

已核对真机：Xiaomi 14 / 23127PN0CC、Android 17 / API 37、澎湃 `OS4.0.0.14.XNCCNXM`、安全补丁2026-08-01。0.5.0已安装并正常启动，用户确认拔掉USB后识别成功，点击保存后在账单页能看到记录。此机USB安装即使开启开关仍返回 `INSTALL_FAILED_USER_RESTRICTED`；APK签名校验通过，改由用户在手机文件管理器打开下载目录的APK安装成功。

Android 15 / 16 的界面场景、日志和截图见 [兼容测试](ANDROID-COMPATIBILITY.md)。小米实机尚需验证：麦克风音质与方言、Wi-Fi 和移动数据、权限拒绝、锁屏 / 来电 / 后台打断、横屏 / 大字体、文件导出恢复。模拟器通过不能替代这些真机结果。首版真机结果见 [安卓验证记录](ANDROID-VALIDATION.md)。

## 官方依据

- [Android 17 SDK 配置](https://developer.android.com/about/versions/17/setup-sdk)
- [AGP 9.4.0 与 Gradle / JDK 兼容关系](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [AGP 内置 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [KSP 官方发布](https://github.com/google/ksp/releases)
- [百炼 API Key](https://help.aliyun.com/zh/model-studio/get-api-key)
