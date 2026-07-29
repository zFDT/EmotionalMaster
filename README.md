# 情感大师

一个本地优先的 Android 聊天记录分析 MVP。当前版本支持 JSON/JSONL 文件预览、身份映射、加密导入、可复算统计、脱敏上下文确认，以及 4 条可复制的回复策略。

## 项目状态

- 基础 MVP 已完成；当前第一版在线能力仅支持 DeepSeek 官方服务，不支持自定义 endpoint 或其他 OpenAI-compatible 供应商。
- 当前开发分支为 `feature/mvp-foundation`，首期实现尚未合并到 `main`。
- 下一阶段计划进行非 Root 数据采集能力的方案设计，包括无障碍按需采集和 MediaProjection/OCR 回退。
- UI 现阶段以流程可用和文字可读为目标，功能稳定后再迭代视觉细节。

当前完成情况、后续任务、开发约束和多设备协作方式见 [开发状态与下一步](docs/development/status-and-next-steps.md)，整体交付顺序见 [Android 总路线图](docs/superpowers/plans/2026-07-20-emotional-master-roadmap.md)。

## 隐私边界

- 文件通过 Android Storage Access Framework 只读打开，确认前不写入资料库。
- 聊天数据存入由 Android Keystore 密钥保护的 SQLCipher 数据库。
- 禁用 Android 自动备份、设备迁移数据导出和明文网络流量。
- 模型请求前展示真实脱敏上下文并允许取消；应用不提供自动发送能力。
- 未配置 DeepSeek、读取配置失败或在线生成失败时，界面会明确说明原因；只有用户再次选择“使用本地建议”才会生成离线保守建议，不会静默把在线结果替换成本地结果。

## DeepSeek 在线配置

- 在“回复建议”页点击“模型设置”进入配置页。第一版固定连接 `api.deepseek.com` 的官方 `/models` 与 Chat Completions 接口。
- API Key 只需在验证和首次保存时输入；保存后不回显明文，由 Android Keystore 的 AES-GCM 密钥在本机加密保护。
- 模型列表来自 DeepSeek 官方 `/models`。默认优先选择 `deepseek-v4-pro`；没有该模型时选择列表中的首个非弃用模型。已弃用模型仍会标注展示，但不会成为默认选择。
- 获取模型列表只提交鉴权信息，不提交聊天内容。生成回复前会展示将上传的脱敏上下文，只有用户点击“确认生成”才发起在线请求。
- 在线失败会保留预览和当前输入，用户可以重试、取消，或显式切换到 4 条本地建议。建议只能复制，应用不会自动发送。

## 构建与验证

需要 JDK 17 和 Android SDK：

```powershell
.\gradlew.bat test lintDebug :app:assembleDebug
```

设备验收记录见 `docs/testing/mvp-acceptance.md`，威胁与缓解措施见 `docs/security/threat-model.md`。

## 当前限制

- 仅支持项目约定的 JSON/JSONL 导入结构，不直接读取第三方应用私有目录。
- 线上模型仅支持 DeepSeek 官方服务；尚不支持自定义 endpoint、其他供应商或本地大模型。
- UI 当前以流程可用和文字可读为目标，品牌与细节视觉将在功能稳定后迭代。
