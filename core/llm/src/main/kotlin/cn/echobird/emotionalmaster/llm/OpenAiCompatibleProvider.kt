package cn.echobird.emotionalmaster.llm

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ModelCallEvent(
    val method: String,
    val endpoint: String,
    val statusCode: Int?,
    val durationMillis: Long,
)

fun interface ProviderEventLogger {
    fun log(event: ModelCallEvent)

    companion object {
        val NONE = ProviderEventLogger { }
    }
}

class OpenAiCompatibleProvider(
    endpoint: String,
    private val apiKeyProvider: () -> String,
    private val model: String,
    client: OkHttpClient = OkHttpClient(),
    logger: ProviderEventLogger = ProviderEventLogger.NONE,
) : ModelProvider {
    private val endpoint = endpoint.toHttpUrl()
    private val json = Json { isLenient = false }
    private val client = client.newBuilder()
        .addInterceptor(SafeMetadataLoggingInterceptor(logger))
        .build()

    init {
        require(model.isNotBlank()) { "模型名称不能为空" }
    }

    override fun generate(request: ModelRequest): String {
        val apiKey = apiKeyProvider()
        require(apiKey.isNotBlank()) { "模型 API Key 不能为空" }
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(requestBody(request).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (error: SocketTimeoutException) {
            throw ModelProviderException.Timeout(error)
        } catch (error: IOException) {
            throw ModelProviderException.TransportFailure(error)
        }

        response.use {
            if (!it.isSuccessful) throw ModelProviderException.HttpFailure(it.code)
            val body = it.body.string()
            return try {
                json.parseToJsonElement(body)
                    .jsonObject.requiredArray("choices")
                    .first().jsonObject
                    .requiredObject("message")
                    .requiredString("content")
            } catch (error: Exception) {
                throw ModelProviderException.MalformedResponse(error)
            }
        }
    }

    private fun requestBody(request: ModelRequest): String = buildJsonObject {
        put("model", JsonPrimitive(model))
        put("messages", buildJsonArray {
            add(message("system", SYSTEM_PROMPT))
            add(message("user", renderContext(request.context)))
        })
        put("response_format", buildJsonObject {
            put("type", JsonPrimitive("json_object"))
        })
    }.toString()

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(content))
    }

    private fun renderContext(context: RedactedConversation): String = context.messages
        .joinToString("\n") { message -> "${message.role.name}: ${message.text}" }

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name]?.jsonArray ?: error("缺少数组字段 $name")

    private fun JsonObject.requiredObject(name: String): JsonObject =
        this[name]?.jsonObject ?: error("缺少对象字段 $name")

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?: error("缺少字符串字段 $name")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SYSTEM_PROMPT =
            "基于最小化聊天上下文生成 4 到 6 条不同策略的保守回复建议。" +
                "只返回 JSON 对象：{\"suggestions\":[{\"text\":\"\",\"strategy\":\"\"," +
                "\"intent\":\"\",\"risk\":\"\",\"applicableWhen\":\"\"}]}。" +
                "所有字段必须是非空字符串，strategy 不得重复。"
    }
}

/**
 * 创建仅连接 DeepSeek 官方 Chat Completions 地址的回复服务。
 */
fun officialDeepSeekReplySuggestionService(
    apiKeyProvider: () -> String,
    model: String,
): ReplySuggestionService = deepSeekReplySuggestionService(
    apiKeyProvider = apiKeyProvider,
    model = model,
    endpoint = OFFICIAL_DEEPSEEK_CHAT_COMPLETIONS_ENDPOINT,
)

internal fun officialDeepSeekReplySuggestionService(
    apiKeyProvider: () -> String,
    model: String,
    endpoint: String,
): ReplySuggestionService = deepSeekReplySuggestionService(apiKeyProvider, model, endpoint)

private fun deepSeekReplySuggestionService(
    apiKeyProvider: () -> String,
    model: String,
    endpoint: String,
): ReplySuggestionService = ReplySuggestionService(
    OpenAiCompatibleProvider(
        endpoint = endpoint,
        apiKeyProvider = apiKeyProvider,
        model = model,
    ),
)

internal const val OFFICIAL_DEEPSEEK_CHAT_COMPLETIONS_ENDPOINT =
    "https://api.deepseek.com/chat/completions"

private class SafeMetadataLoggingInterceptor(
    private val logger: ProviderEventLogger,
    private val nanoTime: () -> Long = System::nanoTime,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val startedAt = nanoTime()
        var statusCode: Int? = null
        try {
            return chain.proceed(request).also { statusCode = it.code }
        } finally {
            val event = ModelCallEvent(
                method = request.method,
                endpoint = request.url.newBuilder().query(null).build().toString(),
                statusCode = statusCode,
                durationMillis = (nanoTime() - startedAt) / NANOS_PER_MILLISECOND,
            )
            runCatching { logger.log(event) }
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000
    }
}
