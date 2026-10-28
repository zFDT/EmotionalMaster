package cn.echobird.emotionalmaster.importer

import cn.echobird.emotionalmaster.model.ChatMessage
import java.security.MessageDigest

data class DeduplicationResult(
    val messages: List<ChatMessage>,
    val contentHashes: Set<String>,
)

class MessageDeduplicator {
    fun deduplicate(
        messages: List<ChatMessage>,
        existingHashes: Set<String> = emptySet(),
    ): DeduplicationResult {
        val hashes = existingHashes.toMutableSet()
        val accepted = messages.filter { message ->
            hashes.add(contentHash(message))
        }

        return DeduplicationResult(
            messages = accepted,
            contentHashes = hashes.toSet(),
        )
    }

    private fun contentHash(message: ChatMessage): String {
        val canonical = buildString {
            append(message.direction.name)
            append('\u001f')
            append(message.sentAt)
            append('\u001f')
            append(message.text.orEmpty())
            message.attachments.forEach { attachment ->
                append('\u001f')
                append(attachment.stableSourceId)
                append('\u001f')
                append(attachment.mediaType)
            }
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
