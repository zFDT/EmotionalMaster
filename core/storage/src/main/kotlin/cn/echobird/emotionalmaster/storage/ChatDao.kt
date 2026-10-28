package cn.echobird.emotionalmaster.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPerson(person: PersonEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertImportBatch(batch: ImportBatchEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAttachments(attachments: List<MessageAttachmentEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM import_batches WHERE sourceHash = :sourceHash)")
    fun importBatchExists(sourceHash: String): Boolean

    @Query("SELECT COUNT(*) FROM persons")
    fun personCount(): Int

    @Query("SELECT COUNT(*) FROM import_batches")
    fun importBatchCount(): Int

    @Query("SELECT COUNT(*) FROM messages")
    fun messageCount(): Int

    @Query("DELETE FROM persons WHERE personId = :personId")
    fun deletePerson(personId: String)

    @Transaction
    fun insertImportIfAbsent(
        person: PersonEntity,
        batch: ImportBatchEntity,
        messages: List<MessageEntity>,
        attachments: List<MessageAttachmentEntity>,
    ): Boolean {
        if (importBatchExists(batch.sourceHash)) return false

        insertPerson(person)
        insertImportBatch(batch)
        insertMessages(messages)
        if (attachments.isNotEmpty()) insertAttachments(attachments)
        return true
    }
}
