# 架构与接口

## 数据流

```text
按住 → 麦克风授权 → AudioCapturer（16kHz / mono / PCM16）
                         ├─ 私有 WAV 草稿 + RDB 元数据
                         └─ WSS → 百炼 Fun-ASR-Realtime
                                      ↓ 实时文本返回手机
                             HTTPS → Qwen JSON 信息提取
                                      ↓ 提案返回手机
                             金额原文核验、分类/日期校验
                                      ↓
                             手机确认页 → RDB → 汇总与图表
```

当前鸿蒙0.7.0和安卓0.7.0保留手机直连，用户填写个人API Key即可通过Wi-Fi或移动数据调用云端。手动记账、账单和预算完全离线；文字智能记账跳过录音与ASR。UI直接调用领域函数校验和汇总。

鸿蒙0.6.0新增`LedgerBrowse.ts`，统一处理近三天、指定日、月份、统计范围和全历史关键词搜索。每次查询集中计算匹配账单及完整日支出，UI按80笔分批渲染；全天合计不随分批或类型、关键词筛选变化。现有完整账本仍用于统计、推荐和备份，本轮未改变数据库结构。详见[日期与搜索](HARMONY-LEDGER-BROWSING.md)。

`DirectCloudClient.ets` 调用百炼北京地域的 HTTPS 文本接口，默认模型 `qwen-plus`；`DirectCloudVoice` 使用 WSS 接入 `fun-asr-realtime`。收到 `task-started` 后发送排队的 PCM，松手发送 `finish-task`，收到 `task-finished` 后再提取账单。`SpeechAccumulator` 按句子开始时间替换修订文本，避免重复拼接。模型输出经 `CloudParsing.ts` 独立核验，金额证据必须出现在原文且与提案金额一致。

鸿蒙0.7.0提案协议新增`source_text`与`title_text`，用逐笔原文位置核对名称和金额，并按来源排序；过宽的字面引用可收紧到本笔名称及金额，仍重叠时留空待核对。`CloudRecognition.ts`组织一次提取及最多一次异常复核，正常路径不增加调用。确认页展示逐笔原话与全局确认序号。片段只存在于未确认缓存，保存后沿用原事务裁剪，不进入账本和备份。安卓0.7.0已同步逐笔来源协议及复核逻辑；未接入的后端原型继续使用旧协议。详见[鸿蒙识别修复](HARMONY-0.7.0-RECOGNITION.md)及[安卓同步说明](ANDROID-0.7.0.md)。

客户端已经删除自建服务模式、服务地址、连接口令和旧的 `RecognitionClient.ets`。`backend/` 中的原型暂时保留，供未来会员后端开发参考，当前客户端不连接它。会员服务应由应用内建固定 HTTPS 接口管理，用户侧不再出现服务器配置。

## 个人密钥与联网

`CloudKeyStore.ets` 使用鸿蒙 AssetStoreKit，资产别名为 `yiyu.personal.dashscope.v1`；设置 `DEVICE_UNLOCKED` 仅设备解锁可读、`SyncType.NEVER` 禁止同步。界面通过属性查询判断是否配置，不回显已存的密钥；只有调用时读取密钥并放入 HTTPS/WSS 的 Authorization 请求头。安装包、账本 JSON/CSV 及日志不包含个人密钥，移除密钥不影响已保存账目。

设置只显示密钥状态和添加/更换入口。保存时先检验格式，再通过百炼 Qwen 验证可用性，成功后才覆盖系统资产；格式或网络错误保留原有密钥，填写页关闭时清空输入。原有资产别名不变，升级后无需重填。0.3.0已删除一次性调试配置导入代码与对应工具，并清理旧版服务连接首选项。

这种方式用于持有自己百炼账号的个人使用，调用费用由该账号承担。系统加密存储保护本地保存过程，调用时应用仍需在内存中读取密钥。以后面向公众提供统一识别服务时，应把运营方密钥放在服务端，以用户凭据及限流管理访问，不能把共享密钥内置客户端。

## 录音的生命周期

每条草稿使用随机编号，存入 `UIAbilityContext.filesDir`，文件路径不接受用户输入。录音流复制后异步排队写入 WAV，并更新头信息；停止时等待写入队列再关闭文件。录音上限30秒。上滑取消删除草稿；进入后台停止采集并保留有效草稿。

成功识别的结果先缓存在草稿元数据中，再删除本地 WAV、清空路径，确认恢复只读取缓存 JSON，不再次识别。每次确认时，“账目写入”“已确认数量增加”和“移除已确认提案及原始转写”在同一个 RDB 事务中完成。`savedCount` 保留全局确认序号，`resultOffset` 表示裁剪后缓存的起始序号，避免部分确认后恢复时跳过或重复账单。账目编号为 `audio_…_item_序号`，关闭确认页后可从首页“未完成记录”继续下一条；全部确认后清理元数据。

