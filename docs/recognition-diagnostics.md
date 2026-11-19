# Accounts 识别诊断文件 v1

在「设置 → 识别诊断日志 → 导出 JSON」选择时间范围，通过 Android 文件选择器保存 UTF-8 JSON。界面输入的时间按设备时区解释，截止时间包含所填的一整秒。应用内可通过 `RecognitionLogger.report(context, fromMs, toMs, limit)` 生成相同格式。

| 字段 | 含义 |
|---|---|
| `format` / `schemaVersion` | 固定为 `accounts-recognition-diagnostics` / `1` |
| `timeZone` | 导出设备时区 |
| `range.fromMsInclusive` / `range.toMsExclusive` | 查询范围的 Unix 毫秒时间戳，左闭右开 |
| `retention` | 保留天数、条数上限和最早可用时间 |
| `matchingEventCount` / `truncated` | 范围内可用事件数，以及结果是否因导出上限截断 |
| `events` | 按时间升序排列的事件数组 |

每个事件有 `timestampMs`、`channel`（`screen`、`notification` 或 `system`）、`code`、`evidence`，也可能包含 `packageName`。使用 `evidence.attemptId` 关联同一次检测的后续事件，使用 `evidence.ruleId` 对照规则配置。常见结果包括 `recorded`（已写账）、`replay`（重复采样）、`cross_channel_duplicate`（屏幕与通知匹配到同一笔）、`confirmation_required`（同金额待确认）、`rule_rejected`（规则拒绝）和 `amount_missing`（金额未读到）。

诊断最多保留最近 14 天中的 2,000 条新格式事件。允许导出的证据包括有无金额、是否匹配结果标记、节点数和诊断标识。原始屏幕文字、通知内容、具体金额、商户、账户凭据和历史纯文本日志不进入诊断 JSON。分析时可结合一张自行脱敏的支付结果截图补足未知的页面词语。
