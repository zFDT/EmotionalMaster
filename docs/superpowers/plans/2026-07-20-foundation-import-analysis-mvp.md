# 情感大师基础导入分析 MVP 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 交付一个可安装的 Android MVP：导入授权聊天记录、生成可复算统计和证据摘要，并通过 OpenAI-compatible 模型生成 4—6 条不同策略的候选回复。

**架构：** 建立 `app`、`core:model`、`core:importer`、`core:analytics`、`core:storage`、`core:llm` 六个模块。领域层不依赖 Android；文件导入先解析到暂存模型，经校验、身份映射和去重后事务写入加密数据库；统计结果与受控上下文经网关生成结构化建议。

**技术栈：** Kotlin、Gradle Kotlin DSL、Android SDK 35、Jetpack Compose、Coroutines/Flow、Room + SQLCipher、kotlinx.serialization、OkHttp、Android Keystore、JUnit、Robolectric、Compose UI Test。

---

## 前置门禁

- [ ] 记录 `java -version`、`javac -version`、`adb version` 和已安装 Android SDK；当前已知 PATH 中只有 JDK 11，Android 构建开始前需经用户批准安装并切换至 JDK 17。
- [ ] 向用户列出拟新增依赖及用途，取得明确批准后再写入版本目录并下载：Compose、Coroutines、Room、SQLCipher、kotlinx.serialization、OkHttp、JUnit、Robolectric。
- [ ] 依照 Android 官方兼容表选择相互兼容的稳定版本，将最终版本固定在 `gradle/libs.versions.toml`，不使用动态版本。

## 任务 1：建立可编译的多模块 Android 工程

**文件：**

- 创建：`settings.gradle.kts`
- 创建：`build.gradle.kts`
- 创建：`gradle.properties`
- 创建：`gradle/libs.versions.toml`
- 创建：`.gitignore`
- 创建：`app/build.gradle.kts`
- 创建：`app/src/main/AndroidManifest.xml`
- 创建：`app/src/main/java/cn/echobird/emotionalmaster/MainActivity.kt`
- 创建：`app/src/main/java/cn/echobird/emotionalmaster/EmotionalMasterApp.kt`
- 创建：`core/model/build.gradle.kts`
- 创建：`core/importer/build.gradle.kts`
- 创建：`core/analytics/build.gradle.kts`
- 创建：`core/storage/build.gradle.kts`
- 创建：`core/llm/build.gradle.kts`

**步骤：**

- [ ] 先只创建根工程和模块声明，运行 `./gradlew.bat projects`，确认在模块脚本缺失时失败，错误指向缺失模块。
- [ ] 补齐各模块最小构建脚本、命名空间、SDK 配置和依赖方向；领域模块禁止反向依赖 `app`。
- [ ] 创建只显示“情感大师”的 Compose 首页。
- [ ] 运行 `./gradlew.bat projects`，预期列出六个模块。
- [ ] 运行 `./gradlew.bat :app:assembleDebug`，预期生成 `app/build/outputs/apk/debug/app-debug.apk`。
- [ ] 提交：`chore(工程): 初始化 Android 多模块项目`

## 任务 2：定义领域模型和平台适配器契约

**文件：**

- 创建：`core/model/src/main/kotlin/cn/echobird/emotionalmaster/model/ChatModels.kt`
- 创建：`core/model/src/main/kotlin/cn/echobird/emotionalmaster/model/ProfileModels.kt`
- 创建：`core/model/src/main/kotlin/cn/echobird/emotionalmaster/model/ReplyModels.kt`
- 创建：`core/model/src/main/kotlin/cn/echobird/emotionalmaster/model/PlatformAdapter.kt`
- 创建：`core/model/src/test/kotlin/cn/echobird/emotionalmaster/model/ModelInvariantTest.kt`

**步骤：**

- [ ] 先写测试：消息必须有稳定来源 ID、时间、方向和正文或附件之一；画像假设必须带证据、反证、置信度和更新时间；回复集合只能包含 4—6 个不重复策略。
- [ ] 运行 `./gradlew.bat :core:model:test`，预期因模型尚未定义而失败。
- [ ] 实现不可变数据类、值对象和校验工厂；`PlatformAdapter` 只暴露能力探测、授权采集、标准化和健康诊断。
- [ ] 再运行同一测试，预期全部通过。
- [ ] 提交：`feat(模型): 定义聊天画像与回复领域契约`

## 任务 3：实现 JSON/JSONL 导入流水线

**文件：**

