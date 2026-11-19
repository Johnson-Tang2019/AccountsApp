# Accounts 的 DeepSeek 诊断

在识别诊断页选择时间范围，再打开「DeepSeek 诊断」。预览显示将要发送的提示词和脱敏 JSON。每次发送都需确认。

- 「预览并交给 DSH Go」将预览文本交给支持 Android 分享的 DSH Go。继续在 DSH Go 里审阅并提交。此路径使用 DSH Go 自己的模型设置。[DSH Go](https://github.com/ccch1mneyyy/dsh-Go)
- 「预览并分析」通过 HTTPS Chat Completions 请求模型。默认地址为 `https://api.deepseek.com/chat/completions`，默认模型为 `deepseek-flash`。兼容服务可填写完整的 HTTPS `/chat/completions` 地址和模型名称。API Key 用于当前请求，不会保存或写入导出文件。[DeepSeek API](https://api-docs.deepseek.com/)

发送的 `accounts-redacted-diagnostics` v1 JSON 最多包含所选区间内最近 200 条事件。`offsetMs` 是相对起始时间，`index` 是在这份报告里的顺序，`attemptGroup` 用于关联同一识别尝试。`truncated` 表示同一时段还有更早的诊断事件。只发送微信、支付宝的包名以及经过筛选的诊断信号。完整时间戳、原始页面和通知内容、商户、交易金额和自定义规则 ID 不随此报告发送。

DeepSeek API 返回的 `rulesConfig` 通过校验后可导出为 `accounts-rule-draft.json`。在应用当前规则前，先导出现有完整配置，把草案中需要的规则合并进去，再通过识别规则页导入确认。规则格式参见 [Accounts 识别规则 v1](recognition-rules.md)。
