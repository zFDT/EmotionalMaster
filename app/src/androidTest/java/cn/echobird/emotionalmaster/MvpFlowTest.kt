package cn.echobird.emotionalmaster

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.echobird.emotionalmaster.analysis.AnalysisViewModel
import cn.echobird.emotionalmaster.importing.ImportDocumentPreview
import cn.echobird.emotionalmaster.importing.ImportViewModel
import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import cn.echobird.emotionalmaster.llm.ModelProvider
import cn.echobird.emotionalmaster.llm.ModelProviderException
import cn.echobird.emotionalmaster.llm.AvailableModel
import cn.echobird.emotionalmaster.llm.ReplySuggestionService
import cn.echobird.emotionalmaster.reply.ReplyScreen
import cn.echobird.emotionalmaster.reply.OnlineReplyProvider
import cn.echobird.emotionalmaster.reply.ReplyViewModel
import cn.echobird.emotionalmaster.settings.DeepSeekSettingsController
import cn.echobird.emotionalmaster.settings.DeepSeekSettingsUiState
import cn.echobird.emotionalmaster.settings.SettingsSuccess
import cn.echobird.emotionalmaster.storage.DeepSeekConfiguration
import cn.echobird.emotionalmaster.storage.DeepSeekConfigurationStore
import cn.echobird.emotionalmaster.ui.EmotionalMasterTheme
import java.time.Instant
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MvpFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun importAnalyzePreviewAndCopySuggestion() {
        val importViewModel = ImportViewModel()
        val analysisViewModel = AnalysisViewModel()
        val replyViewModel = ReplyViewModel()

        composeRule.setContent {
            EmotionalMasterTheme {
                EmotionalMasterFlow(
                    importViewModel = importViewModel,
                    analysisViewModel = analysisViewModel,
                    replyViewModel = replyViewModel,
                    onChooseDocument = {
                        importViewModel.showPreview(samplePreview())
                    },
                )
            }
        }

        composeRule.onNodeWithText("导入聊天记录").assertIsDisplayed()
        capture("01-import-empty")
        composeRule.onNodeWithText("选择聊天文件").performScrollTo()
        capture("01b-import-empty-action")
        composeRule.onNodeWithText("选择聊天文件").performClick()
        composeRule.onNodeWithText("预览聊天记录").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("6 条消息").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("identity-mapping").performScrollTo().assertIsDisplayed()
        capture("02-import-preview")
        composeRule.onNodeWithText("交换双方").performClick()
        composeRule.onNodeWithText("交换双方").performClick()
        composeRule.onNodeWithText("确认导入").performScrollTo()
        capture("02b-import-confirm")
        composeRule.onNodeWithText("确认导入").performClick()

        composeRule.onNodeWithText("相处节奏").performScrollTo().assertIsDisplayed()
        capture("03-analysis")
        composeRule.onNodeWithText("获取回复建议").performScrollTo().performClick()
        capture("04-reply-input")
        composeRule.onNodeWithTag("current-message").performTextInput("你最近怎么总是这么忙？")
        composeRule.onNodeWithTag("current-message").performImeAction()
        composeRule.waitForIdle()
        pressBack()
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithText("相处节奏").fetchSemanticsNodes().isEmpty()) {
            pressBack()
        }
        composeRule.onNodeWithText("相处节奏").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("获取回复建议").performScrollTo().performClick()
        composeRule.onNodeWithTag("current-message").assertTextContains("你最近怎么总是这么忙？")
        composeRule.onNodeWithText("预览上传内容").performScrollTo().performClick()

        composeRule.onNodeWithText("将上传的上下文").assertIsDisplayed()
        composeRule.onNodeWithText("未配置 DeepSeek").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("context-current-message").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        capture("05-context-preview")
        composeRule.onNodeWithText("确认生成").performClick()
        composeRule.onNodeWithText("在线生成失败").assertIsDisplayed()
        composeRule.onNodeWithText("使用本地建议").performClick()
        composeRule.onNodeWithText("选择一种回复思路").performScrollTo().assertIsDisplayed()
        capture("06-suggestions")
        composeRule.onAllNodesWithText("复制").assertCountEquals(4)
        composeRule.onNodeWithTag("suggestions-end").performScrollTo()
        capture("06b-suggestions-bottom")
        composeRule.onAllNodesWithText("复制")[3].performScrollTo()
        composeRule.onAllNodesWithText("复制")[3].performClick()
        composeRule.onNodeWithText("已复制到剪贴板，请留意剪贴板隐私").assertIsDisplayed()
    }

    @Test
    fun failedPersistenceStaysOnPreviewAndCanRetry() {
        val importViewModel = ImportViewModel()
        composeRule.setContent {
            EmotionalMasterTheme {
                EmotionalMasterFlow(
                    importViewModel = importViewModel,
                    analysisViewModel = AnalysisViewModel(),
                    replyViewModel = ReplyViewModel(),
                    onChooseDocument = { importViewModel.showPreview(samplePreview()) },
                    onImportConfirmed = { _, completion ->
                        completion(Result.failure(IllegalStateException("设备存储暂时不可用")))
                    },
                )
            }
        }

        composeRule.onNodeWithText("选择聊天文件").performScrollTo().performClick()
        composeRule.onNodeWithText("确认导入").performScrollTo().performClick()
        composeRule.onNodeWithText("保存失败").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("设备存储暂时不可用").assertIsDisplayed()
        composeRule.onNodeWithText("确认导入").performScrollTo().assertIsDisplayed()
        capture("07-import-save-error")
    }

    @Test
    fun configureDeepSeekModelWithoutExposingApiKey() {
        val importViewModel = ImportViewModel()
        var settingsState by mutableStateOf(DeepSeekSettingsUiState())
        var discardCount = 0
        var verifiedApiKey: String? = null
        var savedModelId: String? = null

        composeRule.setContent {
            EmotionalMasterTheme {
                EmotionalMasterFlow(
                    importViewModel = importViewModel,
                    analysisViewModel = AnalysisViewModel(),
                    replyViewModel = ReplyViewModel(),
                    onChooseDocument = { importViewModel.showPreview(samplePreview()) },
                    settingsState = settingsState,
                    onVerifyDeepSeek = { apiKey ->
                        verifiedApiKey = apiKey
                        settingsState = settingsState.copy(
                            models = listOf(
                                AvailableModel("deepseek-v4-pro", isDeprecated = false),
                                AvailableModel("deepseek-chat", isDeprecated = true),
                            ),
                            selectedModelId = "deepseek-v4-pro",
                            canSave = true,
                        )
                    },
                    onSelectDeepSeekModel = { modelId ->
                        settingsState = settingsState.copy(selectedModelId = modelId)
                    },
                    onSaveDeepSeek = {
                        savedModelId = settingsState.selectedModelId
                        settingsState = settingsState.copy(
                            hasSavedConfiguration = true,
                            configuredModelId = settingsState.selectedModelId,
                            canSave = false,
                            success = SettingsSuccess.SAVED,
                        )
                    },
                    onConsumeDeepSeekSuccess = {
                        settingsState.success.also {
                            settingsState = settingsState.copy(success = null)
                        }
                    },
                    onDiscardDeepSeekCandidate = { discardCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("选择聊天文件").performScrollTo().performClick()
        composeRule.onNodeWithText("确认导入").performScrollTo().performClick()
        composeRule.onNodeWithText("获取回复建议").performScrollTo().performClick()
        composeRule.onNodeWithText("模型设置").performScrollTo().performClick()
        composeRule.onNodeWithText("DeepSeek 官方服务").assertIsDisplayed()
        composeRule.onNodeWithText("api.deepseek.com").assertIsDisplayed()
        composeRule.onNodeWithTag("deepseek-api-key").performTextInput("test-key")
        assertTrue(
            composeRule.onAllNodesWithText("test-key", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText("验证并获取模型").performScrollTo().performClick()
        assertEquals("test-key", verifiedApiKey)
        composeRule.onNodeWithText("deepseek-v4-pro").performScrollTo().performClick()
        composeRule.onNodeWithText("deepseek-chat（已弃用）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("保存并启用").performScrollTo().performClick()
        assertEquals("deepseek-v4-pro", savedModelId)
        composeRule.onNodeWithText("DeepSeek · deepseek-v4-pro").assertIsDisplayed()
        assertEquals(1, discardCount)
    }

    @Test
    fun editingVerifiedApiKeyRequiresVerifyingTheCurrentInputAgain() {
        val importViewModel = ImportViewModel()
        var settingsState by mutableStateOf(DeepSeekSettingsUiState())
        var verifiedCandidate: String? = null
        var savedCandidate: String? = null

        composeRule.setContent {
            EmotionalMasterTheme {
                EmotionalMasterFlow(
                    importViewModel = importViewModel,
                    analysisViewModel = AnalysisViewModel(),
                    replyViewModel = ReplyViewModel(),
                    onChooseDocument = { importViewModel.showPreview(samplePreview()) },
                    settingsState = settingsState,
                    onVerifyDeepSeek = { candidate ->
                        verifiedCandidate = candidate
                        settingsState = settingsState.copy(
                            models = listOf(AvailableModel("deepseek-v4-pro", isDeprecated = false)),
                            selectedModelId = "deepseek-v4-pro",
                            canSave = true,
                        )
                    },
                    onSaveDeepSeek = {
                        savedCandidate = verifiedCandidate
                    },
                    onDeepSeekCandidateEdited = {
                        verifiedCandidate = null
                        settingsState = settingsState.copy(
                            models = emptyList(),
                            selectedModelId = null,
                            canSave = false,
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("选择聊天文件").performScrollTo().performClick()
        composeRule.onNodeWithText("确认导入").performScrollTo().performClick()
        composeRule.onNodeWithText("获取回复建议").performScrollTo().performClick()
        composeRule.onNodeWithText("模型设置").performScrollTo().performClick()
        composeRule.onNodeWithTag("deepseek-api-key").performTextInput("candidate-a")
        composeRule.onNodeWithText("验证并获取模型").performScrollTo().performClick()
        composeRule.onNodeWithText("保存并启用").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("deepseek-api-key").performTextReplacement("candidate-b")

        composeRule.onAllNodesWithText("保存并启用").assertCountEquals(0)
        composeRule.onAllNodesWithText("deepseek-v4-pro").assertCountEquals(0)
        composeRule.onNodeWithText("验证并获取模型").performScrollTo().performClick()
        composeRule.onNodeWithText("保存并启用").performScrollTo().performClick()
        assertEquals("candidate-b", savedCandidate)
    }

    @Test
    fun backgroundSettingsSuccessIsConsumedWithoutReopeningNavigation() {
        val importViewModel = ImportViewModel()
        var settingsState by mutableStateOf(DeepSeekSettingsUiState())
        var consumeCount = 0

        composeRule.setContent {
            EmotionalMasterTheme {
                EmotionalMasterFlow(
                    importViewModel = importViewModel,
                    analysisViewModel = AnalysisViewModel(),
                    replyViewModel = ReplyViewModel(),
                    onChooseDocument = { importViewModel.showPreview(samplePreview()) },
                    settingsState = settingsState,
                    onVerifyDeepSeek = {
                        settingsState = settingsState.copy(
                            models = listOf(AvailableModel("deepseek-v4-pro", isDeprecated = false)),
                            selectedModelId = "deepseek-v4-pro",
                            canSave = true,
                        )
                    },
                    onSaveDeepSeek = {
                        settingsState = settingsState.copy(isLoading = true, canSave = false)
                    },
                    onClearDeepSeek = {
                        settingsState = settingsState.copy(isLoading = true)
                    },
                    onConsumeDeepSeekSuccess = {
                        settingsState.success?.also {
                            consumeCount += 1
                            settingsState = settingsState.copy(success = null)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("选择聊天文件").performScrollTo().performClick()
        composeRule.onNodeWithText("确认导入").performScrollTo().performClick()
        composeRule.onNodeWithText("获取回复建议").performScrollTo().performClick()
        composeRule.onNodeWithText("模型设置").performScrollTo().performClick()
        composeRule.onNodeWithTag("deepseek-api-key").performTextInput("test-key")
        composeRule.onNodeWithText("验证并获取模型").performScrollTo().performClick()
        composeRule.onNodeWithText("保存并启用").performScrollTo().performClick()
        composeRule.onNodeWithText("保存并启用").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performScrollTo().performClick()

        composeRule.runOnIdle {
            settingsState = settingsState.copy(
                hasSavedConfiguration = true,
                configuredModelId = "deepseek-v4-pro",
                selectedModelId = "deepseek-v4-pro",
                isLoading = false,
                success = SettingsSuccess.SAVED,
            )
        }
        composeRule.onNodeWithText("DeepSeek · deepseek-v4-pro").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, consumeCount) }
        composeRule.onNodeWithText("模型设置").performScrollTo().performClick()
        composeRule.onNodeWithText("DeepSeek 官方服务").assertIsDisplayed()

        composeRule.onNodeWithText("清除已保存配置").performScrollTo().performClick()
        composeRule.onNodeWithText("清除已保存配置").assertIsDisplayed()
        composeRule.onNodeWithText("返回").performScrollTo().performClick()
        composeRule.runOnIdle {
            settingsState = DeepSeekSettingsUiState(success = SettingsSuccess.CLEARED)
        }
        composeRule.onNodeWithText("未配置 DeepSeek").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(2, consumeCount) }
        composeRule.onNodeWithText("模型设置").performScrollTo().performClick()
        composeRule.onNodeWithText("DeepSeek 官方服务").assertIsDisplayed()
    }

    @Test
    fun onlineFailureKeepsContextPreviewAndMessage() {
        val viewModel = ReplyViewModel(
            providerResolver = {
                OnlineReplyProvider(
                    label = "DeepSeek · deepseek-v4-pro",
                    service = ReplySuggestionService(
                        ModelProvider {
                            throw ModelProviderException.Timeout(SocketTimeoutException("secret"))
                        },
                    ),
                )
            },
        )
        viewModel.start(samplePreview().messages)
        viewModel.updateCurrentMessage("你最近怎么总是这么忙？")

        composeRule.setContent {
            EmotionalMasterTheme {
                ReplyScreen(
                    state = viewModel.uiState,
                    onCurrentMessageChange = viewModel::updateCurrentMessage,
                    onPreviewContext = viewModel::showContextPreview,
                    onDismissContextPreview = viewModel::dismissContextPreview,
                    onGenerateSuggestions = viewModel::generateSuggestions,
                    onUseOfflineSuggestions = viewModel::useOfflineSuggestions,
                    onBack = {},
                )
            }
        }
        composeRule.waitUntil(5_000) { !viewModel.uiState.isResolvingOnlineProvider }
        composeRule.runOnIdle(viewModel::showContextPreview)

        composeRule.onNodeWithText("确认生成").performClick()
        composeRule.waitUntil(5_000) { !viewModel.uiState.isGenerating }

        composeRule.onNodeWithText("将上传的上下文").assertIsDisplayed()
        composeRule.onNodeWithText("在线生成失败").assertIsDisplayed()
        composeRule.onNodeWithText("连接 DeepSeek 超时").assertIsDisplayed()
        composeRule.onNodeWithText("使用本地建议").assertIsDisplayed()
        composeRule.onAllNodesWithText("复制").assertCountEquals(0)
        composeRule.onNodeWithText("使用本地建议").performClick()
        composeRule.onAllNodesWithText("复制").assertCountEquals(4)
        assertFalse(viewModel.uiState.isGenerating)
        assertFalse(viewModel.uiState.showContextPreview)
        assertEquals(4, viewModel.uiState.suggestions.size)
        assertEquals("你最近怎么总是这么忙？", viewModel.uiState.currentMessage)
    }

    @Test
    fun configurationChangeUsesNewestModelForNextRequest() {
        var modelId = "deepseek-v4-pro"
        val requestedModels = mutableListOf<String>()
        val viewModel = ReplyViewModel(
            providerResolver = {
                val requestModel = modelId
                OnlineReplyProvider(
                    label = "DeepSeek · $requestModel",
                    service = ReplySuggestionService(
                        ModelProvider {
                            requestedModels += requestModel
                            successfulSuggestions()
                        },
                    ),
                )
            },
        )
        viewModel.start(samplePreview().messages)
        composeRule.setContent {
            EmotionalMasterTheme {
                ReplyScreen(
                    state = viewModel.uiState,
                    onCurrentMessageChange = viewModel::updateCurrentMessage,
                    onPreviewContext = viewModel::showContextPreview,
                    onDismissContextPreview = viewModel::dismissContextPreview,
                    onGenerateSuggestions = viewModel::generateSuggestions,
                    onUseOfflineSuggestions = viewModel::useOfflineSuggestions,
                    onBack = {},
                )
            }
        }
        composeRule.waitUntil(5_000) { !viewModel.uiState.isResolvingOnlineProvider }
        composeRule.runOnIdle {
            viewModel.updateCurrentMessage("第一次")
            viewModel.showContextPreview()
            viewModel.generateSuggestions()
        }
        composeRule.waitUntil(5_000) { !viewModel.uiState.isGenerating }

        modelId = "deepseek-v4-flash"
        viewModel.refreshOnlineProvider()
        composeRule.waitUntil(5_000) {
            viewModel.uiState.providerLabel == "DeepSeek · deepseek-v4-flash"
        }
        composeRule.runOnIdle {
            viewModel.updateCurrentMessage("第二次")
            viewModel.showContextPreview()
            viewModel.generateSuggestions()
        }
        composeRule.waitUntil(5_000) {
            !viewModel.uiState.isGenerating && requestedModels.size == 2
        }

        assertEquals(listOf("deepseek-v4-pro", "deepseek-v4-flash"), requestedModels)
    }

    @Test
    fun savingNewApiKeyForSameModelRefreshesProviderBeforeNextRequest() {
        var savedConfiguration = DeepSeekConfiguration("deepseek-v4-pro", "old-test-key")
        val configurationStore = object : DeepSeekConfigurationStore {
            override fun load(): DeepSeekConfiguration = savedConfiguration

            override fun configuredModelId(): String = savedConfiguration.modelId

            override fun save(configuration: DeepSeekConfiguration) {
                savedConfiguration = configuration
            }

            override fun clear() {
                error("此测试不应清除配置")
            }
        }
        val controller = DeepSeekSettingsController(
            configurationStore = configurationStore,
            modelCatalog = { listOf(AvailableModel("deepseek-v4-pro", isDeprecated = false)) },
        ).also(DeepSeekSettingsController::initializeMetadata)
        var settingsState by mutableStateOf(controller.state)
        val requestedApiKeys = mutableListOf<String>()
        val replyViewModel = ReplyViewModel(
            providerResolver = {
                val configuration = configurationStore.load()
                OnlineReplyProvider(
                    label = "DeepSeek · ${configuration.modelId}",
                    service = ReplySuggestionService(
                        ModelProvider {
                            requestedApiKeys += configuration.apiKey
                            successfulSuggestions()
                        },
                    ),
                )
            },
        )
        val importViewModel = ImportViewModel()

        composeRule.setContent {
            EmotionalMasterTheme {
                EmotionalMasterFlow(
                    importViewModel = importViewModel,
                    analysisViewModel = AnalysisViewModel(),
                    replyViewModel = replyViewModel,
                    onChooseDocument = { importViewModel.showPreview(samplePreview()) },
                    settingsState = settingsState,
                    onVerifyDeepSeek = { apiKey ->
                        controller.verifyAndLoadModels(apiKey)
                        settingsState = controller.state
                    },
                    onSelectDeepSeekModel = { modelId ->
                        controller.selectModel(modelId)
                        settingsState = controller.state
                    },
                    onSaveDeepSeek = {
                        controller.saveVerifiedConfiguration()
                        settingsState = controller.state
                    },
                    onConsumeDeepSeekSuccess = {
                        controller.consumeSuccess().also {
                            settingsState = controller.state
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("选择聊天文件").performScrollTo().performClick()
        composeRule.onNodeWithText("确认导入").performScrollTo().performClick()
        composeRule.onNodeWithText("获取回复建议").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { !replyViewModel.uiState.isResolvingOnlineProvider }
        composeRule.onNodeWithText("模型设置").performScrollTo().performClick()
        composeRule.onNodeWithTag("deepseek-api-key").performTextInput("new-test-key")
        composeRule.onNodeWithText("验证并获取模型").performScrollTo().performClick()
        composeRule.onNodeWithText("保存并启用").performScrollTo().performClick()
        composeRule.onNodeWithTag("current-message").performTextInput("使用新配置")
        composeRule.onNodeWithText("预览上传内容").performScrollTo().performClick()
        composeRule.onNodeWithText("确认生成").performClick()
        composeRule.waitUntil(5_000) {
            !replyViewModel.uiState.isGenerating && requestedApiKeys.isNotEmpty()
        }

        assertEquals(listOf("new-test-key"), requestedApiKeys)
        assertFalse(settingsState.toString().contains("new-test-key"))
    }

    @Test
    fun clearPartialFailureRefreshesAndRemovesOnlineProvider() {
        var savedConfiguration: DeepSeekConfiguration? =
            DeepSeekConfiguration("deepseek-v4-pro", "old-test-key")
        val configurationStore = object : DeepSeekConfigurationStore {
            override fun load(): DeepSeekConfiguration? = savedConfiguration

            override fun configuredModelId(): String? = savedConfiguration?.modelId

            override fun save(configuration: DeepSeekConfiguration) {
                error("此测试不应保存配置")
            }

            override fun clear() {
                savedConfiguration = null
                throw IllegalStateException("clear-test-failure")
            }
        }
        val controller = DeepSeekSettingsController(
            configurationStore = configurationStore,
            modelCatalog = { emptyList() },
        ).also(DeepSeekSettingsController::initializeMetadata)
        var settingsState by mutableStateOf(controller.state)
        var providerResolverCalls = 0
        val replyViewModel = ReplyViewModel(
            providerResolver = {
                providerResolverCalls += 1
                configurationStore.load()?.let { configuration ->
                    OnlineReplyProvider(
                        label = "DeepSeek · ${configuration.modelId}",
                        service = ReplySuggestionService(
                            ModelProvider { successfulSuggestions() },
                        ),
                    )
                }
            },
        )
        val importViewModel = ImportViewModel()

        composeRule.setContent {
            EmotionalMasterTheme {
                EmotionalMasterFlow(
                    importViewModel = importViewModel,
                    analysisViewModel = AnalysisViewModel(),
                    replyViewModel = replyViewModel,
                    onChooseDocument = { importViewModel.showPreview(samplePreview()) },
                    settingsState = settingsState,
                    onClearDeepSeek = {
                        controller.clearConfiguration()
                        settingsState = controller.state
                    },
                )
            }
        }

        composeRule.onNodeWithText("选择聊天文件").performScrollTo().performClick()
        composeRule.onNodeWithText("确认导入").performScrollTo().performClick()
        composeRule.onNodeWithText("获取回复建议").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            !replyViewModel.uiState.isResolvingOnlineProvider &&
                replyViewModel.uiState.hasOnlineProvider
        }
        val resolverCallsBeforeClear = providerResolverCalls
        composeRule.onNodeWithText("模型设置").performScrollTo().performClick()
        composeRule.onNodeWithText("清除已保存配置").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            providerResolverCalls > resolverCallsBeforeClear &&
                !replyViewModel.uiState.isResolvingOnlineProvider
        }

        assertFalse(replyViewModel.uiState.hasOnlineProvider)
        assertEquals("未配置 DeepSeek", replyViewModel.uiState.providerLabel)
        assertFalse(settingsState.hasSavedConfiguration)
        assertEquals(null, settingsState.configuredModelId)
        assertFalse(settingsState.toString().contains("old-test-key"))
    }

    @Test
    fun unconfiguredProviderDoesNotCreateOnlineServiceOrLeakFailureSecret() {
        var providerResolverCalls = 0
        val secret = "must-not-leak-secret"
        val viewModel = ReplyViewModel(
            providerResolver = {
                providerResolverCalls += 1
                null
            },
        )
        viewModel.start(samplePreview().messages)
        composeRule.setContent {
            androidx.compose.material3.Text(viewModel.uiState.providerLabel)
        }
        composeRule.waitUntil(5_000) { !viewModel.uiState.isResolvingOnlineProvider }
        composeRule.runOnIdle {
            viewModel.updateCurrentMessage("你好")
            viewModel.showContextPreview()
            viewModel.generateSuggestions()
        }

        assertEquals(1, providerResolverCalls)
        assertEquals("尚未配置 DeepSeek，请先打开模型设置", viewModel.uiState.onlineFailureMessage)
        assertFalse(viewModel.uiState.toString().contains(secret))
    }

    @Test
    fun damagedConfigurationErrorDoesNotExposeSecret() {
        val secret = "damaged-store-secret"
        val viewModel = ReplyViewModel(
            providerResolver = { throw IllegalStateException(secret) },
        )
        viewModel.start(samplePreview().messages)
        composeRule.setContent {
            androidx.compose.material3.Text(viewModel.uiState.providerLabel)
        }
        composeRule.waitUntil(5_000) { !viewModel.uiState.isResolvingOnlineProvider }
        composeRule.runOnIdle {
            viewModel.updateCurrentMessage("你好")
            viewModel.showContextPreview()
            viewModel.generateSuggestions()
        }
        composeRule.waitUntil(5_000) { !viewModel.uiState.isGenerating }

        assertEquals(
            "DeepSeek 配置不可用，请重新打开模型设置",
            viewModel.uiState.onlineFailureMessage,
        )
        assertFalse(viewModel.uiState.toString().contains(secret))
    }

    @Test
    fun resolvingConfigurationCannotBeMistakenForMissingConfiguration() {
        val releaseResolution = CountDownLatch(1)
        var onlineCalls = 0
        val viewModel = ReplyViewModel(
            providerResolver = {
                check(releaseResolution.await(5, TimeUnit.SECONDS))
                OnlineReplyProvider(
                    label = "DeepSeek · deepseek-v4-pro",
                    service = ReplySuggestionService(
                        ModelProvider {
                            onlineCalls += 1
                            successfulSuggestions()
                        },
                    ),
                )
            },
        )
        viewModel.start(samplePreview().messages)
        viewModel.updateCurrentMessage("你好")

        assertTrue(viewModel.uiState.isResolvingOnlineProvider)
        viewModel.showContextPreview()
        viewModel.generateSuggestions()
        assertFalse(viewModel.uiState.showContextPreview)
        assertEquals(null, viewModel.uiState.onlineFailureMessage)
        assertEquals(0, onlineCalls)

        releaseResolution.countDown()
        composeRule.setContent {
            androidx.compose.material3.Text(viewModel.uiState.providerLabel)
        }
        composeRule.waitUntil(5_000) { !viewModel.uiState.isResolvingOnlineProvider }
        viewModel.showContextPreview()
        viewModel.generateSuggestions()
        composeRule.waitUntil(5_000) { !viewModel.uiState.isGenerating }

        assertEquals("DeepSeek · deepseek-v4-pro", viewModel.uiState.providerLabel)
        assertEquals(1, onlineCalls)
    }

    @Test
    fun staleProviderResolutionCannotOverwriteNewGeneration() {
        val firstResolutionStarted = CountDownLatch(1)
        val releaseFirstResolution = CountDownLatch(1)
        val firstResolutionFinished = CountDownLatch(1)
        val resolutionCount = AtomicInteger()
        val viewModel = ReplyViewModel(
            providerResolver = {
                if (resolutionCount.incrementAndGet() == 1) {
                    firstResolutionStarted.countDown()
                    check(releaseFirstResolution.await(5, TimeUnit.SECONDS))
                    firstResolutionFinished.countDown()
                    OnlineReplyProvider(
                        "DeepSeek · stale-model",
                        ReplySuggestionService(ModelProvider { successfulSuggestions() }),
                    )
                } else {
                    OnlineReplyProvider(
                        "DeepSeek · newest-model",
                        ReplySuggestionService(ModelProvider { successfulSuggestions() }),
                    )
                }
            },
        )
        viewModel.start(samplePreview().messages)
        assertTrue(firstResolutionStarted.await(5, TimeUnit.SECONDS))

        viewModel.refreshOnlineProvider()
        composeRule.setContent {
            androidx.compose.material3.Text(viewModel.uiState.providerLabel)
        }
        composeRule.waitUntil(5_000) {
            viewModel.uiState.providerLabel == "DeepSeek · newest-model"
        }
        releaseFirstResolution.countDown()
        assertTrue(firstResolutionFinished.await(5, TimeUnit.SECONDS))
        composeRule.waitForIdle()

        assertEquals("DeepSeek · newest-model", viewModel.uiState.providerLabel)
        assertFalse(viewModel.uiState.isResolvingOnlineProvider)
    }

    @Test
    fun dismissInvalidatesRunningOnlineRequest() {
        verifyRunningRequestInvalidated(ReplyViewModel::dismissContextPreview)
    }

    @Test
    fun inputChangeInvalidatesRunningOnlineRequest() {
        verifyRunningRequestInvalidated { it.updateCurrentMessage("新的输入") }
    }

    @Test
    fun providerRefreshInvalidatesRunningOnlineRequest() {
        verifyRunningRequestInvalidated(ReplyViewModel::refreshOnlineProvider)
    }

    private fun verifyRunningRequestInvalidated(invalidate: (ReplyViewModel) -> Unit) {
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val requestFinished = CountDownLatch(1)
        val viewModel = ReplyViewModel(
            providerResolver = {
                OnlineReplyProvider(
                    "DeepSeek · deepseek-v4-pro",
                    ReplySuggestionService(
                        ModelProvider {
                            requestStarted.countDown()
                            check(releaseRequest.await(5, TimeUnit.SECONDS))
                            requestFinished.countDown()
                            successfulSuggestions()
                        },
                    ),
                )
            },
        )
        viewModel.start(samplePreview().messages)
        composeRule.setContent {
            androidx.compose.material3.Text(viewModel.uiState.providerLabel)
        }
        composeRule.waitUntil(5_000) { !viewModel.uiState.isResolvingOnlineProvider }
        composeRule.runOnIdle {
            viewModel.updateCurrentMessage("原始输入")
            viewModel.showContextPreview()
            viewModel.generateSuggestions()
        }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

        composeRule.runOnIdle { invalidate(viewModel) }
        releaseRequest.countDown()
        assertTrue(requestFinished.await(5, TimeUnit.SECONDS))
        composeRule.waitForIdle()

        assertTrue(viewModel.uiState.suggestions.isEmpty())
        assertFalse(viewModel.uiState.isGenerating)
        assertEquals(null, viewModel.uiState.onlineFailureMessage)
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "visual-qa").apply { mkdirs() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().recycle()
        instrumentation.waitForIdleSync()
        File(directory, "$name.png").outputStream().use { output ->
            instrumentation.uiAutomation.takeScreenshot().compress(
                Bitmap.CompressFormat.PNG,
                100,
                output,
            )
        }
    }

    private fun samplePreview() = ImportDocumentPreview(
        fileName = "sample.json",
        fileSizeBytes = 1_024,
        participantIds = listOf("me", "them"),
        messages = listOf(
            message("1", "2026-07-20T09:00:00Z", MessageDirection.OUTGOING, "早上好"),
            message("2", "2026-07-20T09:02:00Z", MessageDirection.INCOMING, "早呀"),
            message("3", "2026-07-20T12:00:00Z", MessageDirection.INCOMING, "午饭了吗"),
            message("4", "2026-07-20T12:03:00Z", MessageDirection.OUTGOING, "正准备去"),
            message("5", "2026-07-20T21:00:00Z", MessageDirection.OUTGOING, "今天忙完啦"),
            message("6", "2026-07-20T21:04:00Z", MessageDirection.INCOMING, "辛苦了"),
        ),
        issues = emptyList(),
    )

    private fun message(
        id: String,
        sentAt: String,
        direction: MessageDirection,
        text: String,
    ) = ChatMessage(
        stableSourceId = id,
        sentAt = Instant.parse(sentAt),
        direction = direction,
        text = text,
    )

    private fun successfulSuggestions(): String = """
        {"suggestions":[
        {"text":"一","strategy":"甲","intent":"稳住","risk":"低","applicableWhen":"现在"},
        {"text":"二","strategy":"乙","intent":"澄清","risk":"低","applicableWhen":"现在"},
        {"text":"三","strategy":"丙","intent":"共情","risk":"低","applicableWhen":"现在"},
        {"text":"四","strategy":"丁","intent":"边界","risk":"低","applicableWhen":"现在"}
        ]}
    """.trimIndent()
}
