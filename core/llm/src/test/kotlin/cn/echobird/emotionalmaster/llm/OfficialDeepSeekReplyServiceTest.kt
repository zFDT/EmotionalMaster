package cn.echobird.emotionalmaster.llm

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialDeepSeekReplyServiceTest {
    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `公开官方工厂固定使用 DeepSeek Chat Completions 地址且不发起请求`() {
        val service = officialDeepSeekReplySuggestionService(
            apiKeyProvider = { "unused-test-key" },
            model = "deepseek-v4-pro",
        )
        val provider = ReplySuggestionService::class.java.getDeclaredField("provider").run {
            isAccessible = true
            get(service)
        }
        val endpoint = OpenAiCompatibleProvider::class.java.getDeclaredField("endpoint").run {
            isAccessible = true
            get(provider).toString()
        }

        assertEquals(
            "https://api.deepseek.com/chat/completions",
            OFFICIAL_DEEPSEEK_CHAT_COMPLETIONS_ENDPOINT,
        )
        assertEquals(OFFICIAL_DEEPSEEK_CHAT_COMPLETIONS_ENDPOINT, endpoint)
    }

    @Test
    fun `官方服务使用选择的模型且不会在可打印对象中暴露密钥`() {
        val requestBody = AtomicReference("")
        val authorization = AtomicReference("")
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/chat/completions") { exchange ->
                requestBody.set(exchange.requestBody.bufferedReader().readText())
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                val response = """
                    {"choices":[{"message":{"content":"{\"suggestions\":[
                    {\"text\":\"一\",\"strategy\":\"甲\",\"intent\":\"稳住\",\"risk\":\"低\",\"applicableWhen\":\"现在\"},
                    {\"text\":\"二\",\"strategy\":\"乙\",\"intent\":\"澄清\",\"risk\":\"低\",\"applicableWhen\":\"现在\"},
                    {\"text\":\"三\",\"strategy\":\"丙\",\"intent\":\"共情\",\"risk\":\"低\",\"applicableWhen\":\"现在\"},
                    {\"text\":\"四\",\"strategy\":\"丁\",\"intent\":\"边界\",\"risk\":\"低\",\"applicableWhen\":\"现在\"}
                    ]}"}}]}
                """.trimIndent()
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }
        val secret = "unit-secret"
        val service = officialDeepSeekReplySuggestionService(
            apiKeyProvider = { secret },
            model = "deepseek-v4-pro",
            endpoint = "http://127.0.0.1:${server!!.address.port}/chat/completions",
        )

        val result = service.suggest(emptyList())

        assertTrue(result is OnlineSuccess)
        assertTrue(requestBody.get().contains("\"model\":\"deepseek-v4-pro\""))
        assertEquals("Bearer $secret", authorization.get())
        assertFalse(service.toString().contains(secret))
    }
}