没有记账提案或没有有效转写时删除录音及元数据，不创建未完成记录。网络失败或后台中断时暂存 WAV 供重试，最多24小时，在下次启动、回到前台或刷新时清理。`RecordingPolicy.ts` 明确区分无记账内容、已全部确认、待确认提案和失败录音：录音过期不能删除仍未确认的账单；损坏提案元数据保留供用户明确处理。界面没有常驻草稿管理入口，只有实际存在未完成记录时才出现继续入口。

文本输入不保存音频；未提交文字保留在当前页面内存中，退出应用进程后不保留。手动录入通过原生表单校验。正式上架前可进一步增加文本编辑草稿和用户更正偏好。

## 本地数据

鸿蒙数据库`YiyuLedger.db`包含`entries(id,value)`、`audio_drafts(id,value)`、`categories(id,value)`和`monthly_budgets(id,value)`，value使用领域JSON。安卓使用Room，对应独立字段表，数据库v2通过增量迁移新增`monthly_budgets(month,amountMinor)`，不重建旧表。当前规模适合个人使用；数万笔后可增加索引、分页和数据库聚合。

账目字段定义在 `Ledger.ts`，日期使用 `YYYY-MM-DD`，金额以人民币分保存，不使用浮点数加减货币。0.4.0统一采用确认后实际删除：旧回收站记录在升级时清理，列表与统计重新从账本计算，已确认的识别副本和相关原始转写一并清理，独立的待确认账单保留。删除一笔账单时仅解除其他账单指向它的关联，不删除另一笔真实收支。数据库启用 `secure_delete`，删除后尝试截断 WAL。已导出到用户选择位置的文件独立保留。

自定义分类按支出、收入、转账、往来分组，退款使用支出分类。名称限制1–12个字符，同组不得重名；已有账单或待确认账单使用的分类不能移除。JSON备份为v3，保存有效账单、分类和月预算，兼容v1/v2。三类内容完整校验后在同一事务中合并，已有账单编号和同月显式预算不覆盖，分类含义冲突时拒绝导入。CSV显示自定义名称，并转义公式开头文本。

月预算以整数分和有效月份保存，向后沿用最近设置，0为关闭标记。`Budget`领域函数按自然月计算`max(0,支出−退款)`，返回剩余、超支、百分比与两层圆环比例；转账、往来、收入和删除记录排除。圆环第二圈红色覆盖已用进度，达到200%后保持红色；文字金额继续增长。两端使用同一份合成边界样例验证。

## 后端原型协议 v1（当前客户端未接入）

下列是仓库中保留的开发原型协议，供后续会员服务设计参考。当前0.4.0客户端不提供服务连接设置，也不发送这些请求。会员账号、服务端配额及支付验签尚未实现。

HTTP 使用 `Authorization: Bearer <手机连接口令>`；口令与云端 API Key 不同。WebSocket 的口令在第一条消息中传递，避免进入 URL/访问日志。服务设置了3个并发识别槽位、输入长度、大小和超时限制。个人阶段尚无按用户的小时调用配额。

| 接口 | 请求 | 返回 |
| --- | --- | --- |
| GET `/health` | 鉴权头 | configured、protocol_version、录音限制 |
| POST `/api/parse` | text、reference_date | transcript、drafts、issues |
| POST `/api/transcribe` | audio_base64（WAV）、reference_date | 相同的识别结果 |
| WS `/api/voice` | 见下方 | ready → partial → result / error |

WebSocket 首条：

```json
{"type":"start","token":"手机连接口令","reference_date":"2026-10-10"}
```

收到 `{"type":"ready"}` 后发送二进制 PCM 帧（每帧不超过65536字节，总计不超过960000字节）；结束发送 `{"type":"finish"}`。取消关闭连接或发送 `{"type":"cancel"}`。客户端在等待 ready 时缓冲音频，按序发送帧与结束指令。手机始终保留本地 WAV，直到所有提取账单确认完成。

成功返回示例（不是实际云端测试结果）：

```json
{
  "type":"result",
  "result":{
    "transcript":"中午吃了辣椒炒肉盖饭，花了15元",
    "drafts":[{
      "kind":"expense","title":"辣椒炒肉盖饭","amount_minor":1500,
      "category_id":"food","occurred_on":"2026-10-10","time_hint":"中午",
      "note":"","issues":[]
    }],
    "issues":[]
  }
}
```

