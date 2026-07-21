package cn.echobird.emotionalmaster.importer

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection

enum class ChatImportFormat {
    JSON,
    JSONL,
}

data class IdentityMapping(
    val selfParticipantId: String,
    val peerParticipantId: String,
) {
    init {
        require(selfParticipantId.isNotBlank()) { "本人身份标识不能为空" }
        require(peerParticipantId.isNotBlank()) { "对方身份标识不能为空" }
        require(selfParticipantId != peerParticipantId) { "双方身份标识不能相同" }
    }

    fun directionOf(participantId: String): MessageDirection? = when (participantId) {
        selfParticipantId -> MessageDirection.OUTGOING
        peerParticipantId -> MessageDirection.INCOMING
        else -> null
    }
}

enum class ImportErrorCode {
    MALFORMED_JSON,
    UNKNOWN_PARTICIPANT,
    INVALID_MESSAGE,
}

data class ImportError(
    val code: ImportErrorCode,
    val lineNumber: Int? = null,
    val summary: String,
)

data class StagedImportBatch(
    val sourceHash: String,
    val parsedMessages: List<ChatMessage>,
    val errors: List<ImportError>,
) {
    val canCommit: Boolean = errors.isEmpty()
    val readyMessages: List<ChatMessage> = if (canCommit) parsedMessages else emptyList()
}

internal data class RawImportMessage(
    val id: String,
    val senderId: String,
    val sentAt: String,
    val text: String?,
    val attachments: List<RawImportAttachment>,
)

internal data class RawImportAttachment(
    val sourceId: String,
    val mediaType: String,
)

internal class ImportValidationException(
    val code: ImportErrorCode,
    message: String,
) : IllegalArgumentException(message)
