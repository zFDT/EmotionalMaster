package cn.echobird.emotionalmaster.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "persons")
data class PersonEntity(
    @androidx.room.PrimaryKey
    val personId: String,
)

@Entity(
    tableName = "import_batches",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("personId")],
)
data class ImportBatchEntity(
    @androidx.room.PrimaryKey
    val sourceHash: String,
    val personId: String,
    val messageCount: Int,
    val importedAtEpochMillis: Long,
    val errorSummary: String?,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["sourceHash"],
            childColumns = ["importSourceHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("personId"), Index("importSourceHash")],
)
data class MessageEntity(
    @androidx.room.PrimaryKey
    val stableSourceId: String,
    val personId: String,
    val importSourceHash: String,
    val sentAtEpochMillis: Long,
    val direction: String,
    val text: String?,
)

@Entity(
    tableName = "message_attachments",
    primaryKeys = ["messageSourceId", "attachmentSourceId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["stableSourceId"],
            childColumns = ["messageSourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageSourceId")],
)
data class MessageAttachmentEntity(
    val messageSourceId: String,
    val attachmentSourceId: String,
    val mediaType: String,
)