- 创建：`core/importer/src/main/kotlin/cn/echobird/emotionalmaster/importer/ImportModels.kt`
- 创建：`core/importer/src/main/kotlin/cn/echobird/emotionalmaster/importer/JsonChatParser.kt`
- 创建：`core/importer/src/main/kotlin/cn/echobird/emotionalmaster/importer/ImportValidator.kt`
- 创建：`core/importer/src/main/kotlin/cn/echobird/emotionalmaster/importer/MessageDeduplicator.kt`
- 创建：`core/importer/src/test/resources/chat/minimal.json`
- 创建：`core/importer/src/test/resources/chat/duplicate.jsonl`
- 创建：`core/importer/src/test/kotlin/cn/echobird/emotionalmaster/importer/JsonChatParserTest.kt`
- 创建：`core/importer/src/test/kotlin/cn/echobird/emotionalmaster/importer/ImportIdempotenceTest.kt`

**步骤：**

- [ ] 先写失败测试，覆盖 JSON、逐行 JSON、未知字段容忍、错误行定位、身份映射、内容哈希去重和重复导入幂等。
- [ ] 运行 `./gradlew.bat :core:importer:test`，预期因解析器缺失而失败。
- [ ] 实现流式读取，避免把整份聊天记录一次性载入内存；原文件只读并计算 SHA-256 来源哈希。
- [ ] 让解析结果先进入暂存批次，任何致命校验错误都不得产生部分提交。
- [ ] 再运行测试，预期全部通过。
- [ ] 提交：`feat(导入): 支持 JSON 与 JSONL 聊天记录`

## 任务 4：实现加密存储和事务导入

**文件：**

- 创建：`core/storage/src/main/kotlin/cn/echobird/emotionalmaster/storage/Entities.kt`
- 创建：`core/storage/src/main/kotlin/cn/echobird/emotionalmaster/storage/ChatDao.kt`
- 创建：`core/storage/src/main/kotlin/cn/echobird/emotionalmaster/storage/EmotionalMasterDatabase.kt`
- 创建：`core/storage/src/main/kotlin/cn/echobird/emotionalmaster/storage/DatabaseKeyProvider.kt`
- 创建：`core/storage/src/main/kotlin/cn/echobird/emotionalmaster/storage/ImportRepository.kt`
- 创建：`core/storage/src/test/kotlin/cn/echobird/emotionalmaster/storage/ImportRepositoryTest.kt`

**步骤：**

- [ ] 先写测试，覆盖唯一来源键、事务回滚、重复导入、删除联系人级数据和数据库重新打开。
- [ ] 运行 `./gradlew.bat :core:storage:test`，预期因 DAO 和数据库缺失而失败。
- [ ] 实现 Room 表、索引与事务；数据库密钥由 Keystore 包装，禁止出现在日志和普通偏好设置中。
- [ ] 实现导入批次审计，只存文件哈希、数量、时间和错误摘要，不存原始文件路径。
- [ ] 再运行测试，预期全部通过。
- [ ] 提交：`feat(存储): 实现聊天数据加密与事务导入`

## 任务 5：实现确定性聊天统计

**文件：**

- 创建：`core/analytics/src/main/kotlin/cn/echobird/emotionalmaster/analytics/ConversationStats.kt`
- 创建：`core/analytics/src/main/kotlin/cn/echobird/emotionalmaster/analytics/SessionSegmenter.kt`
- 创建：`core/analytics/src/main/kotlin/cn/echobird/emotionalmaster/analytics/StatsEngine.kt`
- 创建：`core/analytics/src/test/kotlin/cn/echobird/emotionalmaster/analytics/StatsEngineTest.kt`
- 创建：`core/analytics/src/test/kotlin/cn/echobird/emotionalmaster/analytics/SessionSegmenterTest.kt`

**步骤：**

- [ ] 先写表驱动测试，覆盖双方消息占比、主动发起、回复间隔、中位数/IQR 异常值处理、7×24 热力和月度趋势。
- [ ] 把“连续消息间隔超过 5 分钟即新会话”定义为可配置统计参数，而非人格阈值。
- [ ] 运行 `./gradlew.bat :core:analytics:test`，预期因引擎缺失而失败。
- [ ] 实现纯 Kotlin 统计函数，输出携带样本量和计算参数，确保相同输入得到相同结果。
- [ ] 再运行测试，预期全部通过。
- [ ] 提交：`feat(分析): 添加可复算的聊天统计引擎`

## 任务 6：实现模型网关和结构化回复建议

**文件：**

