package cn.echobird.emotionalmaster.storage

import android.content.Context
import androidx.room.Room
import cn.echobird.emotionalmaster.importer.StagedImportBatch
import cn.echobird.emotionalmaster.importer.ChatImportFormat
import cn.echobird.emotionalmaster.importer.IdentityMapping
import cn.echobird.emotionalmaster.importer.JsonChatParser
import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import java.time.Instant
import java.io.ByteArrayInputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: EmotionalMasterDatabase
    private lateinit var repository: ImportRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "storage-${UUID.randomUUID()}.db"
        database = openDatabase()
        repository = ImportRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `唯一来源键冲突时整批事务回滚`() {
        val messages = listOf(
            message(sourceId = "message:1", text = "第一条"),
            message(sourceId = "message:1", text = "冲突内容"),
        )

        assertThrows(Exception::class.java) {
            repository.importBatch("peer:1", batch("source:rollback", messages))
        }

        assertEquals(0, database.chatDao().messageCount())
        assertEquals(0, database.chatDao().importBatchCount())
    }

    @Test
    fun `重复导入同一来源批次保持幂等`() {
        val batch = batch("source:idempotent", listOf(message()))

        assertEquals(ImportOutcome.IMPORTED, repository.importBatch("peer:1", batch))
        assertEquals(ImportOutcome.ALREADY_IMPORTED, repository.importBatch("peer:1", batch))
        assertEquals(1, database.chatDao().messageCount())
        assertEquals(1, database.chatDao().importBatchCount())
    }

    @Test
    fun `包含错误行的解析批次拒绝写入`() {
        val content = """
            {"id":"message:1","senderId":"peer","sentAt":"2026-07-20T10:00:00Z","text":"你好"}
            {not-json}
        """.trimIndent()
        val stagedBatch = JsonChatParser().parse(
            ByteArrayInputStream(content.toByteArray()),
            ChatImportFormat.JSONL,
            IdentityMapping("self", "peer"),
        )

        assertEquals(ImportOutcome.REJECTED, repository.importBatch("peer:1", stagedBatch))
        assertEquals(0, database.chatDao().messageCount())
        assertEquals(0, database.chatDao().importBatchCount())
    }

    @Test
    fun `删除联系人级数据同时删除消息和导入审计`() {
        repository.importBatch("peer:1", batch("source:delete", listOf(message())))

        repository.deletePersonData("peer:1")

        assertEquals(0, database.chatDao().messageCount())
        assertEquals(0, database.chatDao().importBatchCount())
        assertEquals(0, database.chatDao().personCount())
    }

    @Test
    fun `关闭并重新打开数据库后数据仍然存在`() {
        repository.importBatch("peer:1", batch("source:reopen", listOf(message())))
        database.close()

        database = openDatabase()
        repository = ImportRepository(database)

        assertEquals(1, database.chatDao().messageCount())
        assertEquals(1, database.chatDao().importBatchCount())
    }

    private fun openDatabase() = Room.databaseBuilder(
        context,
        EmotionalMasterDatabase::class.java,
        databaseName,
    ).allowMainThreadQueries().build()

    private fun batch(
        sourceHash: String,
        messages: List<ChatMessage>,
    ) = StagedImportBatch(
        sourceHash = sourceHash,
        parsedMessages = messages,
        errors = emptyList(),
    )

    private fun message(
        sourceId: String = "message:1",
        text: String = "你好",
    ) = ChatMessage(
        stableSourceId = sourceId,
        sentAt = Instant.parse("2026-07-20T10:00:00Z"),
        direction = MessageDirection.INCOMING,
        text = text,
    )
}
