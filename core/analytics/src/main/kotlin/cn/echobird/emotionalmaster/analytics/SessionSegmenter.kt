package cn.echobird.emotionalmaster.analytics

import cn.echobird.emotionalmaster.model.ChatMessage
import java.time.Duration

data class ConversationSession(
    val messages: List<ChatMessage>,
)

class SessionSegmenter(
    private val sessionGap: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!sessionGap.isNegative && !sessionGap.isZero) { "会话间隔必须大于零" }
    }

    fun segment(messages: List<ChatMessage>): List<ConversationSession> {
        val sorted = messages.sortedWith(compareBy(ChatMessage::sentAt, ChatMessage::stableSourceId))
        if (sorted.isEmpty()) return emptyList()

        val sessions = mutableListOf<MutableList<ChatMessage>>()
        sorted.forEach { message ->
            val current = sessions.lastOrNull()
            val previous = current?.lastOrNull()
            if (previous == null || Duration.between(previous.sentAt, message.sentAt) > sessionGap) {
                sessions += mutableListOf(message)
            } else {
                current += message
            }
        }
        return sessions.map { ConversationSession(it.toList()) }
    }
}
