# Accounts 识别规则 v1

在 Accounts 的「设置 → 识别规则」导出 JSON。编辑后导入，检查启用数量，再确认替换本机规则。未安装过自定义规则时，导出文件包含一条停用的微信规则示例。规则修改随下一次识别生效。

智能体可以用 [JSON Schema](recognition-rules.schema.json) 校验字段，并按下列约定修改从手机导出的完整文件。`id` 必须全局唯一，文件不得超过 64 KB。导入还会检查完成短语至少包含三个文字字符。规则文件只包含匹配条件，不含 API 密钥或诊断记录。

```json
{
  "format": "AccountsRecognitionRules",
  "version": 1,
  "rules": [
    {
      "id": "skip-wechat-archive",
      "channel": "screen",
      "packageName": "com.tencent.mm",
      "action": "reject",
      "containsAll": ["账单详情", "交易时间"]
    },
    {
      "id": "wechat-shop-receipt",
      "enabled": true,
      "channel": "screen",
      "packageName": "com.tencent.mm",
      "action": "accept",
      "completionPhrase": "支付成功",
      "containsAll": ["返回商家"],
      "containsNone": ["交易记录", "我的账单"]
    }
  ]
}
```

- `channel` 为 `screen`（无障碍可见文字）或 `notification`（支付通知）。`packageName` 必须是完整包名。微信为 `com.tencent.mm`，支付宝为 `com.eg.android.AlipayGphone`。
- `containsAll` 必须全部出现，`containsAny` 至少出现一项（空数组时跳过此条件），`containsNone` 中任何词出现都排除此规则。匹配区分大小写，使用普通文字，`*` 等符号也按字面匹配。
- `accept` 要求某行或可见文字节点以 `completionPhrase`（例如「支付成功」）开头，随后是行尾、标点、空格或金额，并且输入含有唯一正金额。识别到两个不同金额时，规则可以用 `amountAnchor` 定位后续金额，例如 `实付金额`。可用 `merchant` 提供固定商户名。
- `reject` 至少需要一条 `containsAll` 或 `containsAny`。同一输入只采用最先匹配的启用规则，因此针对历史、退款或广告的拒绝规则应排在允许规则之前。`enabled: false` 临时停用一条规则。
- 都未匹配时继续使用内置识别。应用锁、付款确认、微信历史和已排除通知等保护仍会在对应通道执行。导入 `rules: []` 可清空自定义规则。

分析漏记时应先按诊断里的来源、包名和排除原因定位对应通道。新增允许规则前，验证相近的付款前页面、账单历史、退款和普通聊天不会入账。分析重复时，与同一次付款的屏幕和通知事件对照，避免用很宽的拒绝条件屏蔽其他交易。