金额缺失或不一致时 `amount_minor` 为null，确认页必须补填。模型生成的金额不能直接作为账本数据。手机直连与原型后端均按句子的开始时间合并最终句，等待 `task-finished` 才进入文本解析。上述后端v1示例保留原字段；鸿蒙0.7.0使用独立的逐笔来源协议。客户端中文金额、证据边界及句子合并有独立自动测试。

## 构建与适配

ArkTS/ArkUI Stage 工程，最低兼容和目标版本为 HarmonyOS 5.0.0 / API 12。采用系统音频、网络、ArkData、文件选择器和 Canvas；未依赖 Android 兼容环境或第三方跨端 UI。构建模型5.0.0已由 DevEco Studio 26.0.0.851 / Hvigor 6.26.8 / SDK 26.0.0.105 编译通过；编译 SDK 随 IDE 内置版本选择，实际兼容行为需真机确认。

鸿蒙0.5.0已通过ArkTS编译、签名及Pura80Pro（系统6.1.1、API24）覆盖安装。验证仅保留功能结果，不公开真机的个人账本内容。此前0.2.0由用户验证拔掉USB、关闭Wi-Fi后的移动数据语音识别，直连链路保持相同。

确认页分为固定标题、可滚动内容和固定保存区。分类默认显示选中项与最多两个推荐项，“更多”展开全部分类及自定义入口；推荐基于原文关键词和历史更正，不将候选项描述为模型置信度。自定义分类目录作为数据发送给Qwen，返回分类仍通过本地范围校验。

布局根据窗口可用宽度、系统字体与显示密度调整分类列数、推荐数量、边距和控件高度；通过窗口尺寸事件、前台更新和区域尺寸变化同步有效宽度。页面内容最大720vp，确认页最大600vp。键盘采用RESIZE避让，固定保存按钮保持可见；切换导航重置各页滚动位置。系统字体上限3.2，金额按长度缩放。已实测本机特大字体、大显示缩放、键盘和长金额，其他机型及折叠屏仍需实测，见 `ADAPTATION-AND-DELETION.md`。

录音按钮拦截触摸冒泡，并追踪同一根手指的开始、移动和释放。按住、准备录音及录音期间，外层 Scroll 禁用滚动交互，并在手势判定阶段拒绝滚动手势；松手/取消后恢复。真机取消前后按钮边界完全一致，后续普通滑动可正常滚动。

此前0.1.1的真实麦克风多笔识别、RDB保存及进程重启、周/月/年统计与分类明细已基础实测。语音准确率和异常场景仍需扩大验收，详见 `VALIDATION.md`。旧 USB 启动脚本仅供历史开发参考，当前客户端不使用。

账单列表的 ArkUI ForEach 使用账单编号与更新时间作为条目版本；日期组同时包含内部条目的版本，避免确认下一笔或编辑后复用旧的构建参数。图表使用 vp 字体，点按与拖动区分，滚动页面不会误触日期明细。

鸿蒙账单页改为单层原生List，ListItem右侧滑动按钮进入现有确认删除流程；安卓采用两锚点AnchoredDraggable，滑动本身不删除。窗口变化更新按钮宽度；竖向浏览、筛选和换页收起操作，保持账单点击编辑与手势浏览独立。

本机Windows资源编译器曾无法从PATH加载SDK图像依赖。`build-harmony.ps1`将官方restool及其所需DLL复制至项目`artifacts/harmony-resource-runtime`，只在本次构建替换资源工具路径，不修改已安装IDE/SDK；额外的预加载环境变量在结束后恢复。无密钥进入此目录或源码包。

## 官方参考

- [鸿蒙应用开发](https://developer.huawei.com/consumer/cn/app/planning)
- [AudioCapturer 录音指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-v5/using-audiocapturer-for-recording-V5)
- [WebSocket 连接指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/websocket-connection-V5)
- [ArkData 关系型数据库](https://developer.huawei.com/consumer/en/doc/harmonyos-references/arkts-apis-data-relationalstore)
- [文件选择器](https://developer.huawei.com/consumer/en/doc/harmonyos-references/js-apis-file-picker)
- [AssetStoreKit 安全资产存储](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-asset)
- [DevEco 安装要求](https://developer.huawei.com/consumer/en/doc/harmonyos-guides/ide-software-install)
- [Fun-ASR-Realtime WebSocket 协议](https://help.aliyun.com/zh/model-studio/fun-asr-realtime-websocket-api)
- [Qwen 结构化输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)
- [获取百炼 Key](https://help.aliyun.com/zh/model-studio/get-api-key)
