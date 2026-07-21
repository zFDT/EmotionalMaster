package cn.echobird.emotionalmaster.analytics

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSegmenterTest {
    @Test
    fun `消息按时间排序且仅在间隔严格超过阈值时切分`() {
        val messages = listOf(
            message("third", "2026-01-01T00:10:01Z"),
            message("first", "2026-01-01T00:00:00Z"),
            message("second", "2026-01-01T00:05:00Z"),
        )

        val sessions = SessionSegmenter(Duration.ofMinutes(5)).segment(messages)

        assertEquals(2, sessions.size)
        assertEquals(listOf("first", "second"), sessions[0].messages.map { it.stableSourceId })
        assertEquals(listOf("third"), sessions[1].messages.map { it.stableSourceId })
    }

    private fun message(id: String, sentAt: String) = ChatMessage(
        stableSourceId = id,
        sentAt = Instant.parse(sentAt),
        direction = MessageDirection.INCOMING,
        text = id,
    )
}
