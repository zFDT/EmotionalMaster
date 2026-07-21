package cn.echobird.emotionalmaster.importer

import cn.echobird.emotionalmaster.model.AttachmentReference
import cn.echobird.emotionalmaster.model.ChatMessage
import java.time.OffsetDateTime

class ImportValidator {
    internal fun validate(
        raw: RawImportMessage,
        identityMapping: IdentityMapping,
    ): ChatMessage {
        val direction = identityMapping.directionOf(raw.senderId)
            ?: throw ImportValidationException(
                ImportErrorCode.UNKNOWN_PARTICIPANT,
                "消息参与者未映射",
            )

        return try {
            ChatMessage(
                stableSourceId = raw.id,
                sentAt = OffsetDateTime.parse(raw.sentAt).toInstant(),
                direction = direction,
                text = raw.text,
                attachments = raw.attachments.map { attachment ->
                    AttachmentReference(
                        stableSourceId = attachment.sourceId,
                        mediaType = attachment.mediaType,
                    )
                },
            )
        } catch (error: IllegalArgumentException) {
            throw ImportValidationException(
                ImportErrorCode.INVALID_MESSAGE,
                error.message ?: "消息字段无效",
            )
        }
    }
}
