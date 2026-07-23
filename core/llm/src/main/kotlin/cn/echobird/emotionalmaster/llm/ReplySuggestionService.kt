package cn.echobird.emotionalmaster.llm

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.ReplySuggestion
import cn.echobird.emotionalmaster.model.ReplySuggestionSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SuggestionMode {
    ONLINE,
    ONLINE_FAILURE,
    OFFLINE_FALLBACK,
}

sealed interface ReplySuggestionResult {
    @Deprecated("任务 6 后请按 ReplySuggestionResult 的 sealed 子类型穷尽处理。")
    val suggestions: ReplySuggestionSet?

    @Deprecated("任务 6 后请按 ReplySuggestionResult 的 sealed 子类型穷尽处理。")
    val mode: SuggestionMode
}

data class OnlineSuccess(
    @Suppress("OVERRIDE_DEPRECATION")
    override val suggestions: ReplySuggestionSet,
) : ReplySuggestionResult {
    @Suppress("OVERRIDE_DEPRECATION")
    override val mode = SuggestionMode.ONLINE
}

data class OnlineFailure(
    val message: String,
) : ReplySuggestionResult {
    @Suppress("OVERRIDE_DEPRECATION")
    override val suggestions: ReplySuggestionSet? = null

    @Suppress("OVERRIDE_DEPRECATION")
    override val mode = SuggestionMode.ONLINE_FAILURE
}

class ReplySuggestionService(
    private val provider: ModelProvider,
    private val redactor: ContextRedactor = ContextRedactor(),
    private val fallback: RuleFallback = RuleFallback(),
) {
    private val json = Json { isLenient = false }

    fun suggest(messages: List<ChatMessage>): ReplySuggestionResult {
        val response = try {
            provider.generate(ModelRequest(redactor.redact(messages)))
        } catch (error: Exception) {
            return OnlineFailure(providerFailureMessage(error))
        }

        return try {
            OnlineSuccess(parseSuggestions(response))
        } catch (_: Exception) {
            OnlineFailure(INVALID_RESPONSE_MESSAGE)
        }
    }

    fun offlineSuggestions(): ReplySuggestionSet = fallback.suggestions()

    private fun parseSuggestions(response: String): ReplySuggestionSet {
        val root = json.parseToJsonElement(response).jsonObject
        require(root.keys == setOf(SUGGESTIONS)) { "回复顶层字段无效" }
        val suggestions = root.getValue(SUGGESTIONS).jsonArray.map { element ->
            element.jsonObject.toSuggestion()
        }
        return ReplySuggestionSet.of(suggestions)
    }

    private fun JsonObject.toSuggestion(): ReplySuggestion {
        require(keys == SUGGESTION_FIELDS) { "建议字段无效" }
        return ReplySuggestion(
            text = requiredString("text"),
            strategy = requiredString("strategy"),
            intent = requiredString("intent"),
            risk = requiredString("risk"),
            applicableWhen = requiredString("applicableWhen"),
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        getValue(name).jsonPrimitive
            .takeIf { it.isString }
            ?.content
            ?: error("字段 $name 必须为字符串")

    private fun providerFailureMessage(error: Exception): String = when (error) {
        is ModelProviderException.HttpFailure -> when (error.statusCode) {
            400, 422 -> INCOMPATIBLE_REQUEST_MESSAGE
            401 -> INVALID_API_KEY_MESSAGE
            402 -> INSUFFICIENT_BALANCE_MESSAGE
            429 -> TOO_MANY_REQUESTS_MESSAGE
            in 500..599 -> SERVICE_UNAVAILABLE_MESSAGE
            else -> TEMPORARILY_UNAVAILABLE_MESSAGE
        }

        is ModelProviderException.Timeout -> TIMEOUT_MESSAGE
        is ModelProviderException.TransportFailure -> TRANSPORT_FAILURE_MESSAGE
        is ModelProviderException.MalformedResponse -> INVALID_RESPONSE_MESSAGE
        else -> TEMPORARILY_UNAVAILABLE_MESSAGE
    }

    private companion object {
        const val INCOMPATIBLE_REQUEST_MESSAGE = "在线请求格式不兼容"
        const val INVALID_API_KEY_MESSAGE = "DeepSeek API Key 无效"
        const val INSUFFICIENT_BALANCE_MESSAGE = "DeepSeek 账户余额不足"
        const val TOO_MANY_REQUESTS_MESSAGE = "DeepSeek 请求过于频繁，请稍后重试"
        const val SERVICE_UNAVAILABLE_MESSAGE = "DeepSeek 服务暂时不可用"
        const val TIMEOUT_MESSAGE = "连接 DeepSeek 超时"
        const val TRANSPORT_FAILURE_MESSAGE = "无法连接 DeepSeek"
        const val INVALID_RESPONSE_MESSAGE = "在线回复格式无效"
        const val TEMPORARILY_UNAVAILABLE_MESSAGE = "在线回复暂时不可用"
        const val SUGGESTIONS = "suggestions"
        val SUGGESTION_FIELDS = setOf(
            "text",
            "strategy",
            "intent",
            "risk",
            "applicableWhen",
        )
    }
}
