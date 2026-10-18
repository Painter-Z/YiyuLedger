# 一语账：小米澎湃与安卓移植准备

调研日期：2026-10-11。基线：已安装的原生鸿蒙0.4.0。方案已开始落地，Android原生0.5.0已成功编译APK；实际环境和使用步骤见 [安卓说明](ANDROID-QUICKSTART.md)。下文保留方案依据，验收结果以对应验证记录为准。

## 1. 系统判断

小米、REDMI手机上的澎湃OS继续支持Android应用生态，本项目应开发标准Android APK。小米官方仍在发布Android适配指南，并明确提供基于Android的澎湃开发者预览系统；当前官方系统页展示HyperOS 4。不能把澎湃品牌覆盖的所有设备类型都当作同一种手机运行环境，也不能仅凭“澎湃4”推断某一部手机的Android/API版本。

用户提供澎湃完整版本`4.0.0.14.XNCCNXM.D00`。本次通过ADB只读查询确认设备为 **Xiaomi 14 / 23127PN0CC（houji）**，Android **17 / API 37**，系统属性版本 `OS4.0.0.14.XNCCNXM`，Android安全补丁 **2026-08-01**；该日期是安全补丁日期。实际屏幕1200×2670、density 480。安卓0.5.0已通过手机文件管理器安装并正常启动，未刷机或解锁Bootloader。

