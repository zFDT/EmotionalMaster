# MVP 验收记录

## 验收环境

- 日期：2026-07-21
- 工作分支：`feature/mvp-foundation`
- JDK：17
- 自动化设备：Android API 36 模拟器 `emulator-5554`
- 视觉配置：常规 1080×2400、320dp + 1.5× 字体、约 914dp 宽横屏

## 自动化检查

| 检查 | 命令或证据 | 结果 |
|---|---|---|
| 单元测试、lint、Debug APK | `.\gradlew.bat test lintDebug :app:assembleDebug --no-daemon --console=plain` | 通过 |
| Compose 主流程 | `MvpFlowTest#importAnalyzePreviewAndCopySuggestion` | 三种视觉配置均通过 |
| 保存失败恢复 | `MvpFlowTest#failedPersistenceStaysOnPreviewAndCanRetry` | 三种视觉配置均通过 |
| Android 13+ 设备测试 | `ANDROID_SERIAL=emulator-5554 .\gradlew.bat :app:connectedDebugAndroidTest` | Android API 36 模拟器，2/2 通过 |
| 重复导入 | `ImportRepositoryTest#重复导入同一来源批次保持幂等` | 第二次返回 `ALREADY_IMPORTED`，消息数保持 1 |
| 错误批次 | `ImportRepositoryTest#包含错误行的解析批次拒绝写入` | 返回 `REJECTED`，消息与批次计数均为 0 |
| 敏感日志 | 清空 logcat 后安装当前 APK 并运行 2 条设备测试，再扫描 8 个敏感标记 | 聊天样例、API token、Authorization、数据库密钥均为 0 命中 |
| 视觉可用性 | 常规、窄屏大字体、横屏共 31 张截图；独立复核 | PASS；细节视觉后续迭代 |

## 人工流程验收

- [x] 文件确认前只展示预览，不写入数据库。
- [x] 身份映射可交换，保存失败停留原页并可重试。
- [x] 分析展示消息占比、回复中位数、会话发起、活跃时段和样本口径。
- [x] 模型确认页展示当前消息、脱敏历史范围和供应商状态，可取消。
- [x] 未配置 provider 时明确显示“离线降级”。
- [x] 返回 4 条策略不同的建议，每条都有正文、意图、风险、适用条件和复制按钮。
- [x] 复制后出现剪贴板隐私提示；不存在自动发送按钮或后台发送路径。
- [x] 系统返回从回复页回到分析页，重新进入后当前输入仍保留。

## 发布产物与证据

- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 界面流程提交：`722a975 feat(界面): 串联聊天导入分析与回复建议`。
- logcat 扫描方式：先执行 `adb logcat -c`，运行当前测试 APK，再对 `早上好`、当前消息样例、测试 token、`Authorization` 和数据库密钥标记逐项计数。
- 测试宿主截图中的白色系统栏图标是测试 Activity 主题差异；生产 `MainActivity` 截图验证为正确深色图标。
