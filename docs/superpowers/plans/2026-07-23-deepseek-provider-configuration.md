# DeepSeek 在线模型配置实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让用户安全保存 DeepSeek API Key，从官方接口获取并选择模型，使用 `deepseek-v4-pro` 优先完成在线回复，同时保留明确、可控的离线降级。

**架构：** `core:llm` 新增与 `ModelProvider` 分离的 DeepSeek 模型目录，并把在线回复失败作为显式结果返回；`core:storage` 使用 Android Keystore 的 AES-GCM 密钥包装 API Key，SharedPreferences 只持久化密文和模型。`app` 新增设置状态与 Compose 页面，通过固定官方 endpoint 装配现有 OpenAI-compatible Provider。

**技术栈：** Kotlin、Jetpack Compose、Android Keystore、SharedPreferences、OkHttp、kotlinx.serialization、JUnit、Robolectric、Compose UI Test；不新增第三方依赖。

---

## 文件结构

### 新建

- `core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ModelCatalog.kt`：通用模型目录契约、模型描述和目录错误类型。
- `core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/DeepSeekModelCatalog.kt`：固定 DeepSeek 官方 `/models` 协议实现和模型优先级。
- `core/llm/src/test/kotlin/cn/echobird/emotionalmaster/llm/DeepSeekModelCatalogTest.kt`：模型发现、错误分类和日志安全测试。
- `core/storage/src/main/kotlin/cn/echobird/emotionalmaster/storage/DeepSeekConfigurationStore.kt`：配置模型、存储契约和 Keystore + SP 实现。
- `core/storage/src/test/kotlin/cn/echobird/emotionalmaster/storage/DeepSeekConfigurationStoreTest.kt`：用可控密码器验证持久化原子性和无明文。
- `app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsController.kt`：不依赖 Android 生命周期的同步设置状态机。
- `app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsViewModel.kt`：候选 Key 验证、模型选择、保存和清除状态机。
- `app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsScreen.kt`：可用优先的 DeepSeek 设置页面。
- `app/src/test/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsControllerTest.kt`：不联网验证同步设置状态机。

### 修改

- `core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ReplySuggestionService.kt`：在线失败显式化，离线建议改为用户触发。
- `core/llm/src/test/kotlin/cn/echobird/emotionalmaster/llm/ReplySuggestionServiceTest.kt`：更新在线失败和显式降级断言。
- `app/src/main/java/cn/echobird/emotionalmaster/MainActivity.kt`：装配配置存储、目录、Provider 和设置导航。
- `app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyViewModel.kt`：按当前配置创建服务，展示在线错误并响应离线按钮。
- `app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyScreen.kt`：增加模型设置入口、在线失败和显式离线操作。
- `app/src/androidTest/java/cn/echobird/emotionalmaster/MvpFlowTest.kt`：覆盖设置入口、模型标签和现有主流程回归。
- `docs/security/threat-model.md`：记录 API Key 存储、DeepSeek 数据边界和剩余风险。
- `docs/development/status-and-next-steps.md`：完成后更新线上模型配置状态。
- `README.md`：补充 DeepSeek 配置能力和未配置时的降级说明。

## 任务 1：实现 DeepSeek 模型目录

**文件：**

- 创建：`core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ModelCatalog.kt`
- 创建：`core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/DeepSeekModelCatalog.kt`
- 创建：`core/llm/src/test/kotlin/cn/echobird/emotionalmaster/llm/DeepSeekModelCatalogTest.kt`

- [ ] **步骤 1：编写失败测试，覆盖模型列表、默认模型和错误映射**

使用 JDK `HttpServer`，不得调用真实 DeepSeek：

