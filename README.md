# Accounts

一个本地优先的安卓记账应用。用户授权后，应用识别受支持的支付结果页或支付通知，并将金额、商户和时间写入本机 SQLite 数据库。

## 构建

使用 Android Studio 打开项目，安装 Android SDK 35 后运行 `app`。首次启动点击“开启无障碍自动记账”，在系统设置中找到“支付信息自动记账”并开启。

## 隐私边界

- 本机处理受支持的支付结果页和支付通知。自定义规则按应用包名及识别通道限定范围。
- 跳过密码节点及所有可编辑输入框。
- 自动识别和记账在本机进行。选用 DeepSeek 时，只有确认发送才会向配置的服务提交诊断摘要。Android 备份已关闭。

## 识别诊断与规则

在「设置 → 识别诊断日志」选取起止日期和时间，导出诊断 JSON。文件记录通道、时间戳、判断结果和结构化证据，可直接交给智能体分析。参见 [诊断文件接口](docs/recognition-diagnostics.md) 和 [DeepSeek / DSH Go 接入](docs/deepseek-diagnostics.md)。

在「设置 → 识别规则」导出规则 JSON，编辑后导入并确认。字段定义与示例见 [规则说明](docs/recognition-rules.md) 和 [JSON Schema](docs/recognition-rules.schema.json)。

> 无障碍能力应仅用于用户明确知情并主动授权的用途。发布到应用商店前，请核对目标市场的无障碍 API 政策、隐私披露和数据安全表单要求。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 开源。
