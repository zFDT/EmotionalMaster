package cn.echobird.emotionalmaster.llm

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection

enum class ConversationRole {
    SELF,
    PEER,
}

data class RedactedMessage(
    val role: ConversationRole,
    val text: String,
)

data class RedactedConversation(
    val messages: List<RedactedMessage>,
)

class ContextRedactor(
    private val maxRecentMessages: Int = 20,
) {
    init {
        require(maxRecentMessages > 0) { "最小上下文消息数必须大于零" }
    }

    fun redact(messages: List<ChatMessage>): RedactedConversation {
        val recentMessages = messages
            .sortedWith(compareBy(ChatMessage::sentAt, ChatMessage::stableSourceId))
            .takeLast(maxRecentMessages)
            .map { message ->
                RedactedMessage(
                    role = if (message.direction == MessageDirection.OUTGOING) {
                        ConversationRole.SELF
                    } else {
                        ConversationRole.PEER
                    },
                    text = redactText(message.text ?: "[附件消息]"),
                )
            }
        return RedactedConversation(recentMessages)
    }

    private fun redactText(text: String): String = text
        .replace(URL_PATTERN, "[链接]")
        .replace(EMAIL_PATTERN, "[邮箱]")
        .replace(PHONE_PATTERN, "[手机号]")

    private companion object {
        val URL_PATTERN = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        val EMAIL_PATTERN = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        val PHONE_PATTERN = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    }
}
