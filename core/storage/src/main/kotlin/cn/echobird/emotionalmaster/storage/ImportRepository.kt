package cn.echobird.emotionalmaster.storage

import cn.echobird.emotionalmaster.importer.StagedImportBatch

enum class ImportOutcome {
    IMPORTED,
    ALREADY_IMPORTED,
    REJECTED,
}

class ImportRepository(
    database: EmotionalMasterDatabase,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.chatDao()

    fun importBatch(personId: String, stagedBatch: StagedImportBatch): ImportOutcome {
        require(personId.isNotBlank()) { "联系人标识不能为空" }
        if (!stagedBatch.canCommit) return ImportOutcome.REJECTED

        val messages = stagedBatch.readyMessages.map { message ->
            MessageEntity(
                stableSourceId = message.stableSourceId,
                personId = personId,
                importSourceHash = stagedBatch.sourceHash,
                sentAtEpochMillis = message.sentAt.toEpochMilli(),
                direction = message.direction.name,
                text = message.text,
            )
        }
        val attachments = stagedBatch.readyMessages.flatMap { message ->
            message.attachments.map { attachment ->
                MessageAttachmentEntity(
                    messageSourceId = message.stableSourceId,
                    attachmentSourceId = attachment.stableSourceId,
                    mediaType = attachment.mediaType,
                )
            }
        }
        val imported = dao.insertImportIfAbsent(
            person = PersonEntity(personId),
            batch = ImportBatchEntity(
                sourceHash = stagedBatch.sourceHash,
                personId = personId,
                messageCount = messages.size,
                importedAtEpochMillis = currentTimeMillis(),
                errorSummary = null,
            ),
            messages = messages,
            attachments = attachments,
        )
        return if (imported) ImportOutcome.IMPORTED else ImportOutcome.ALREADY_IMPORTED
    }

    fun deletePersonData(personId: String) {
        require(personId.isNotBlank()) { "联系人标识不能为空" }
        dao.deletePerson(personId)
    }
}
