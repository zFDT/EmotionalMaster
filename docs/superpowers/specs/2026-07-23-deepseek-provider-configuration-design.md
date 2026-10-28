# DeepSeek 在线模型配置设计

## 状态

- 日期：2026-07-23
- 状态：已确认，等待实现计划
- 目标分支：`feature/mvp-foundation`
- 产品范围：第一版仅支持 DeepSeek 官方 API，内部保留通用 `ModelProvider` 接口

## 背景与目标

当前应用已经具备 OpenAI-compatible 请求、结构化回复解析、上下文脱敏和离线规则降级能力，但没有线上供应商配置入口。实际运行时只能生成本地保守建议，尚未形成真实模型回复闭环。

本阶段需要让用户一次输入 DeepSeek API Key，通过官方模型列表接口验证凭据并选择可用模型。配置在应用重启后继续生效；生成回复前仍展示真实脱敏上下文并要求用户确认；模型失败时不得自动发送、泄漏敏感信息或把离线结果伪装成在线结果。

本阶段完成后再进入微信非 Root 采集设计与实现。

## 范围

### 包含

- DeepSeek 官方 API Key 的本机持久化、更新和清除。
- 调用 `GET https://api.deepseek.com/models` 验证 Key 并读取可用模型。
- 从服务端返回列表中选择模型，默认优先 `deepseek-v4-pro`。
- 使用所选模型调用 `POST https://api.deepseek.com/chat/completions`。
- DeepSeek 配置页面、回复页面入口、配置状态和错误提示。
- 在线调用失败后的显式错误和用户可控离线降级。
- 单元测试、Android 集成测试、日志检查和现有主流程回归。

### 不包含

- 自定义 Base URL 或第三方 OpenAI-compatible 服务。
- 多供应商账号管理、云端同步或团队共享 Key。
- 流式输出、工具调用、联网搜索、图片理解或长对话记忆。
- API 余额查询、充值入口或用量计费统计。
- 微信无障碍、MediaProjection/OCR、Root/LSPosed 等数据采集能力。
- UI 品牌化重构；页面只需流程可用、状态清楚和文字可读。

## 外部接口基线

DeepSeek 官方 OpenAI-compatible Base URL 为 `https://api.deepseek.com`。模型发现使用 `/models`，回复生成使用 `/chat/completions`，请求通过 `Authorization: Bearer <API_KEY>` 鉴权。

实现时只接受 HTTPS 官方主机 `api.deepseek.com`，不从用户输入构造 endpoint。当前默认模型是 `deepseek-v4-pro`。`deepseek-chat` 和 `deepseek-reasoner` 将于 2026-07-24 15:59 UTC 停止服务，不作为默认值；若服务端仍返回旧模型，界面应标记为即将弃用。

参考：

