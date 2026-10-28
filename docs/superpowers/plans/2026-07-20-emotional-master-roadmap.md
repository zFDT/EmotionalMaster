# 情感大师 Android 总路线图

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 Android 上交付一个本地优先、Root 可选的聊天分析助手，从授权数据中形成可追溯画像，并给出多条不会自动发送的回复建议。

**架构：** 采用“采集适配器 → 标准化 → 加密存储 → 确定性统计 → 证据画像 → 回复建议”的流水线。Root、无障碍、OCR 和文件导入均实现同一适配器契约，任何单一采集方式失败都可回退。

**技术栈：** Kotlin、Android SDK、Jetpack Compose、Coroutines/Flow、Room + SQLCipher、kotlinx.serialization、OkHttp、Android Keystore、JUnit、Robolectric、Compose UI Test。

---

## 交付顺序

- [x] 阶段 0：确认依赖版本并安装 JDK 17；新增依赖前取得用户批准。
- [x] 阶段 1：实现本地项目骨架、聊天记录导入、确定性统计、OpenAI-compatible 网关和 4—6 条回复建议。
- [ ] 阶段 2：实现微信无障碍按需采集、MediaProjection/OCR 回退与权限引导。
- [ ] 阶段 3：实现持续画像、证据/反证/置信度、人工修正与删除。
- [ ] 阶段 4：实现 Root/LSPosed 独立增强模块和微信深层适配器。
- [ ] 阶段 5：依次增加 Soul、小红书、抖音平台适配器。
- [ ] 阶段 6：完成端到端安全测试、性能测试、脱敏导出与发布打包。

截至 2026-07-21，阶段 0 和阶段 1 已在 `feature/mvp-foundation` 完成并通过 MVP 验收。阶段 2 是下一计划阶段，开始实现前需要先明确采集范围、权限边界、目标微信版本和失败回退策略。详细状态见 [开发状态与下一步](../../development/status-and-next-steps.md)。

## 阶段边界

### 首期可用版本

用户能导入一份聊天记录，选择双方身份，查看基础统计，为当前对话生成 4—6 条有不同推进策略的回复，并复制其中一条。模型失败时显示可理解的降级结果；应用绝不自动发送。

### 非 Root 可用版本

用户能通过无障碍模式抓取当前可见会话。节点不可读时，应用提示用户切换到屏幕捕获/OCR；任何权限均可单独关闭。

### Root 增强版本

深层采集作为可卸载、可禁用的增强能力。平台版本变化或 Hook 失败时，核心应用与已有数据仍可使用。

## 跨阶段验收规则

- 所有画像结论必须带证据来源、置信度、反证和更新时间。
- 原始聊天内容与密钥不得进入普通日志、崩溃报告或明文配置。
- 上传云模型的上下文必须可预览、可取消、可脱敏。
- 自动发送始终不在范围内；复制建议是唯一的发送前动作。
- 每个平台适配器必须通过契约测试、失败回退测试和样例数据回归测试。

## 关联文档

- [Android 方案规格](../specs/2026-07-20-emotional-master-android-design.md)
- [微信记录导入补充规格](../specs/2026-07-20-wechat-record-import-addendum.md)
- [参考项目评审](../../research/2026-07-20-wechat-analyzer-reference-review.md)
- [首期 MVP 实现计划](2026-07-20-foundation-import-analysis-mvp.md)
