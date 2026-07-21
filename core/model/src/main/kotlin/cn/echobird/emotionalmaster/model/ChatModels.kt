package cn.echobird.emotionalmaster.model

import java.time.Instant

enum class MessageDirection {
    INCOMING,
    OUTGOING,
}

data class AttachmentReference(
    val stableSourceId: String,
    val mediaType: String,
) {
    init {
        require(stableSourceId.isNotBlank()) { "附件必须包含稳定来源标识" }
        require(mediaType.isNotBlank()) { "附件必须包含媒体类型" }
    }
}

data class ChatMessage(
    val stableSourceId: String,
    val sentAt: Instant,
    val direction: MessageDirection,
    val text: String? = null,
    val attachments: List<AttachmentReference> = emptyList(),
) {
    init {
        require(stableSourceId.isNotBlank()) { "消息必须包含稳定来源标识" }
        require(!text.isNullOrBlank() || attachments.isNotEmpty()) {
            "消息必须包含正文或附件之一"
        }
    }
}
