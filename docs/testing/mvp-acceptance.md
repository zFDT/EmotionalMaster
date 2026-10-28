# MVP 验收记录

## 验收环境

- 最近验收日期：2026-07-30
- 工作分支：`feature/mvp-foundation`
- JDK：17
- 自动化设备：Android API 36 模拟器 `emulator-5554`
- 本轮视觉配置：常规 1080×2400
- 历史基础 MVP 视觉配置：常规 1080×2400、320 dp + 1.5× 字体、约 914 dp 宽横屏

## 自动化检查

| 检查 | 命令或证据 | 结果 |
|---|---|---|
| 单元测试、lint、Debug APK | `.\gradlew.bat test lintDebug :app:assembleDebug --no-daemon --console=plain` | 通过 |
| 基础 MVP Compose 主流程 | `MvpFlowTest#importAnalyzePreviewAndCopySuggestion` | 2026-07-21 的三种视觉配置均通过；2026-07-30 常规尺寸通过 |
| 基础 MVP 保存失败恢复 | `MvpFlowTest#failedPersistenceStaysOnPreviewAndCanRetry` | 2026-07-21 的三种视觉配置均通过；2026-07-30 常规尺寸通过 |
| Android 设备测试 | `$env:ANDROID_SERIAL='emulator-5554'; .\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain` | Android API 36 模拟器，17/17 通过，0 skipped |
| DeepSeek 配置安全存储 | `DeepSeekConfigurationStoreInstrumentedTest#androidKeystore配置可往返且SharedPreferences不含明文` | Android Keystore alias 保存后存在、清除后消失；配置可往返，偏好中无 Key 明文 |
| 重复导入 | `ImportRepositoryTest#重复导入同一来源批次保持幂等` | 第二次返回 `ALREADY_IMPORTED`，消息数保持 1 |
| 错误批次 | `ImportRepositoryTest#包含错误行的解析批次拒绝写入` | 返回 `REJECTED`，消息与批次计数均为 0 |
| 敏感日志 | 清空 logcat 后，以应用 UID 和测试 UID 运行 17 条 fake instrumentation，再扫描 4 个敏感标记 | `sk-test-log-secret`、`Authorization`、聊天样例、`body-secret` 均为 0 命中 |
| 视觉可用性 | 本轮常规尺寸 15 张截图；历史基础 MVP 常规、窄屏大字体、横屏共 31 张截图 | 模型设置、在线失败和显式本地建议状态可用、可读；细节视觉后续迭代 |

## 人工流程验收

- [x] 文件确认前只展示预览，不写入数据库。
- [x] 身份映射可交换，保存失败停留原页并可重试。
- [x] 分析展示消息占比、回复中位数、会话发起、活跃时段和样本口径。
- [x] 模型确认页展示当前消息、脱敏历史范围和供应商状态，可取消。
- [x] 未配置 provider 时明确显示“离线降级”。
- [x] 返回 4 条策略不同的建议，每条都有正文、意图、风险、适用条件和复制按钮。
- [x] 复制后出现剪贴板隐私提示；不存在自动发送按钮或后台发送路径。
- [x] 系统返回从回复页回到分析页，重新进入后当前输入仍保留。
- [x] 模型设置页只显示 DeepSeek 官方服务，API Key 使用密码语义和遮罩文本。
- [x] 模型目录显示 `deepseek-v4-pro`，弃用模型带有明确标记。
- [x] 保存配置后回复页显示 DeepSeek 与所选模型；编辑已验证 Key 后必须重新验证。
- [x] 在线失败保留上下文预览和输入，明确显示重试、取消及“使用本地建议”。
- [x] 本地建议必须由用户显式选择，不会把在线失败静默伪装成在线成功。

## 发布产物与证据

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 界面流程提交：`722a975 feat(界面): 串联聊天导入分析与回复建议`。
- DeepSeek API 36 测试稳定提交：`e1d0810 test(界面): 稳定 API 36 设置流程`。
- logcat 扫描方式：安装当前应用与测试 APK，记录两个包的 UID，执行 `adb logcat -c` 和全量 fake instrumentation，再用 `adb logcat -d --uid=<app_uid>,<test_uid>` 对四项标记逐项计数。
- 测试宿主截图中的白色系统栏图标是测试 Activity 主题差异；生产 `MainActivity` 截图验证为正确深色图标。

## 剩余设备验收

- Android API 26 的最低版本 Keystore 行为尚未在本轮执行。
- DeepSeek 新状态尚未完成目标实体设备/OEM、窄屏、大字体和 TalkBack 人工复核。
- 自动化和截图继续只使用 fake provider、fake server 和测试标记，不得调用真实 DeepSeek 或使用真实 API Key。
