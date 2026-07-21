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
import cn.echobird.emotionalmaster.analysis.AnalysisViewModel
import cn.echobird.emotionalmaster.importing.ImportDocumentPreview
import cn.echobird.emotionalmaster.importing.ImportViewModel
import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import cn.echobird.emotionalmaster.reply.ReplyViewModel
import cn.echobird.emotionalmaster.ui.EmotionalMasterTheme
import java.time.Instant
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.pressBack
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
        composeRule.onNodeWithText("相处节奏").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("获取回复建议").performScrollTo().performClick()
        composeRule.onNodeWithTag("current-message").assertTextContains("你最近怎么总是这么忙？")
        composeRule.onNodeWithText("预览上传内容").performScrollTo().performClick()

        composeRule.onNodeWithText("将上传的上下文").assertIsDisplayed()
        composeRule.onNodeWithText("未配置（将使用本地降级）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("context-current-message").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        capture("05-context-preview")
        composeRule.onNodeWithText("确认生成").performClick()

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
}
