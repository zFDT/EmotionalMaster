package cn.echobird.emotionalmaster.importer

import cn.echobird.emotionalmaster.model.MessageDirection
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonChatParserTest {
    private val parser = JsonChatParser()
    private val identityMapping = IdentityMapping(
        selfParticipantId = "self",
        peerParticipantId = "peer",
    )

    @Test
    fun `JSON 数组逐条解析并忽略未知字段`() {
        val batch = resource("chat/minimal.json").use { input ->
            parser.parse(input, ChatImportFormat.JSON, identityMapping)
        }

        assertTrue(batch.errors.isEmpty())
        assertEquals(2, batch.readyMessages.size)
        assertEquals(MessageDirection.INCOMING, batch.readyMessages[0].direction)
        assertEquals(MessageDirection.OUTGOING, batch.readyMessages[1].direction)
        assertTrue(batch.sourceHash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `JSONL 错误包含准确行号且整批不可提交`() {
        val content = """
            {"id":"message:1","senderId":"peer","sentAt":"2026-07-20T10:00:00Z","text":"你好"}
            {not-json}
            {"id":"message:2","senderId":"self","sentAt":"2026-07-20T10:01:00Z","text":"收到"}
        """.trimIndent()

        val batch = parser.parse(
            ByteArrayInputStream(content.toByteArray()),
            ChatImportFormat.JSONL,
            identityMapping,
        )

        assertFalse(batch.canCommit)
        assertTrue(batch.readyMessages.isEmpty())
        assertEquals(2, batch.errors.single().lineNumber)
    }

    @Test
    fun `未映射参与者构成致命校验错误`() {
        val content = """
            [{"id":"message:1","senderId":"unknown","sentAt":"2026-07-20T10:00:00Z","text":"你好"}]
        """.trimIndent()

        val batch = parser.parse(
            ByteArrayInputStream(content.toByteArray()),
            ChatImportFormat.JSON,
            identityMapping,
        )

        assertFalse(batch.canCommit)
        assertEquals(ImportErrorCode.UNKNOWN_PARTICIPANT, batch.errors.single().code)
    }

    private fun resource(path: String) = checkNotNull(javaClass.classLoader.getResourceAsStream(path))
}
