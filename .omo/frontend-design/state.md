# Frontend Design State

## 当前目标与锁定决策

- 目标：实现聊天导入、统计分析、上下文确认和多策略复制的 Android MVP。
- 设计契约：`DESIGN.md`。
- 方向：编辑型关系日志；暖纸色、墨色、陶土主色、苔绿色第二数据序列；Material 3 原生交互。
- 红线：不自动上传、不自动发送；导入和模型请求均先预览并允许取消。

## 输入与明确排除

- 产品规格：`docs/superpowers/specs/2026-07-20-emotional-master-android-design.md`。
- 实现计划：`docs/superpowers/plans/2026-07-20-foundation-import-analysis-mvp.md`。
- 视觉检索：Breeze、Ultrahuman、Grok、Raycast、Preview、Acrobat 的移动端截图，仅作为信息层级观察。
- 概念稿：`exec-98e09fec-81b1-4922-9f41-c6b8972b629e.png`、`exec-68c20e69-387a-47ba-a6f8-0450a0249575.png`、`exec-233812de-aed1-4410-825b-6f72095963e5.png`。
- 排除：粉色恋爱模板、仪表盘堆叠、玻璃拟态、渐变、Emoji 图标、图像驱动首页、任何发送自动化。

## 设计原则与成功标准

- 掌控感优先、事实先于判断、一屏一主行动、柔和但清晰、Android 原生。
- 成功标准：完整 MVP 流程可操作；异步与错误状态完整；TalkBack、字体缩放、48dp 触控与色彩对比达标；真实截图通过视觉复核。

## 用户画像

- 情绪压力下希望快速获得克制建议的用户。
- 对聊天隐私、云端上传边界高度敏感的用户。
- 使用 TalkBack、放大字体或需要较大触控区域的用户。

## 决策日志

| 日期 | 决策 | 理由 |
|---|---|---|
| 2026-07-21 | 以第 3 张分析概念稿为主视觉 | 品牌辨识度、内容层级与 Compose 可实现性最佳 |
| 2026-07-21 | 采用第 2 张导入稿的信息结构 | 文件、统计、身份和错误的分组最清楚 |
| 2026-07-21 | 不新增字体依赖 | 用户要求新增依赖前询问，系统字体足以完成 MVP |
| 2026-07-21 | 不采用生成稿中的印章、Emoji 和发送暗示 | 保持图标一致性并贯彻“仅复制、不发送” |

## 开放问题

- 当前未配置线上模型供应商；产品会在确认页明确提示并使用本地降级，不伪装成在线结果。
- 用户已确认当前阶段 UI 以“可用、正常可看”为验收线，品牌与像素级优化在功能完成后迭代。

## 设计债务

| ID | 日期 | 来源 | 严重度 | 问题 | 受影响用户 | 建议修复 | 状态 | 备注 |
|---|---|---|---|---|---|---|---|---|
| DD-001 | 2026-07-21 | 设计规划 | Note | 品牌自定义字体未引入 | 全体用户 | 产品需要更强品牌化时单独评估字体资源 | deferred | 系统 serif/sans 不影响可访问性 |
| DD-002 | 2026-07-21 | 设计规划 | Minor | 尚无真实设备的大字体与横屏截图 | 低视力及横屏用户 | UI 完成后在模拟器/设备补截图并执行 visual-qa | closed | 已完成常规、320dp + 1.5× 字体、约 914dp 横屏三组端到端截图 |

## 下一阶段交接

- 继续安全、备份策略与发布文档收尾；线上 provider 配置单独设计，不在代码中写入密钥。
- 后续 UI 迭代优先补组件 Showcase、深色截图与品牌资源，不阻塞当前功能推进。

## 证据索引

- 设计契约：`DESIGN.md`
- 产品规格：`docs/superpowers/specs/2026-07-20-emotional-master-android-design.md`
- MVP 计划：`docs/superpowers/plans/2026-07-20-foundation-import-analysis-mvp.md`
- 视觉检索目录：`C:/Users/dreame/.codex/visualizations/2026/07/20/019f7fd2-7908-7681-bf50-d5385bfa13a8/lazyweb-references/`
- 概念稿目录：`C:/Users/dreame/.codex/generated_images/019f7fd2-7908-7681-bf50-d5385bfa13a8/`
- 常规手机截图：`C:/Users/dreame/.codex/visualizations/2026/07/20/019f7fd2-7908-7681-bf50-d5385bfa13a8/mvp-ui-qa/visual-qa/`
- 320dp + 1.5× 字体截图：`C:/Users/dreame/.codex/visualizations/2026/07/20/019f7fd2-7908-7681-bf50-d5385bfa13a8/mvp-ui-qa/visual-qa-responsive/`
- 约 914dp 横屏截图：`C:/Users/dreame/.codex/visualizations/2026/07/20/019f7fd2-7908-7681-bf50-d5385bfa13a8/mvp-ui-qa/visual-qa-landscape/`
- 自动化证据：三种配置的 `MvpFlowTest` 均为 `OK (2 tests)`，覆盖主流程、系统返回输入保留和保存失败重试。
