package cn.echobird.emotionalmaster.llm

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekModelCatalogTest {
    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `获取合法列表时使用 Bearer 鉴权并返回可用模型`() {
        var requestMethod = ""
        var authorization = ""
        server = serverResponding(
            status = 200,
            body = modelsResponse("deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner"),
            onRequest = { method, header ->
                requestMethod = method
                authorization = header
            },
        )

        val models = catalogFor(server!!).listModels("test-api-key")

        assertEquals("GET", requestMethod)
        assertEquals("Bearer test-api-key", authorization)
        assertEquals(
            listOf(
                AvailableModel("deepseek-v4-pro", isDeprecated = false),
                AvailableModel("deepseek-chat", isDeprecated = true),
                AvailableModel("deepseek-reasoner", isDeprecated = true),
            ),
            models,
        )
    }

    @Test
    fun `模型按 ID 去重且 v4 pro 始终优先`() {
        server = serverResponding(
            status = 200,
            body = modelsResponse("z-model", "deepseek-chat", "deepseek-v4-pro", "a-model", "z-model"),
        )

        val models = catalogFor(server!!).listModels("test-api-key")

        assertEquals(
            listOf("deepseek-v4-pro", "a-model", "deepseek-chat", "z-model"),
            models.map(AvailableModel::id),
        )
    }

    @Test
    fun `优先模型选择 v4 pro 否则选择首个模型`() {
        assertEquals(
            AvailableModel("deepseek-v4-pro", isDeprecated = false),
            preferredDeepSeekModel(
                listOf(AvailableModel("other", false), AvailableModel("deepseek-v4-pro", false)),
            ),
        )
        assertEquals(
            AvailableModel("first", isDeprecated = false),
            preferredDeepSeekModel(listOf(AvailableModel("first", false), AvailableModel("second", false))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            preferredDeepSeekModel(emptyList())
        }
    }

    @Test
    fun `空或异常模型结构均拒绝`() {
        listOf(
            "not-json",
            "{}",
            "{\"object\":\"not-list\",\"data\":[{\"id\":\"model\",\"object\":\"model\",\"owned_by\":\"deepseek\"}]}",
            "{\"object\":\"list\",\"data\":[]}",
            "{\"object\":\"list\",\"data\":[{\"id\":\"\",\"object\":\"model\",\"owned_by\":\"deepseek\"}]}",
            "{\"object\":\"list\",\"data\":[{\"id\":\"model\",\"object\":\"not-model\",\"owned_by\":\"deepseek\"}]}",
            "{\"object\":\"list\",\"data\":[{\"id\":\"model\",\"object\":\"model\"}]}",
        ).forEach { body ->
            server = serverResponding(status = 200, body = body)

            assertThrows(ModelCatalogException.MalformedResponse::class.java) {
                catalogFor(server!!).listModels("test-api-key")
            }
            server!!.stop(0)
        }
    }

    @Test
    fun `HTTP 状态映射为可恢复的目录错误`() {
        val expectations = listOf(
            400 to ModelCatalogException.InvalidRequest::class.java,
            401 to ModelCatalogException.InvalidApiKey::class.java,
            402 to ModelCatalogException.InsufficientBalance::class.java,
            422 to ModelCatalogException.InvalidRequest::class.java,
            429 to ModelCatalogException.RateLimited::class.java,
            500 to ModelCatalogException.ServiceUnavailable::class.java,
            503 to ModelCatalogException.ServiceUnavailable::class.java,
            504 to ModelCatalogException.ServiceUnavailable::class.java,
            418 to ModelCatalogException.InvalidRequest::class.java,
        )

        expectations.forEach { (status, expectedType) ->
            server = serverResponding(status = status, body = "response-secret")

            val error = assertThrows(ModelCatalogException::class.java) {
                catalogFor(server!!).listModels("test-api-key")
            }

            assertTrue("status=$status", expectedType.isInstance(error))
            server!!.stop(0)
        }
    }

    @Test
    fun `读取超时被分类且错误信息不泄露密钥或响应正文`() {
        server = serverResponding(status = 200, body = "response-secret", delayMillis = 200)
        val client = OkHttpClient.Builder()
            .readTimeout(50, TimeUnit.MILLISECONDS)
            .build()

        val error = assertThrows(ModelCatalogException.Timeout::class.java) {
            catalogFor(server!!, client).listModels("test-api-key")
        }

        val observableError = error.toString() + error.message
        listOf("test-api-key", "Authorization", "response-secret").forEach { secret ->
            assertFalse(observableError.contains(secret))
        }
    }

    @Test
    fun `响应头已返回后读取 body 超时仍被分类`() {
        server = serverResponding(
            status = 200,
            body = modelsResponse("deepseek-v4-pro"),
            bodyDelayMillis = 200,
        )
        val client = OkHttpClient.Builder()
            .readTimeout(50, TimeUnit.MILLISECONDS)
            .build()

        assertThrows(ModelCatalogException.Timeout::class.java) {
            catalogFor(server!!, client).listModels("test-api-key")
        }
    }

    @Test
    fun `网络 IO 异常被分类为传输错误`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("network-secret") }
            .build()

        val error = assertThrows(ModelCatalogException.Transport::class.java) {
            DeepSeekModelCatalog(client = client).listModels("test-api-key")
        }

        assertFalse(error.toString().contains("test-api-key"))
        assertFalse(error.toString().contains("network-secret"))
    }

    @Test
    fun `异常响应的可观察错误不泄露密钥请求头或响应正文`() {
        server = serverResponding(status = 200, body = "response-secret")

        val error = assertThrows(ModelCatalogException.MalformedResponse::class.java) {
            catalogFor(server!!).listModels("test-api-key")
        }

        val observableError = error.toString() + error.message
        listOf("test-api-key", "Authorization", "response-secret").forEach { secret ->
            assertFalse(observableError.contains(secret))
        }
    }

    @Test
    fun `空 API Key 被立即拒绝且不发送请求`() {
        var called = false
        server = serverResponding(
            status = 200,
            body = modelsResponse("deepseek-v4-pro"),
            onRequest = { _, _ -> called = true },
        )

        assertThrows(IllegalArgumentException::class.java) {
            catalogFor(server!!).listModels("  ")
        }

        assertFalse(called)
    }

    private fun catalogFor(
        server: HttpServer,
        client: OkHttpClient = OkHttpClient(),
    ) = DeepSeekModelCatalog(
        endpoint = "http://127.0.0.1:${server.address.port}/models",
        client = client,
    )

    private fun serverResponding(
        status: Int,
        body: String,
        delayMillis: Long = 0,
        bodyDelayMillis: Long = 0,
        onRequest: (String, String) -> Unit = { _, _ -> },
    ): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/models") { exchange ->
            onRequest(exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization").orEmpty())
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            if (bodyDelayMillis > 0) Thread.sleep(bodyDelayMillis)
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private fun modelsResponse(vararg ids: String): String = ids.joinToString(
        prefix = "{\"object\":\"list\",\"data\":[",
        postfix = "]}",
    ) { id -> "{\"id\":\"$id\",\"object\":\"model\",\"owned_by\":\"deepseek\"}" }
}
