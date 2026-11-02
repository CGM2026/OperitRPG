# 专用模型接口与旧配置

`rpg/model` 已移除 AIService、ModelConfigManager、通用聊天历史、工具框架和原日志依赖。仅使用 OkHttp、coroutines、serialization 与 DataStore；保存配置不发起请求，也没有真实付费 API 验证。

支持用户明确选择的四类文本协议：OpenAI Chat Completions、OpenAI Responses、Anthropic Messages、Gemini generateContent。按标准响应结构提取可见文本，忽略独立 thinking/reasoning/signature；工具输出、空正文、截断结果、格式不符均失败，不执行、不修补、不自动重试或换协议。演示模式只匹配已发布的固定 DEMO 配置快照。

每阶段最多一个 attempt 请求，60 秒上限；取消 coroutine 或指定 attempt 会关闭对应 Call。响应原始字节最多 1 MiB，可见正文最多 64,000 字符。输入用轻量字符估算，超限先裁掉最旧历史，保留当前输入、公开状态、规则及已提交结果；必要上下文仍超限则明确失败。这是估算上限，不宣称等同所有供应商的精确 tokenizer。

端点只接受 HTTP(S)，拒绝用户名/密码、查询参数、片段、反斜杠及控制字符。保留旧末尾 `#` 禁补全标记。兼容旧同 host 的路径补全；不改变协议或 host。HTTP 仅在用户明确保存/选择时使用，并由设置页确认明文风险。完全禁止 HTTP 与 HTTPS 重定向以及连接自动重试，密钥不会随重定向发送。无 Key 的明确本地接口不虚构凭据，Anthropic 版本头始终存在。

没有请求、响应、凭据或正文日志；异常仅包含固定安全文案及 HTTP 状态，不包含原始错误响应或原异常。反射 API Key 的接口正文被拒绝，配置对象的 toString 也不包含 Key。Key 只留在原本机偏好文件，不进入跑团数据库或存档。

`RpgModelConfigStore` 直接使用原 `datastore/model_configs.preferences_pb` 文件里的 `config_list` 和 `config_ID` JSON。没有数据迁移、默认覆盖、未知配置清理或 corruption replacement；编辑合并原 JSON，保留未知字段与其它 preference 键。读取未支持配置也不重写源数据。

已知旧供应商按其既定文本协议识别。未知供应商、OAuth、本地原生推理、多密钥或自定义头/参数配置完整保留并标为需要明确重新配置。用户选择协议、端点、模型和单 Key 保存后，`d20Protocol` 表示已明确采用精简接口；旧高级字段仍留存，但不参与新请求。

旧 configurationId、providerId、modelId 不变，兼容指纹仍为：

```text
sha256(id + newline + providerId + newline + modelId + newline + endpoint + newline + thinkingConfigurations + newline + thinkingOptionId)
```

只有用户明确把 `d20Protocol` 改成不同于旧供应商既定协议时，才扩展为 `sha256(legacyHash + newline + "d20Protocol=" + protocol)`，防止旧进行中回合悄悄切协议。相同协议编辑名称或 Key 不改变旧指纹。需要换配置的已结算回合继续使用既有显式叙述恢复绑定入口。

MockWebServer 测试覆盖四协议请求/可见输出、usage、思考分离、null控制字段、工具拒绝、空/错/截断响应、HTTP失败不重试、跨 host 重定向阻止、取消/超时、体积限制、凭据脱敏与反射拒绝、历史裁剪、旧指纹固定样本、未知字段保留与明确协议转换。没有把 Mock 结果当作真实供应商兼容验证。