- 创建：`core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ModelProvider.kt`
- 创建：`core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/OpenAiCompatibleProvider.kt`
- 创建：`core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ContextRedactor.kt`
- 创建：`core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ReplySuggestionService.kt`
- 创建：`core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/RuleFallback.kt`
- 创建：`core/llm/src/test/kotlin/cn/echobird/emotionalmaster/llm/ReplySuggestionServiceTest.kt`
- 创建：`core/llm/src/test/kotlin/cn/echobird/emotionalmaster/llm/ContextRedactorTest.kt`

**步骤：**

- [ ] 先写测试，覆盖脱敏、最小上下文、超时、非 2xx、非法 JSON、少于 4 条、多于 6 条、重复策略和规则降级。
- [ ] 运行 `./gradlew.bat :core:llm:test`，预期因服务缺失而失败。
- [ ] 实现 OpenAI-compatible 请求与严格结构化响应；建议字段包含正文、策略、意图、风险提示和适用条件。
- [ ] 实现日志拦截器白名单，保证请求正文、响应正文和 Authorization 永不写日志。
- [ ] 模型失败时由规则层输出 4 条保守建议，并在 UI 数据中标记“离线降级”。
- [ ] 再运行测试，预期全部通过。
- [ ] 提交：`feat(建议): 生成多策略回复并支持离线降级`

## 任务 7：完成导入、分析和复制建议 UI

**文件：**

- 创建：`app/src/main/java/cn/echobird/emotionalmaster/importing/ImportScreen.kt`
- 创建：`app/src/main/java/cn/echobird/emotionalmaster/importing/ImportViewModel.kt`
- 创建：`app/src/main/java/cn/echobird/emotionalmaster/analysis/AnalysisScreen.kt`
- 创建：`app/src/main/java/cn/echobird/emotionalmaster/analysis/AnalysisViewModel.kt`
- 创建：`app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyScreen.kt`
- 创建：`app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyViewModel.kt`
- 创建：`app/src/androidTest/java/cn/echobird/emotionalmaster/MvpFlowTest.kt`

**步骤：**

- [ ] 先写 Compose 流程测试：选择文档、预览解析、映射双方身份、确认导入、查看统计、输入当前消息、看到 4—6 条策略并复制一条。
- [ ] 运行 `./gradlew.bat :app:connectedDebugAndroidTest`，预期在 UI 未实现时失败。
- [ ] 使用 Storage Access Framework 只读打开文件；导入确认前展示消息数、时间范围、参与者和错误摘要。
- [ ] 在建议发送模型前展示“将上传的上下文”预览和取消入口；不提供任何自动发送按钮。
- [ ] 实现空状态、加载、可重试错误、模型降级和敏感权限说明。
- [ ] 再运行仪器测试，预期主流程通过。
- [ ] 提交：`feat(界面): 串联聊天导入分析与回复建议`

## 任务 8：安全与发布前验证

**文件：**

- 创建：`docs/security/threat-model.md`
- 创建：`docs/testing/mvp-acceptance.md`
- 修改：`app/src/main/AndroidManifest.xml`
- 修改：`README.md`

**步骤：**

- [ ] 写威胁模型，覆盖本机文件、数据库、备份、日志、剪贴板、网络传输、模型供应商和 Root 环境。
- [ ] 禁用明文流量与 Android 自动备份；对可导出组件和网络安全配置做最小授权。
- [ ] 运行 `./gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug`，预期全部成功且无阻断级 lint。
- [ ] 在一台 Android 13+ 设备执行 `./gradlew.bat :app:connectedDebugAndroidTest`，预期主流程通过。
- [ ] 使用样例文件连续导入两次，确认消息数量不增加；制造错误行，确认整批回滚。
- [ ] 检查 `adb logcat`，确认无聊天原文、API Key、Authorization 或数据库密钥。
- [ ] 根据 `docs/testing/mvp-acceptance.md` 人工验收 4—6 条建议的差异性、可复制性和“无自动发送”。
- [ ] 提交：`docs(验收): 补充 MVP 安全模型与验收记录`

## 完成定义

- Debug APK 可安装并完成首期全流程。
- 单元测试、lint 和设备测试均有当次运行记录。
- JSON/JSONL 重导幂等，失败导入可完整回滚。
- 模型上下文可预览且经过脱敏，密钥由 Keystore 保护。
- 每次成功或降级均返回 4—6 条不同策略建议，不存在自动发送路径。
- 未经用户批准，不安装新运行时、不新增依赖、不启用 Root 或无障碍权限。
