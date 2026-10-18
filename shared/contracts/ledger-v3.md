# 一语账跨端账本协议 v3

沿用[v2账单及分类字段](ledger-v2.md)，完整JSON备份输出`format: "yiyu-ledger"`、`version: 3`，新增必填数组`monthlyBudgets`。v1/v2仍可读，对应预算为空；v3旧版客户端不能读取。

```json
{"format":"yiyu-ledger","version":3,"exportedAt":"2026-10-12T00:00:00.000Z","entries":[],"categories":[],"monthlyBudgets":[{"month":"2026-10","amountMinor":200000},{"month":"2026-12","amountMinor":0}]}
```

`month`为1900–2199年有效的`YYYY-MM`；`amountMinor`为0–999999999999之间的整数分，不能使用字符串或浮点数。数组最多3600项，每月只能出现一次。正金额从该月份起有效，0表示从该月关闭，查询月份采用不晚于该月份的最近记录。

导入保留本机相同月份的显式设置，补充尚不存在的月份；与分类和账单在同一事务合并。全部内容先校验，拒绝重复月份、无效日期、非法金额和冲突分类。备份仍不含密钥、音频或待确认提案。

两端使用`fixtures/monthly-budget.json`校验相同的消费、退款、剩余、超支与圆环比例。绘图比例只用于展示，金额始终按整数分计算。