依据：[小米Android适配指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2297)、[小米应用创建指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1072)、[HyperOS 4官网](https://os.mi.com/)。部分适配文档保留开发者预览时期的描述，不能拿文档中的旧推送日期推断用户当前系统状态。

## 2. 技术路线

建议新增独立的原生Android工程，采用Kotlin、Jetpack Compose、Room/SQLite、协程与Flow。沿用一语账的米白/墨绿视觉、语音优先流程和已有账本规则；先在小米手机验收，再覆盖其他安卓品牌。

现有ArkTS代码不能直接编译为Android应用，也不能将HAP改后缀变成APK。可复用的是产品设计、图标资产、数据协议、提示词、语料和测试预期；领域逻辑要移植为Kotlin，并用两端共用的合成测试数据核对结果。维护成本是两套原生客户端；本阶段已有鸿蒙原生基础，改用另一套跨端框架不能直接复用现成ArkUI界面，不建议同时重建两端。

| 现有能力 | Android实现方向 | 必须保持的行为 |
| --- | --- | --- |
| ArkUI页面 | Compose＋自定义主题 | 分类折叠、备选、自定义、底部固定保存区 |
| AudioCapturer | AudioRecord | 单声道16kHz PCM16、最长30秒、上滑取消 |
| 鸿蒙HTTP/WSS | Android网络客户端，拟用OkHttp | 百炼手机直连、文本提取、实时语音及失败重试 |
| ArkData RDB | Room/SQLite＋Repository | 原子保存、实际删除、统计随数据库变化 |
| AssetStoreKit | Android Keystore保护加密密钥，AES-GCM加密API Key | 密钥不回显、不进导出文件、不内置共享Key |
| 文件选择器 | Storage Access Framework | 用户选择位置导出CSV/JSON、选择文件恢复 |
| Canvas统计 | Compose Canvas及可访问的文本明细 | 周/月/年、退款口径、点按明细 |

Compose、Room和AudioRecord均有Android官方接口与指南：[Compose](https://developer.android.com/develop/ui/compose/documentation)、[Room](https://developer.android.com/training/data-storage/room)、[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)。OkHttp的具体稳定版本在建工程时核对官方发布并锁定。

## 3. 环境与项目准备

用户后来安装了Android Studio，已确认位置 `D:\Android-studio`。Android 17 SDK、命令行工具、Build-Tools和ADB已安装到 `D:\Android\Sdk`；新增独立 `android/` 工程，缓存和AVD也配置在D盘。此前只读盘点的“未检测到”已被这次实际安装和构建结果替代。

开发环境按下面顺序准备：

1. 安装[Android Studio稳定版](https://developer.android.com/studio)，优先使用其配套JDK。
2. SDK Manager准备Android SDK Platform、Platform-Tools、Build-Tools和Command-line Tools。按用户提供的Android 17和官方SDK资料，计划`compileSdk=37`；`targetSdk=37`作为新工程目标，需要实际完成对应行为变更验收。这不代表所有澎湃4设备都等于Android 17，也不代表某商店一定要求target37。
3. `minSdk=26`（Android 8.0）作为拟定兼容起点，待确认目标朋友设备后固定。最低支持版本与编译/目标版本分开管理。
4. 使用工程Gradle Wrapper，不要求用户安装全局Gradle。Android Gradle Plugin、Gradle、Kotlin和Compose依赖在建工程时选择相互兼容的稳定组合并锁定，不使用动态版本。
5. 模拟器用于基础页面、尺寸和数据库验证；录音、锁屏/来电、小米权限及移动数据必须用真机验证。
6. 首次真机安装调试可使用USB；完成安装后，日常语音记账只需手机联网，电脑服务和USB均不参与识别。

[Android 17 SDK官方配置](https://developer.android.com/about/versions/17/setup-sdk)给出API 37。已按用户最新授权完成SDK下载并开始安卓开发，未修改原鸿蒙手机账本。

已添加只读检查脚本：

```powershell
.\tools\check-android-environment.ps1
# 自定义安装位置：
.\tools\check-android-environment.ps1 -StudioPath 'D:\AndroidStudio' -SdkPath 'D:\Android\Sdk'
```

脚本只报告路径、已安装平台和工具是否存在，不读取API Key、不启动ADB、不操作手机。路径检测通过也不能代替实际Gradle构建。

实际目录如下：

```text
android/                      Android Studio工程与Gradle Wrapper
  app/src/main/.../
    domain/                   金额、日期、分类、统计、结果核验
    data/                     Room、Repository、事务与迁移
    audio/                    AudioRecord、WAV、采集生命周期
    cloud/                    百炼HTTPS/WSS、识别会话
    security/                 Keystore与个人Key存储
    backup/                   JSON v1/v2与CSV、系统文件选择器
    ui/                       Compose页面、主题、图表
shared/contracts/             两端协议及分类ID定义
shared/fixtures/              无个人数据、无密钥的输入和预期结果
```

源码打包工具已包含`android/`与`shared/`，忽略Gradle缓存、APK/AAB和签名材料。

## 4. 先固定两端共同规则

账单以鸿蒙`Ledger.ts`为基线：ID、kind、amountMinor、currency、categoryId、title、occurredOn、timeHint、note、source、groupId、linkedEntryId、createdAt、updatedAt及兼容字段deletedAt。金额使用整数人民币分；Kotlin使用Long并保留相同合法范围1–999999999999分，不通过Double加减金额。日期保留`YYYY-MM-DD`及原始日期语义，禁止在迁移时转换成另一时区的日期。

必须固定以下规则后再接UI：

- 支出、收入、退款、转账、往来类型一致。退款计入退款发生日，转账和借还本金不计普通收支；周从星期一开始。
- 默认分类ID不变。自定义分类ID、名称、类型及校验一致，退款使用支出分类；推荐不能擅自覆盖用户选择。
- JSON保持`format: yiyu-ledger`、v2当前有效账单＋自定义分类，兼容v1且忽略其已删除记录。相同账单ID不覆盖，分类ID含义冲突拒绝整个导入。
- 导入分类与账单同一事务提交；损坏文件、超限文件或字段错误不改原账本。保持现有5MB/2万笔限制及CSV公式转义策略。
- 双向迁移验收：鸿蒙导出→Android导入→Android导出→鸿蒙导入，逐字段比较；相同ID不重复、金额和统计不变。API Key、录音和未完成提案不迁移。
- 共用测试数据覆盖0.01元、中文金额、同金额不同账单、口误、无金额、无关内容、跨月退款、跨年周、自定义分类、重复导入及删除。

现有40项测试是迁移依据，不等于已经验证Android。新增Kotlin测试与两端共用样例应分别执行，不能用鸿蒙测试通过替代安卓验收。

## 5. 语音与保存状态

业务流程保持：按住→授权/连接→PCM采集和WSS→最终转写→Qwen提取→本地金额/分类/日期核验→确认→事务保存。继续调用已有百炼模型，无需训练新模型。

网络层用统一识别接口封装文字提取和实时会话，首版由个人Key直连实现，未来会员后端可替换提供方。当前鸿蒙已真机验证北京域名接口；此次查阅[百炼实时WebSocket文档](https://help.aliyun.com/zh/model-studio/fun-asr-realtime-websocket-api)发现官方推荐业务空间专属域名。安卓接入前需验证当前账号与现有接口兼容性、域名迁移要求及仅填写Key的使用流程，不凭密钥字符串猜测Workspace ID，也不把服务地址配置交给普通用户。

用明确的识别会话状态管理授权、连接、录音、结束、解析、确认、取消和错误。每个会话绑定编号，取消后忽略迟到的网络结果；首次权限弹窗期间松手不能在授权后自动开始录音。录音手势由按钮消费，按住期间锁定外层页面滚动，取消后恢复。

前台短时录音为首版边界：切后台、锁屏或被系统打断时停止并释放麦克风，保存可重试状态。界面需显示识别中、失败及重试，不能为了保活让用户常规开启自启动或关闭省电限制。后台持续录音若以后确有需要，另行设计对应服务与权限。

无记账内容或主动取消就清理音频及元数据；识别成功后释放音频，只保留未确认提案。失败录音最多暂存24小时，在启动/回前台清理。保存单笔与确认进度、缓存裁剪同一事务完成，保留稳定序号，避免进程重启或重复回调产生重复账单。

## 6. 统一删除与备份边界

Room中的有效账单作为唯一账本来源。列表、搜索、图表和金额汇总通过Repository/Flow读取同一份数据；UI不另存一份历史账本，统计不维护未经校验的独立余额。

确认删除后，在事务中删除账单、清理相关已确认提案及转写副本，解除其他真实账单指向它的关联，保留独立的未确认提案；发布数据变化后所有页面更新。清空对应编辑状态，防止返回旧页面再次保存刚删除的内容。新导出仅含当前账单，用户以前明确导出的独立文件保留。

另外，Android系统可能自动备份应用数据。为符合当前本地账本与明确导出规则，首版计划关闭自动备份，并为云备份、设备迁移分别排除账本、识别缓存和密钥文件。Android官方说明部分厂商的设备迁移不单受`allowBackup=false`控制，因此不能只设这一个标记；需配置并验收`dataExtractionRules`，旧版本配套使用`fullBackupContent`。依据：[Android备份行为变更](https://developer.android.com/about/versions/12/behavior-changes-12)。

个人Key由Keystore保护的加密密钥加密后保存，解密仅在调用时发生；不能声称百炼Key本身永远不进应用内存。密钥更换需验证成功后才覆盖，失败保留旧值。卸载后重新安装需要重新填写个人Key；朋友使用自己的Key。依据：[Keystore](https://developer.android.com/privacy-and-security/keystore)。

## 7. 小米真机适配重点

| 场景 | 验收要求 |
| --- | --- |
| 麦克风权限/系统全局开关 | 首次拒绝、仅本次允许、系统关闭录音权限时均有明确反馈，手动记账可用 |
| 按住/上滑取消/系统返回 | 页面不被录音手势拖动，释放后停止采集；取消不生成账单 |
| 来电/锁屏/切后台 | 停止麦克风、无悬挂连接；可恢复的内容明确呈现，无静默重复保存 |
| Wi-Fi/移动网络与切换 | 不依赖电脑；弱网/断网显示失败或重试；迟到结果不覆盖新会话 |
| 大字体、显示缩放、刘海与手势区 | 内容可滚动、分类候选减少、长金额可读、保存按钮避让键盘和系统栏 |
| 小米分屏/小窗/旋转 | 按实际窗口尺寸排版，避免用手机型号判断宽度；不重复启动识别任务 |
| 数据恢复与删除 | 删除后列表/统计/缓存同步；重启不复活；备份双向导入不重复、不改金额 |
| 系统文件选择器 | CSV/JSON实际保存成功、取消不误报；导出文件保留且不含Key和录音 |
| 安装与覆盖升级 | 相同包名与签名升级，账本和Key保留；不同签名明确区分，避免要求卸载丢数据 |

布局依据：[窗口与键盘Insets](https://developer.android.com/develop/ui/compose/system/insets)。文件导出采用[系统文档选择器](https://developer.android.com/training/data-storage/shared/documents-files)，不索取全部文件访问权限。首版计划仅申请联网及使用时录音所需权限，不接入广告、支付或后台常驻能力。

## 8. 开发顺序与交付门槛

1. 环境与契约：SDK/工具可用、工程能构建；两端数据定义和合成样例固定。
2. 离线闭环：手动保存/编辑/删除、分类、周/月/年统计、CSV/JSON与双向迁移通过。先证明账本可靠，再接语音。
3. 手机直连：Key加密保存、文字提取、实时录音、金额证据、多笔确认、取消/失败恢复通过；拔掉USB验证移动数据识别。
4. 小米适配：完成上表，保留机型/系统/版本与结果。当前用户的小米先验收，再选择另一台安卓和窄屏/大字体组合。
5. 朋友内测：形成固定签名的测试APK和简明说明，验证覆盖升级。APK分发不使用鸿蒙调试Profile；开发调试签名与后续正式发布签名分开规划，不承诺免除系统安装授权或商店审核。

安卓包名拟沿用`com.yiyu.ledger`，正式建立前检查商店占用；首个安卓内测版本号独立递增，并标明功能对齐鸿蒙0.4.0。发布前确定签名管理和备份，不能临时换签名导致朋友必须卸载。依据：[Android应用签名](https://developer.android.com/studio/publish/app-signing)。

首轮达到可用的标准是：小米独立语音生成正确待确认账单；确认只保存一次；取消/无关语音不保存；删除与统计一致；重启后有效数据保留；鸿蒙与安卓JSON可双向恢复。会员、云同步、桌面快捷记账及小米特色系统能力在这个闭环稳定后再逐项增加。
