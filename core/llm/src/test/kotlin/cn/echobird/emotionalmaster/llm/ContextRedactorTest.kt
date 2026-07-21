package cn.echobird.emotionalmaster.llm

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContextRedactorTest {
    @Test
    fun `只保留最近消息和回复必需字段并脱敏联系方式`() {
        val messages = listOf(
            message("discarded-id", "较早消息", MessageDirection.INCOMING, 0),
            message("private-id-1", "邮箱 me@example.com，电话 13800138000", MessageDirection.OUTGOING, 1),
            message("private-id-2", "链接 https://example.com/a?q=secret", MessageDirection.INCOMING, 2),
        )

        val redacted = ContextRedactor(maxRecentMessages = 2).redact(messages)

        assertEquals(
            listOf(
                RedactedMessage(ConversationRole.SELF, "邮箱 [邮箱]，电话 [手机号]"),
                RedactedMessage(ConversationRole.PEER, "链接 [链接]"),
            ),
            redacted.messages,
        )
        val serialized = redacted.toString()
        listOf("discarded-id", "private-id", "me@example.com", "13800138000", "secret").forEach {
            assertFalse(serialized.contains(it))
        }
    }

    private fun message(
        id: String,
        text: String,
        direction: MessageDirection,
        minute: Long,
    ) = ChatMessage(
        stableSourceId = id,
        sentAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60),
        direction = direction,
        text = text,
    )
}
