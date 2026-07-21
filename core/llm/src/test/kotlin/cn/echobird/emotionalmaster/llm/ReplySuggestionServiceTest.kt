package cn.echobird.emotionalmaster.llm

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySuggestionServiceTest {
    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `合法结构返回四到六条在线建议`() {
        val provider = FakeProvider(validSuggestionsJson(4))

        val result = ReplySuggestionService(provider).suggest(listOf(message("最近怎么样？")))

        assertEquals(SuggestionMode.ONLINE, result.mode)
        assertEquals(4, result.suggestions.suggestions.size)
        assertEquals(4, result.suggestions.suggestions.map { it.strategy }.distinct().size)
        assertFalse(provider.lastRequest!!.context.messages.single().text.contains("message-id"))
    }

    @Test
    fun `非法 JSON 数量越界或策略重复时统一规则降级`() {
        val invalidResponses = listOf(
            "not-json",
            validSuggestionsJson(3),
            validSuggestionsJson(7),
            validSuggestionsJson(4, duplicateStrategies = true),
        )

        invalidResponses.forEach { response ->
            val result = ReplySuggestionService(FakeProvider(response))
                .suggest(listOf(message("你好")))

            assertEquals(SuggestionMode.OFFLINE_FALLBACK, result.mode)
            assertEquals(4, result.suggestions.suggestions.size)
            assertEquals(4, result.suggestions.suggestions.map { it.strategy }.distinct().size)
        }
    }

    @Test
    fun `超时和非成功状态均使用规则降级`() {
        listOf(
            ModelProviderException.Timeout(SocketTimeoutException("timeout")),
            ModelProviderException.HttpFailure(503),
        ).forEach { failure ->
            val result = ReplySuggestionService(FakeProvider(failure = failure))
                .suggest(listOf(message("你好")))

            assertEquals(SuggestionMode.OFFLINE_FALLBACK, result.mode)
        }
    }

    @Test
    fun `OpenAI compatible provider 提取消息内容且安全日志不含密钥正文和查询参数`() {
        val responseContent = validSuggestionsJson(4)
        server = serverResponding(200, openAiResponse(responseContent))
        val events = mutableListOf<ModelCallEvent>()
        val port = server!!.address.port
        val provider = OpenAiCompatibleProvider(
            endpoint = "http://127.0.0.1:$port/v1/chat/completions?access_token=url-secret",
            apiKeyProvider = { "token-secret" },
            model = "test-model",
            logger = ProviderEventLogger(events::add),
        )

        val content = provider.generate(
            ModelRequest(RedactedConversation(listOf(RedactedMessage(ConversationRole.PEER, "body-secret")))),
        )

        assertEquals(responseContent, content)
        assertEquals(1, events.size)
        val logText = events.single().toString()
        listOf("token-secret", "body-secret", "url-secret", "Authorization").forEach {
            assertFalse(logText.contains(it))
        }
    }

    @Test
    fun `OpenAI compatible provider 在请求中明确结构化建议 schema`() {
        val requestBodies = mutableListOf<String>()
        server = serverResponding(
            status = 200,
            body = openAiResponse(validSuggestionsJson(4)),
            onRequest = requestBodies::add,
        )

        providerFor(server!!).generate(ModelRequest(RedactedConversation(emptyList())))

        val requestBody = requestBodies.single()
        listOf("suggestions", "text", "strategy", "intent", "risk", "applicableWhen").forEach {
            assertTrue(requestBody.contains(it))
        }
    }

    @Test
    fun `OpenAI compatible provider 将非成功状态和读取超时分类`() {
        server = serverResponding(503, "unavailable")
        val failureProvider = providerFor(server!!)
        assertThrows(ModelProviderException.HttpFailure::class.java) {
            failureProvider.generate(ModelRequest(RedactedConversation(emptyList())))
        }
        server!!.stop(0)

        server = serverResponding(200, openAiResponse(validSuggestionsJson(4)), delayMillis = 200)
        val timeoutClient = OkHttpClient.Builder()
            .readTimeout(50, TimeUnit.MILLISECONDS)
            .build()
        val timeoutProvider = providerFor(server!!, timeoutClient)
        assertThrows(ModelProviderException.Timeout::class.java) {
            timeoutProvider.generate(ModelRequest(RedactedConversation(emptyList())))
        }
    }

    private fun providerFor(
        server: HttpServer,
        client: OkHttpClient = OkHttpClient(),
    ) = OpenAiCompatibleProvider(
        endpoint = "http://127.0.0.1:${server.address.port}/v1/chat/completions",
        apiKeyProvider = { "test-key" },
        model = "test-model",
        client = client,
    )

    private fun serverResponding(
        status: Int,
        body: String,
        delayMillis: Long = 0,
        onRequest: (String) -> Unit = {},
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { exchange ->
            val requestBody = exchange.requestBody.use { it.readBytes().decodeToString() }
            onRequest(requestBody)
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun message(text: String) = ChatMessage(
        stableSourceId = "message-id",
        sentAt = Instant.parse("2026-01-01T00:00:00Z"),
        direction = MessageDirection.INCOMING,
        text = text,
    )

    private fun validSuggestionsJson(
        count: Int,
        duplicateStrategies: Boolean = false,
    ): String = buildString {
        append("{\"suggestions\":[")
        repeat(count) { index ->
            if (index > 0) append(',')
            val strategy = if (duplicateStrategies) "same" else "strategy-$index"
            append(
                """{"text":"reply-$index","strategy":"$strategy","intent":"intent-$index","risk":"risk-$index","applicableWhen":"when-$index"}""",
            )
        }
        append("]}")
    }

    private fun openAiResponse(content: String): String {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }

    private class FakeProvider(
        private val response: String? = null,
        private val failure: RuntimeException? = null,
    ) : ModelProvider {
        var lastRequest: ModelRequest? = null

        override fun generate(request: ModelRequest): String {
            lastRequest = request
            failure?.let { throw it }
            return requireNotNull(response)
        }
    }
}