```kotlin
@Test
fun `模型列表去重并优先选择 v4 pro`() {
    server = serverResponding(
        200,
        """{"object":"list","data":[{"id":"deepseek-v4-flash","object":"model","owned_by":"deepseek"},{"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek"},{"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek"}]}""",
    )
    val catalog = DeepSeekModelCatalog(endpointFor(server!!))

    val models = catalog.listModels("test-secret")

    assertEquals(listOf("deepseek-v4-pro", "deepseek-v4-flash"), models.map { it.id })
    assertEquals("deepseek-v4-pro", preferredDeepSeekModel(models).id)
}

@Test
fun `认证余额限流和服务错误保持可区分`() {
    mapOf(
        401 to ModelCatalogException.InvalidApiKey::class.java,
        402 to ModelCatalogException.InsufficientBalance::class.java,
        429 to ModelCatalogException.RateLimited::class.java,
        503 to ModelCatalogException.ServiceUnavailable::class.java,
    ).forEach { (status, expected) ->
        server = serverResponding(status, "{}")
        assertThrows(expected) {
            DeepSeekModelCatalog(endpointFor(server!!)).listModels("test-secret")
        }
        server!!.stop(0)
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
.\gradlew.bat :core:llm:test --tests "*DeepSeekModelCatalogTest" --no-daemon --console=plain
```

预期：FAIL，`DeepSeekModelCatalog`、`ModelCatalogException` 和 `preferredDeepSeekModel` 尚不存在。

- [ ] **步骤 3：实现目录契约和最小 DeepSeek 客户端**

定义稳定接口：

```kotlin
data class AvailableModel(
    val id: String,
    val isDeprecated: Boolean,
)

fun interface ModelCatalog {
    fun listModels(apiKey: String): List<AvailableModel>
}

sealed class ModelCatalogException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class InvalidApiKey : ModelCatalogException("API Key 无效")
    class InsufficientBalance : ModelCatalogException("DeepSeek 账户余额不足")
    class RateLimited : ModelCatalogException("请求过于频繁，请稍后重试")
    class InvalidRequest(val statusCode: Int) : ModelCatalogException("DeepSeek 请求格式不兼容")
    class ServiceUnavailable(val statusCode: Int) : ModelCatalogException("DeepSeek 服务暂时不可用")
    class Timeout(cause: Throwable) : ModelCatalogException("连接 DeepSeek 超时", cause)
    class Transport(cause: Throwable) : ModelCatalogException("无法连接 DeepSeek", cause)
    class MalformedResponse(cause: Throwable? = null) : ModelCatalogException("DeepSeek 模型列表无效", cause)
}
```

`DeepSeekModelCatalog` 固定默认 endpoint 为 `https://api.deepseek.com/models`，构造器仅为了测试允许注入 URL；发送 Bearer header，解析 `data[].id`，拒绝空列表，将 `deepseek-chat` 和 `deepseek-reasoner` 标记为弃用，排序时 `deepseek-v4-pro` 第一、其他模型按 ID 排序。

- [ ] **步骤 4：补充安全断言并运行测试**

测试服务器记录 header，事件字符串不得包含 `test-secret`、`Authorization` 或响应正文；再运行任务 1 测试，预期全部 PASS。

- [ ] **步骤 5：提交模型目录**

```powershell
git add core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ModelCatalog.kt core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/DeepSeekModelCatalog.kt core/llm/src/test/kotlin/cn/echobird/emotionalmaster/llm/DeepSeekModelCatalogTest.kt
git commit -m "feat(模型): 获取 DeepSeek 可用模型"
```

## 任务 2：把在线失败与离线建议明确分开

**文件：**

- 修改：`core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ReplySuggestionService.kt`
- 修改：`core/llm/src/test/kotlin/cn/echobird/emotionalmaster/llm/ReplySuggestionServiceTest.kt`

- [ ] **步骤 1：把旧的自动降级测试改成显式失败测试**

```kotlin
@Test
fun `非法在线响应返回失败而不自动伪装为离线成功`() {
    val result = ReplySuggestionService(FakeProvider("not-json"))
        .suggest(listOf(message("你好")))

    assertTrue(result is ReplySuggestionResult.OnlineFailure)
    assertEquals("在线回复格式无效", (result as ReplySuggestionResult.OnlineFailure).message)
}

@Test
fun `用户明确选择时才生成离线建议`() {
    val service = ReplySuggestionService(FakeProvider(failure = ModelProviderException.HttpFailure(503)))

    val offline = service.offlineSuggestions()

    assertEquals(4, offline.suggestions.size)
    assertEquals(4, offline.suggestions.map { it.strategy }.distinct().size)
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：

```powershell
.\gradlew.bat :core:llm:test --tests "*ReplySuggestionServiceTest" --no-daemon --console=plain
```

预期：FAIL，现有服务仍把异常自动转成 `OFFLINE_FALLBACK`。

- [ ] **步骤 3：实现显式结果类型**

```kotlin
sealed interface ReplySuggestionResult {
    data class OnlineSuccess(val suggestions: ReplySuggestionSet) : ReplySuggestionResult
    data class OnlineFailure(val message: String) : ReplySuggestionResult
}

