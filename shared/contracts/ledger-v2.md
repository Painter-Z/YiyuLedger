# 一语账跨端账本协议 v2

此为兼容保留的历史版本。鸿蒙0.5.0 / 安卓0.6.0开始输出[v3备份](ledger-v3.md)，继续读取v1/v2。

Android 与 Harmony 使用相同分类 ID、整数人民币分、日期及 JSON 备份字段。备份 `format` 为 `yiyu-ledger`，`version` 为 2；v1 可读。仅输出 `deletedAt=0` 的有效账单，包含所有自定义分类定义，不含密钥、录音或待确认提案。字段及金额校验见两端 `Ledger` 模型。

同账单 ID 合并时跳过，保留本机版本；分类 ID 的名称或类型冲突、同类型分类重名及任一非法字段会拒绝整个导入。退款按发生日期计入退款，并使用支出分类。普通收支不包含转账和往来本金，周从星期一开始。

`shared/fixtures/ledger-golden.json` 由 `node tools/generate-shared-fixtures.mjs` 调用鸿蒙领域代码生成，全部使用合成数据。Kotlin 测试逐项比较金额、日期、统计、语音本地核验、CSV 和备份；不包含个人账单。