- [DeepSeek 列出模型](https://api-docs.deepseek.com/zh-cn/api/list-models/)
- [DeepSeek 首次 API 调用](https://api-docs.deepseek.com/guides/function_calling/)
- [DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/)
- [DeepSeek 错误码](https://api-docs.deepseek.com/quick_start/error_codes/)
- [DeepSeek 更新日志](https://api-docs.deepseek.com/updates/)

## 架构设计

### 通用模型调用层

保留现有 `ModelProvider` 和 `OpenAiCompatibleProvider`。回复服务只依赖 `ModelProvider`，不直接读取 Android 配置，也不感知 SharedPreferences 或 DeepSeek 页面。

`OpenAiCompatibleProvider` 继续负责 Chat Completions 请求、JSON Output、响应内容读取和安全元数据日志。DeepSeek 配置被验证后，由应用装配层使用固定官方 endpoint、解密后的 Key 和用户选择的模型构造 Provider。

### DeepSeek 模型目录

新增独立的模型目录接口及 DeepSeek 实现。它只负责：

1. 使用候选 API Key 请求官方 `/models`。
2. 校验 HTTP 状态和响应结构。
3. 返回非空、去重、稳定排序的模型 ID 列表。
4. 把认证、余额、限流、服务端和网络错误转换成界面可理解的领域错误。

模型发现和回复生成共享安全 HTTP 约束，但不合并成同一个职责对象。以后新增供应商时，可以增加新的目录实现和装配逻辑，而不改变回复服务契约。

### Android 配置存储

非敏感字段使用应用私有 SharedPreferences 保存：

- 配置格式版本
- 供应商标识 `deepseek`
- 所选模型 ID
- Key 密文、IV 和加密格式版本

API Key 不得以明文写入 SharedPreferences。应用在 Android Keystore 中生成不可导出的 AES-GCM 包装密钥，用它加密 Key；SharedPreferences 只保存 Base64 编码的密文和 IV。读取时在内存中短暂解密，用于构造认证头，不写入日志、异常、SavedState、Compose 测试语义或剪贴板。

不新增 `EncryptedSharedPreferences` 依赖。该 API 已弃用，项目直接使用 Android Keystore 与 JCA，模式与现有数据库口令包装保持一致，但使用独立 alias 和独立存储字段。

写入新配置采用“先验证、后替换”：模型列表成功返回且用户确认后，才覆盖上一次有效配置。清除配置时同时删除 SP 字段和对应 Keystore alias；清除失败必须显示错误，不得只更新界面状态。

## 页面与状态流

回复页面在供应商状态附近提供“模型设置”入口。第一版不增加全局导航抽屉或复杂设置中心。

配置页包含：

- 固定供应商名称：DeepSeek
- 固定官方服务地址，只读展示
- API Key 输入框；已有 Key 仅显示“已保存”或掩码，不回填明文
- “验证并获取模型”操作
- 服务端返回的模型选择列表
- 手动刷新模型列表
- “保存并启用”与“清除配置”操作
- 加密保存、上传范围和第三方数据处理说明

状态流如下：

1. 未配置时输入 Key。
2. 点击验证后调用 `/models`，期间禁用重复提交。
3. 成功后展示可用模型，优先选中 `deepseek-v4-pro`；若不存在则选中第一项，但不自动持久化。
4. 用户确认后加密保存 Key 和模型，返回回复页面。
5. 回复页面显示 `DeepSeek · <模型 ID>`。
6. 用户输入当前消息并预览脱敏上下文。
7. 用户明确确认后才调用 DeepSeek。
8. 在线结果通过严格结构校验后展示；失败时展示原因，并允许用户主动选择本地建议。

已保存配置再次打开时，不自动调用网络。用户点击刷新或更换 Key 后才重新获取模型。更换 Key 但验证失败时，继续保留旧配置。

## 回复请求

回复请求继续使用 `response_format={"type":"json_object"}`，系统提示必须明确要求 JSON，并包含现有建议结构示例。请求固定为非流式，使用用户选择的模型。

回复建议必须满足既有领域约束：

- 4—6 条建议
- 策略名称不重复
- `text`、`strategy`、`intent`、`risk`、`applicableWhen` 均为非空字符串
- 响应为空、JSON 截断、未知字段或条数不合法均视为在线失败

第一版不暴露 thinking 或 reasoning effort 开关。使用 DeepSeek V4 Pro 时采用官方接口默认行为，避免同时引入模型选择与高级推理参数配置。后续若真实使用数据表明延迟或费用不可接受，再单独设计开关。

## 错误与降级

模型发现和回复生成至少区分：

| 场景 | 用户提示 | 配置处理 |
|---|---|---|
| 400/422 | 请求参数或接口格式不兼容 | 不保存候选配置 |
| 401 | API Key 无效 | 不保存候选配置，保留旧配置 |
| 402 | DeepSeek 账户余额不足 | 不保存候选配置，保留旧配置 |
| 429 | 请求过于频繁 | 提示稍后重试，保留配置 |
| 500/503 | DeepSeek 服务异常或繁忙 | 提示稍后重试，保留配置 |
| 超时/断网/DNS | 无法连接 DeepSeek | 提示检查网络，保留配置 |
| 空模型列表或异常 JSON | 服务响应格式无效 | 不保存候选配置 |
| Key 解密失败 | 本机配置已损坏或系统密钥不可用 | 禁止在线调用，允许清除后重新配置 |
| 回复结构不合法 | 在线结果无法安全使用 | 不展示残缺结果，允许用户选择离线建议 |

当前 `ReplySuggestionService` 会把所有异常直接转换成离线结果，无法向 UI 说明真实原因。实现时需要让服务返回明确的在线失败信息，离线建议必须由用户可见地选择或由界面明确标注后生成，不能静默伪装成成功的在线调用。

## 隐私与安全边界

- API Key、Authorization、请求正文和响应正文不得写入日志。
- 安全元数据日志只允许记录请求方法、去除 query 的固定 endpoint、状态码和耗时。
- 模型列表请求不携带聊天内容。
- 回复请求只在上下文预览确认后发送脱敏消息。
- 配置页不得提供明文复制 Key 的操作。
- Android 备份和设备迁移继续保持禁用。
- Root 或被攻陷系统仍在信任边界外，产品不声称在该环境下能完全保护 Key。
- 不在仓库、测试资源、截图、CI 参数或文档示例中放置真实 Key。

## 测试与验收

### 单元测试

- 模型列表解析、去重、默认排序和空列表拒绝。
- `/models` 对 401、402、429、500、503、超时和异常 JSON 的错误映射。
- 默认优先选择 `deepseek-v4-pro`，缺失时选择第一项。
- 配置状态只在验证并确认后替换。
- API Key 加密后不包含明文，能够跨实例读取、更新和清除。
- Key 解密失败时禁止在线调用。
- Chat Completions 使用选定模型、JSON Output 和固定官方 endpoint。
- 在线失败不会返回伪装成在线成功的建议；显式离线降级仍返回合法的 4—6 条建议。

### Android 流程测试

- 从回复页面进入设置，输入测试 Key，获取模型，选择并保存。
- 返回回复页面后显示 DeepSeek 与所选模型。
- Activity 重建后配置仍存在，Key 明文不出现在界面语义树。
- 清除配置后恢复未配置状态。
- 配置失败时旧配置仍可使用。
- 上下文确认页显示供应商、模型和真实脱敏范围。

### 发布前检查

- 运行 `test`、`lintDebug` 和 `:app:assembleDebug`。
- 在 Android API 26 与当前目标模拟器上覆盖 Keystore 保存、重启和清除。
- 运行 Compose 设备流程测试。
- 清空 logcat 后执行配置与回复流程，扫描测试 Key、Authorization 和聊天样例，要求零命中。
- 使用本地测试服务器或 fake 验证协议；自动化测试不得调用真实 DeepSeek 服务或消耗真实余额。

## 完成定义

- 用户只需输入一次 DeepSeek API Key，应用重启后仍可使用。
- SP 中不存在 API Key 明文，Keystore 包装密钥不可导出。
- 模型选项来自当前 Key 的 `/models` 响应，默认优先 `deepseek-v4-pro`。
- 未验证的新配置不能覆盖旧配置。
- 回复确认页准确显示 DeepSeek、模型和上传范围。
- 在线成功与离线降级在 UI 上可明确区分。
- 错误不会泄漏 Key、聊天原文或 Authorization。
- 现有导入、分析、复制建议和无自动发送边界保持不变。
