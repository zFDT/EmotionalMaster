# 开发状态与下一步

## 当前基线

- 更新日期：2026-07-21
- 开发分支：`feature/mvp-foundation`
- 基线提交：`fd5fed0 docs(验收): 补充 MVP 安全模型与验收记录`
- 远程分支：`origin/feature/mvp-foundation`
- 构建环境：JDK 17、Android SDK、Gradle Wrapper

本文件描述当前功能边界和后续开发方向。测试结论以 [MVP 验收记录](../testing/mvp-acceptance.md) 为准，安全边界以 [MVP 威胁模型](../security/threat-model.md) 为准。

## 已完成

- 建立 `app`、`core:model`、`core:importer`、`core:analytics`、`core:storage`、`core:llm` 多模块工程。
- 支持约定格式的 JSON/JSONL 聊天记录预览、校验、身份映射、去重和事务导入。
- 使用 SQLCipher 保存聊天数据，并由 Android Keystore 保护数据库口令。
- 提供消息占比、回复中位数、会话发起和活跃时段等可复算统计。
- 提供 OpenAI-compatible 模型网关、上下文脱敏、明确确认和离线降级能力。
- 完成导入、分析、回复建议和复制建议的 Compose 主流程，不包含自动发送能力。
- 禁用自动备份、迁移数据导出和明文网络流量，并完成敏感日志检查。
- 通过单元测试、lint、Debug APK 构建和 Android API 36 模拟器设备测试。

## 尚未完成

- 尚未提供线上模型 endpoint、供应商和密钥的产品配置入口；当前实际运行使用离线降级建议。
- 尚未实现微信无障碍采集、MediaProjection/OCR 回退或其他平台采集适配器。
- 尚未实现持续画像、证据与反证管理、置信度更新、人工修正和完整删除入口。
- 尚未实现 Root/LSPosed 增强模块及 Soul、小红书、抖音等平台适配器。
- 尚未完成 Release 签名、发布流水线、目标 OEM 验收、性能测试和隐私政策。
- UI 仅达到可用和可读标准，品牌视觉、动效和细节适配留待功能稳定后处理。

## 下一步建议

下一计划阶段是非 Root 数据采集。实现前应先形成独立规格并确认以下内容：

1. 明确首个目标平台和受支持版本，不同时并行开发多个平台适配器。
2. 定义无障碍服务只在用户主动触发时读取哪些节点，以及何时停止采集。
3. 定义节点不可读时切换到 MediaProjection/OCR 的条件、提示和取消路径。
4. 为权限拒绝、平台升级、页面结构变化和识别失败设计可理解的回退行为。
5. 使用脱敏样例建立适配器契约测试和回归样本，避免把真实聊天记录提交到仓库。

线上模型配置入口可作为独立任务推进，但在选择真实供应商前必须先完成隐私、数据留存、服务地区、endpoint allowlist 和密钥存储评审。

## 开发注意事项

- 保持本地优先；新增上传、分析 SDK、崩溃 SDK 或模型依赖前重新评估数据流并取得批准。
- 不在源码、Gradle 配置、测试资源、日志或截图中保存 API Key、Token、真实联系人和真实聊天内容。
- Root、无障碍和屏幕捕获是独立风险边界，不得为了便利隐式开启或捆绑授权。
- 所有画像结论都应包含证据、反证、置信度和更新时间，不把统计相关性描述成事实判断。
- 保持“建议只能复制、绝不自动发送”的产品边界。
- 修改导入、数据库或网络链路时，同步复核幂等、事务回滚、日志和备份策略。
- 添加新依赖前先说明用途和替代方案并取得批准；版本固定在 `gradle/libs.versions.toml`。
- UI 优先保证流程完整、状态清楚、窄屏和大字体可用，暂不进行与功能无关的大规模视觉重构。

## 多设备协作

功能开发通过远程分支同步，`.worktrees/`、构建产物、本机 SDK 配置和 Git stash 不会上传到 GitHub。

开始工作前：

```powershell
git fetch origin
git switch feature/mvp-foundation
git pull --ff-only
```

提交前至少运行与改动最相关的测试；涉及完整主流程时运行：

```powershell
.\gradlew.bat test lintDebug :app:assembleDebug --no-daemon --console=plain
```

完成提交后推送当前分支：

```powershell
git push origin feature/mvp-foundation
```

不要依赖 stash 进行跨设备交接。未完成工作应形成可构建的阶段性提交，或在同一设备上继续完成后再推送。合并到 `main` 前，应确认远程 CI 或本地完整验证通过，并通过 Pull Request 保留审查记录。
