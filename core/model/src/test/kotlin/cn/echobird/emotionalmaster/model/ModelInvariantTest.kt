package cn.echobird.emotionalmaster.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelInvariantTest {
    @Test
    fun `消息必须包含稳定来源标识`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatMessage(
                stableSourceId = " ",
                sentAt = Instant.parse("2026-07-20T10:00:00Z"),
                direction = MessageDirection.INCOMING,
                text = "你好",
            )
        }
    }

    @Test
    fun `消息必须包含正文或附件之一`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatMessage(
                stableSourceId = "wechat:message:1",
                sentAt = Instant.parse("2026-07-20T10:00:00Z"),
                direction = MessageDirection.OUTGOING,
                text = " ",
                attachments = emptyList(),
            )
        }
    }

    @Test
    fun `画像假设必须包含支持证据和反向证据`() {
        val evidence = Evidence(
            id = "evidence:1",
            sourceReference = "wechat:message:1",
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProfileHypothesis(
                conclusion = "对方更偏好直接表达",
                confidence = Confidence.of(0.7),
                supportingEvidence = listOf(evidence),
                contradictingEvidence = emptyList(),
                updatedAt = Instant.parse("2026-07-20T10:00:00Z"),
            )
        }
    }

    @Test
    fun `置信度必须位于零到一之间`() {
        assertThrows(IllegalArgumentException::class.java) {
            Confidence.of(1.01)
        }
    }

    @Test
    fun `回复集合只能包含四到六条建议`() {
        val suggestions = (1..3).map { index -> suggestion(index) }

        assertThrows(IllegalArgumentException::class.java) {
            ReplySuggestionSet.of(suggestions)
        }
    }

    @Test
    fun `回复集合不允许重复策略`() {
        val suggestions = (1..4).map { index ->
            suggestion(index, strategy = "稳妥推进")
        }

        assertThrows(IllegalArgumentException::class.java) {
            ReplySuggestionSet.of(suggestions)
        }
    }

    @Test
    fun `平台适配器只暴露四类职责`() {
        assertEquals(
            setOf("detectCapabilities", "captureAuthorized", "normalize", "diagnose"),
            PlatformAdapter::class.java.declaredMethods.map { it.name }.toSet(),
        )
    }

    private fun suggestion(
        index: Int,
        strategy: String = "策略 $index",
    ) = ReplySuggestion(
        text = "回复 $index",
        strategy = strategy,
        intent = "意图 $index",
        risk = "风险 $index",
        applicableWhen = "条件 $index",
    )
}
