package cn.echobird.emotionalmaster.llm

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `合法结构返回四到六条 OnlineSuccess`() {
        val provider = FakeProvider(validSuggestionsJson(4))

        val result = ReplySuggestionService(provider).suggest(listOf(message("最近怎么样？")))

        assertTrue(result is OnlineSuccess)
        assertEquals(SuggestionMode.ONLINE, result.mode)
        assertEquals(4, result.suggestions!!.suggestions.size)
        assertEquals(4, result.suggestions!!.suggestions.map { it.strategy }.distinct().size)
        assertFalse(provider.lastRequest!!.context.messages.single().text.contains("message-id"))
    }

    @Test
    fun `非法 JSON 返回 OnlineFailure 且没有离线建议`() {
        val result = ReplySuggestionService(FakeProvider("not-json"))
            .suggest(listOf(message("你好")))

        assertOnlineFailure(result, "在线回复格式无效")
    }

    @Test
    fun `建议数量越界返回 OnlineFailure 且没有离线建议`() {
        listOf(
            validSuggestionsJson(3),
            validSuggestionsJson(7),
        ).forEach { response ->
            val result = ReplySuggestionService(FakeProvider(response))
                .suggest(listOf(message("你好")))

            assertOnlineFailure(result, "在线回复格式无效")
        }
    }

    @Test
    fun `重复策略返回 OnlineFailure 且没有离线建议`() {
        val result = ReplySuggestionService(FakeProvider(validSuggestionsJson(4, duplicateStrategies = true)))
            .suggest(listOf(message("你好")))

        assertOnlineFailure(result, "在线回复格式无效")
    }

    @Test
    fun `HTTP 失败返回对应的 OnlineFailure 文案`() {
        val expectedMessages = mapOf(
            400 to "在线请求格式不兼容",
            401 to "DeepSeek API Key 无效",
            402 to "DeepSeek 账户余额不足",
            422 to "在线请求格式不兼容",
            429 to "DeepSeek 请求过于频繁，请稍后重试",
            503 to "DeepSeek 服务暂时不可用",
        )

        expectedMessages.forEach { (statusCode, message) ->
            val result = ReplySuggestionService(FakeProvider(failure = ModelProviderException.HttpFailure(statusCode)))
                .suggest(listOf(message("你好")))

            assertOnlineFailure(result, message)
        }
    }

    @Test
    fun `超时和传输失败返回对应的 OnlineFailure 文案`() {
        listOf(
            ModelProviderException.Timeout(SocketTimeoutException("timeout-secret")) to "连接 DeepSeek 超时",
            ModelProviderException.TransportFailure(IOException("transport-secret")) to "无法连接 DeepSeek",
        ).forEach { (failure, message) ->
            val result = ReplySuggestionService(FakeProvider(failure = failure))
                .suggest(listOf(message("你好")))

            assertOnlineFailure(result, message)
        }
    }

    @Test
    fun `未知异常不泄露底层信息`() {
        val result = ReplySuggestionService(FakeProvider(failure = IllegalStateException("secret")))
            .suggest(listOf(message("你好")))

        assertOnlineFailure(result, "在线回复暂时不可用")
        assertFalse((result as OnlineFailure).message.contains("secret"))
    }

    @Test
    fun `只有显式调用才返回四条离线建议`() {
        val service = ReplySuggestionService(FakeProvider(failure = IllegalStateException("secret")))

        val failure = service.suggest(listOf(message("你好")))
        val offlineSuggestions = service.offlineSuggestions()

        assertOnlineFailure(failure, "在线回复暂时不可用")
        assertEquals(4, offlineSuggestions.suggestions.size)
        assertEquals(4, offlineSuggestions.suggestions.map { it.strategy }.distinct().size)
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

    private fun assertOnlineFailure(result: ReplySuggestionResult, expectedMessage: String) {
        assertTrue(result is OnlineFailure)
        assertEquals(SuggestionMode.OFFLINE_FALLBACK, result.mode)
        assertEquals(expectedMessage, (result as OnlineFailure).message)
        assertNull(result.suggestions)
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