class ReplySuggestionService(...) {
    fun suggest(messages: List<ChatMessage>): ReplySuggestionResult = try {
        val response = provider.generate(ModelRequest(redactor.redact(messages)))
        ReplySuggestionResult.OnlineSuccess(parseSuggestions(response))
    } catch (error: Exception) {
        ReplySuggestionResult.OnlineFailure(error.toUserMessage())
    }

    fun offlineSuggestions(): ReplySuggestionSet = fallback.suggestions()
}
```

错误文案必须按 `ModelProviderException.HttpFailure.statusCode` 区分 401、402、429、500/503；解析失败统一为“在线回复格式无效”，未知异常不得拼接原始异常正文。

- [ ] **步骤 4：运行 `core:llm` 全量测试**

```powershell
.\gradlew.bat :core:llm:test --no-daemon --console=plain
```

预期：PASS。

- [ ] **步骤 5：提交显式降级语义**

```powershell
git add core/llm/src/main/kotlin/cn/echobird/emotionalmaster/llm/ReplySuggestionService.kt core/llm/src/test/kotlin/cn/echobird/emotionalmaster/llm/ReplySuggestionServiceTest.kt
git commit -m "fix(建议): 区分在线失败与离线降级"
```

## 任务 3：加密持久化 DeepSeek 配置

**文件：**

- 创建：`core/storage/src/main/kotlin/cn/echobird/emotionalmaster/storage/DeepSeekConfigurationStore.kt`
- 创建：`core/storage/src/test/kotlin/cn/echobird/emotionalmaster/storage/DeepSeekConfigurationStoreTest.kt`

- [ ] **步骤 1：编写失败测试，验证 SP 无明文和旧配置保护**

```kotlin
@RunWith(RobolectricTestRunner::class)
class DeepSeekConfigurationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `保存后偏好文件不包含 API Key 明文且可重新读取`() {
        val store = SharedPreferencesDeepSeekConfigurationStore(context, ReversibleTestCipher())
        store.save(DeepSeekConfiguration("deepseek-v4-pro", "sk-test-secret"))

        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertFalse(preferences.all.values.joinToString().contains("sk-test-secret"))
        assertEquals("deepseek-v4-pro", store.load()!!.modelId)
        assertEquals("sk-test-secret", store.load()!!.apiKey)
    }

    @Test
    fun `写入失败不替换上一次有效配置`() {
        val store = SharedPreferencesDeepSeekConfigurationStore(context, ReversibleTestCipher())
        store.save(DeepSeekConfiguration("deepseek-v4-pro", "old-key"))

        assertThrows(ConfigurationStoreException::class.java) {
            store.save(DeepSeekConfiguration("deepseek-v4-flash", ""))
        }

        assertEquals("old-key", store.load()!!.apiKey)
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

```powershell
.\gradlew.bat :core:storage:testDebugUnitTest --tests "*DeepSeekConfigurationStoreTest" --no-daemon --console=plain
```

预期：FAIL，配置存储类型尚不存在。

- [ ] **步骤 3：实现配置契约、可测试密码器和 SP 存储**

```kotlin
data class DeepSeekConfiguration(val modelId: String, val apiKey: String) {
    init {
        require(modelId.isNotBlank())
        require(apiKey.isNotBlank())
    }
}

interface DeepSeekConfigurationStore {
    fun load(): DeepSeekConfiguration?
    fun configuredModelId(): String?
    fun save(configuration: DeepSeekConfiguration)
    fun clear()
}

data class EncryptedSecret(val ciphertext: ByteArray, val iv: ByteArray)

internal interface SecretCipher {
    fun encrypt(plaintext: ByteArray): EncryptedSecret
    fun decrypt(secret: EncryptedSecret): ByteArray
    fun clearKey()
}
```

`SharedPreferencesDeepSeekConfigurationStore` 使用单次 `commit()` 写入格式版本、模型、Base64 密文和 IV，并检查返回值。解析、Base64、解密或版本错误统一抛出不含敏感值的 `ConfigurationStoreException`。`configuredModelId()` 只读模型，不触发 Key 解密。

- [ ] **步骤 4：实现 Android Keystore AES-GCM 密码器**

使用独立 alias `emotional-master-deepseek-api-key`，算法固定 `AES/GCM/NoPadding`，密钥用途仅为 encrypt/decrypt，随机 IV，GCM tag 128 位。不得复用数据库 alias，也不得把 Key 转成日志字符串。

- [ ] **步骤 5：运行存储模块测试**

```powershell
.\gradlew.bat :core:storage:testDebugUnitTest --no-daemon --console=plain
```

预期：PASS。

- [ ] **步骤 6：提交安全配置存储**

```powershell
git add core/storage/src/main/kotlin/cn/echobird/emotionalmaster/storage/DeepSeekConfigurationStore.kt core/storage/src/test/kotlin/cn/echobird/emotionalmaster/storage/DeepSeekConfigurationStoreTest.kt
git commit -m "feat(存储): 加密保存 DeepSeek 配置"
```

## 任务 4：实现设置状态机

**文件：**

- 创建：`app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsController.kt`
- 创建：`app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsViewModel.kt`
- 创建：`app/src/test/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsControllerTest.kt`

- [ ] **步骤 1：编写失败测试，覆盖验证、默认选择和旧配置保护**

```kotlin
@Test
fun `验证成功优先选中 v4 pro 但确认前不保存`() {
    val store = FakeConfigurationStore(saved = oldConfiguration)
    val controller = DeepSeekSettingsController(
        store = store,
        catalog = ModelCatalog { listOf(model("deepseek-v4-flash"), model("deepseek-v4-pro")) },
    )

    controller.verifyAndLoadModels("new-key")

    assertEquals("deepseek-v4-pro", controller.state.selectedModelId)
    assertEquals(oldConfiguration, store.saved)
}

@Test
fun `候选 key 验证失败保留旧配置`() {
    val store = FakeConfigurationStore(saved = oldConfiguration)
    val controller = DeepSeekSettingsController(
        store,
        ModelCatalog { throw ModelCatalogException.InvalidApiKey() },
    )

    controller.verifyAndLoadModels("bad-key")

    assertEquals("API Key 无效", controller.state.errorMessage)
    assertEquals(oldConfiguration, store.saved)
}
```

- [ ] **步骤 2：运行测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*DeepSeekSettingsControllerTest" --no-daemon --console=plain
```

预期：FAIL，设置状态机尚不存在。

- [ ] **步骤 3：实现 UI 状态和动作**

```kotlin
data class DeepSeekSettingsUiState(
    val hasSavedConfiguration: Boolean = false,
    val models: List<AvailableModel> = emptyList(),
    val selectedModelId: String? = null,
    val isLoading: Boolean = false,
    val canSave: Boolean = false,
    val errorMessage: String? = null,
)
```

`DeepSeekSettingsController` 实现 `verifyAndLoadModels(candidateApiKey: String?)`、`selectModel`、`saveVerifiedConfiguration`、`clearConfiguration`。已保存配置刷新时允许候选 Key 为 null 并临时读取旧 Key；候选 Key 只保存在 Controller 私有字段，验证失败或保存/清除后立即置空。任何候选配置只有经过本轮 `/models` 验证才令 `canSave=true`。

`DeepSeekSettingsViewModel` 只负责在 `viewModelScope` 中把目录和存储操作调度到 `Dispatchers.IO`，并把 Controller 的不可变 state 发布给 Compose；它不得把 API Key 放入公开 UI state 或 SavedState。

- [ ] **步骤 4：运行设置测试与 app 单元测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

预期：PASS。

- [ ] **步骤 5：提交设置状态机**

```powershell
git add app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsController.kt app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsViewModel.kt app/src/test/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsControllerTest.kt
git commit -m "feat(设置): 管理 DeepSeek 配置状态"
```

## 任务 5：完成设置页面和导航

**文件：**

- 创建：`app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsScreen.kt`
- 修改：`app/src/main/java/cn/echobird/emotionalmaster/MainActivity.kt`
- 修改：`app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyScreen.kt`
- 修改：`app/src/androidTest/java/cn/echobird/emotionalmaster/MvpFlowTest.kt`

- [ ] **步骤 1：先写 Compose 失败流程**

在 `EmotionalMasterFlow` 注入设置状态和回调，测试不发网络请求：

```kotlin
composeRule.onNodeWithText("模型设置").performScrollTo().performClick()
composeRule.onNodeWithText("DeepSeek").assertIsDisplayed()
composeRule.onNodeWithTag("deepseek-api-key").performTextInput("test-key")
composeRule.onNodeWithText("验证并获取模型").performClick()
composeRule.onNodeWithText("deepseek-v4-pro").assertIsDisplayed().performClick()
composeRule.onNodeWithText("保存并启用").performClick()
composeRule.onNodeWithText("DeepSeek · deepseek-v4-pro").assertIsDisplayed()
```

测试 fake 回调返回两项模型并记录保存参数；语义树断言中不得出现 `test-key`。

- [ ] **步骤 2：运行设备测试确认失败**

```powershell
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain
```

预期：FAIL，回复页没有设置入口，设置页面和导航状态不存在。

- [ ] **步骤 3：实现可用优先的设置页面**

页面包含只读供应商与域名、密码键盘输入框、验证按钮、单选模型列表、刷新、保存、清除和隐私说明。API Key 输入用非 `rememberSaveable` 的页面局部状态保存，设置 `PasswordVisualTransformation` 且测试 tag 为 `deepseek-api-key`；导航离开后立即丢弃，已有配置只显示“已保存”，不回填明文。模型项显示弃用提示，但禁止默认选择旧模型。

- [ ] **步骤 4：增加设置导航**

给 `MvpScreen` 增加 `DEEPSEEK_SETTINGS`。从回复页进入，保存或返回后回到回复页；系统 Back 行为一致。`EmotionalMasterFlow` 的测试注入接口必须只暴露模型和动作，不暴露持久化 Key。

- [ ] **步骤 5：运行 Compose 设备流程测试**

使用与步骤 2 相同命令，预期设置流程及原有 2 条测试全部 PASS。

- [ ] **步骤 6：提交页面与导航**

```powershell
git add app/src/main/java/cn/echobird/emotionalmaster/settings/DeepSeekSettingsScreen.kt app/src/main/java/cn/echobird/emotionalmaster/MainActivity.kt app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyScreen.kt app/src/androidTest/java/cn/echobird/emotionalmaster/MvpFlowTest.kt
git commit -m "feat(界面): 添加 DeepSeek 模型设置"
```

## 任务 6：接通真实在线回复与显式离线按钮

**文件：**

- 修改：`app/src/main/java/cn/echobird/emotionalmaster/MainActivity.kt`
- 修改：`app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyViewModel.kt`
- 修改：`app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyScreen.kt`
- 修改：`app/src/androidTest/java/cn/echobird/emotionalmaster/MvpFlowTest.kt`

- [ ] **步骤 1：编写失败测试，确保在线失败不自动展示建议**

```kotlin
val replyViewModel = ReplyViewModel(
    serviceFactory = { ReplySuggestionService(FakeFailingProvider()) },
    providerLabelProvider = { "DeepSeek · deepseek-v4-pro" },
)
replyViewModel.start(messages)
replyViewModel.updateCurrentMessage("你好")
replyViewModel.generateSuggestions()

composeRule.onNodeWithText("在线生成失败").assertIsDisplayed()
composeRule.onNodeWithText("使用本地建议").assertIsDisplayed()
composeRule.onAllNodesWithText("复制").assertCountEquals(0)
composeRule.onNodeWithText("使用本地建议").performClick()
composeRule.onAllNodesWithText("复制").assertCountEquals(4)
```

- [ ] **步骤 2：运行相关测试确认失败**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest --no-daemon --console=plain
```

预期：FAIL，当前 ReplyViewModel 仍在无服务或失败时直接生成离线建议。

- [ ] **步骤 3：动态装配 DeepSeek Provider**

在 Activity 的工厂中仅于 IO 线程读取完整配置并构造：

```kotlin
OpenAiCompatibleProvider(
    endpoint = "https://api.deepseek.com/chat/completions",
    apiKeyProvider = { configuration.apiKey },
    model = configuration.modelId,
)
```

回复页标签只通过 `configuredModelId()` 获取，格式为 `DeepSeek · <model>`；配置缺失或解密失败时禁止在线调用并引导进入设置。Provider 构造不可把 Key 捕获到可打印的数据类或 UI state。

- [ ] **步骤 4：实现失败状态和显式离线动作**

给 `ReplyUiState` 增加 `onlineFailureMessage` 和 `hasOnlineProvider`。`generateSuggestions()` 只处理 `OnlineSuccess` 或设置错误；新增 `useOfflineSuggestions()`，只有用户点击后才写入 4 条建议并标记 `isOfflineFallback=true`。上下文预览继续在网络调用前显示供应商、模型和上传范围。

- [ ] **步骤 5：运行 app 单元与设备测试**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest --no-daemon --console=plain
```

预期：PASS；自动化仅使用 fake，不请求 DeepSeek 官方服务。

- [ ] **步骤 6：提交在线闭环**

```powershell
git add app/src/main/java/cn/echobird/emotionalmaster/MainActivity.kt app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyViewModel.kt app/src/main/java/cn/echobird/emotionalmaster/reply/ReplyScreen.kt app/src/androidTest/java/cn/echobird/emotionalmaster/MvpFlowTest.kt
git commit -m "feat(建议): 接通 DeepSeek 在线回复"
```

## 任务 7：安全文档、日志扫描与完整验收

**文件：**

- 修改：`docs/security/threat-model.md`
- 修改：`docs/development/status-and-next-steps.md`
- 修改：`README.md`

- [ ] **步骤 1：更新安全与状态文档**

威胁模型补充 API Key 的 Keystore 包装、SP 密文、DeepSeek `/models` 不上传聊天、Chat Completions 的供应商可见数据、Root 剩余风险和清除语义。状态文档把线上模型配置移入“已完成”，保留非 Root 采集为下一阶段；README 增加设置入口、默认模型和失败降级说明。

- [ ] **步骤 2：运行全量 JVM、lint 和构建验证**

```powershell
.\gradlew.bat test lintDebug :app:assembleDebug --no-daemon --console=plain
```

预期：BUILD SUCCESSFUL，无阻断级 lint。

- [ ] **步骤 3：运行完整设备测试**

```powershell
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain
```

预期：所有设置、导入、分析、回复和保存失败测试通过。

- [ ] **步骤 4：执行敏感日志扫描**

清空 logcat，运行含 `sk-test-log-secret` 和聊天样例的 fake 设备流程，再逐项计数：

```powershell
adb -s emulator-5554 logcat -c
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain
$log = adb -s emulator-5554 logcat -d
@('sk-test-log-secret','Authorization','你最近怎么总是这么忙','body-secret') | ForEach-Object {
    $count = ($log | Select-String -SimpleMatch $_ | Measure-Object).Count
    Write-Output "$_=$count"
}
```

预期：四项均为 `0`。不得在终端命令中放真实 Key。

- [ ] **步骤 5：检查差异和工作区**

```powershell
git diff --check
git status --short
git log --oneline -10
```

预期：只有三份预期文档尚未提交，代码与测试提交边界清晰。

- [ ] **步骤 6：提交文档与验收记录**

```powershell
git add README.md docs/security/threat-model.md docs/development/status-and-next-steps.md
git commit -m "docs(验收): 记录 DeepSeek 在线配置边界"
```

- [ ] **步骤 7：最终确认**

```powershell
git status --short --branch
git rev-list --count origin/feature/mvp-foundation..HEAD
```

预期：工作区干净；本地分支仅领先本阶段已审查提交。获得推送授权后执行 `git push origin feature/mvp-foundation`，禁止 force push。

## 完成定义

- API Key 只输入一次，重启应用后仍可用，SP 和日志中不存在明文。
- 模型列表来自 DeepSeek 官方 `/models`，默认优先 `deepseek-v4-pro`。
- 未验证候选配置不覆盖旧配置，清除操作同时移除配置字段与独立 Keystore alias。
- 回复确认页准确显示 DeepSeek、所选模型和脱敏上传范围。
- 在线失败和离线建议不会混淆，离线建议必须由用户明确选择。
- 自动化测试不访问真实 DeepSeek、不使用真实 Key、不消耗余额。
- 全量测试、lint、Debug APK、设备流程和敏感日志扫描均有当次成功证据。
- 第一版不开放自定义 endpoint，现有通用 `ModelProvider` 契约继续保留。
