package cn.echobird.emotionalmaster.importer

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportIdempotenceTest {
    private val parser = JsonChatParser()
    private val deduplicator = MessageDeduplicator()
    private val identityMapping = IdentityMapping(
        selfParticipantId = "self",
        peerParticipantId = "peer",
    )

    @Test
    fun `同批内容哈希去重不依赖来源消息标识`() {
        val batch = parseFixture()

        val result = deduplicator.deduplicate(batch.readyMessages)

        assertEquals(2, result.messages.size)
        assertEquals(2, result.contentHashes.size)
    }

    @Test
    fun `重复导入相同内容保持幂等`() {
        val first = deduplicator.deduplicate(parseFixture().readyMessages)

        val second = deduplicator.deduplicate(
            messages = parseFixture().readyMessages,
            existingHashes = first.contentHashes,
        )

        assertEquals(0, second.messages.size)
        assertEquals(first.contentHashes, second.contentHashes)
    }

    @Test
    fun `相同输入产生稳定来源哈希`() {
        val bytes = resourceBytes("chat/duplicate.jsonl")

        val first = parser.parse(
            ByteArrayInputStream(bytes),
            ChatImportFormat.JSONL,
            identityMapping,
        )
        val second = parser.parse(
            ByteArrayInputStream(bytes),
            ChatImportFormat.JSONL,
            identityMapping,
        )

        assertEquals(first.sourceHash, second.sourceHash)
    }

    private fun parseFixture() = parser.parse(
        ByteArrayInputStream(resourceBytes("chat/duplicate.jsonl")),
        ChatImportFormat.JSONL,
        identityMapping,
    )

    private fun resourceBytes(path: String) =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)).use { it.readBytes() }
}
