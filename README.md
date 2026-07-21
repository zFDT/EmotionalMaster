# 情感大师

一个本地优先的 Android 聊天记录分析 MVP。当前版本支持 JSON/JSONL 文件预览、身份映射、加密导入、可复算统计、脱敏上下文确认，以及 4 条可复制的回复策略。

## 项目状态

- 基础 MVP 已完成，并已通过单元测试、lint、Debug APK 构建和 Android 模拟器流程测试。
- 当前开发分支为 `feature/mvp-foundation`，首期实现尚未合并到 `main`。
- 下一阶段计划进行非 Root 数据采集能力的方案设计，包括无障碍按需采集和 MediaProjection/OCR 回退。
- UI 现阶段以流程可用和文字可读为目标，功能稳定后再迭代视觉细节。

当前完成情况、后续任务、开发约束和多设备协作方式见 [开发状态与下一步](docs/development/status-and-next-steps.md)，整体交付顺序见 [Android 总路线图](docs/superpowers/plans/2026-07-20-emotional-master-roadmap.md)。

## 隐私边界

- 文件通过 Android Storage Access Framework 只读打开，确认前不写入资料库。
- 聊天数据存入由 Android Keystore 密钥保护的 SQLCipher 数据库。
- 禁用 Android 自动备份、设备迁移数据导出和明文网络流量。
- 模型请求前展示真实脱敏上下文并允许取消；应用不提供自动发送能力。
- 当前未配置线上模型供应商，确认后会明确使用本地离线降级建议。

## 构建与验证

需要 JDK 17 和 Android SDK：

```powershell
.\gradlew.bat test lintDebug :app:assembleDebug
```

设备验收记录见 `docs/testing/mvp-acceptance.md`，威胁与缓解措施见 `docs/security/threat-model.md`。

## 当前限制

- 仅支持项目约定的 JSON/JSONL 导入结构，不直接读取第三方应用私有目录。
- 线上模型 endpoint、供应商和密钥配置尚未提供产品入口。
- UI 当前以流程可用和文字可读为目标，品牌与细节视觉将在功能稳定后迭代。
