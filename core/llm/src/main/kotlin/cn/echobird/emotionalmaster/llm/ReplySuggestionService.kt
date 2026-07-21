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
    OFFLINE_FALLBACK,
}

data class ReplySuggestionResult(
    val suggestions: ReplySuggestionSet,
    val mode: SuggestionMode,
)

class ReplySuggestionService(
    private val provider: ModelProvider,
    private val redactor: ContextRedactor = ContextRedactor(),
    private val fallback: RuleFallback = RuleFallback(),
) {
    private val json = Json { isLenient = false }

    fun suggest(messages: List<ChatMessage>): ReplySuggestionResult = try {
        val response = provider.generate(ModelRequest(redactor.redact(messages)))
        ReplySuggestionResult(
            suggestions = parseSuggestions(response),
            mode = SuggestionMode.ONLINE,
        )
    } catch (_: Exception) {
        ReplySuggestionResult(
            suggestions = fallback.suggestions(),
            mode = SuggestionMode.OFFLINE_FALLBACK,
        )
    }

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

    private companion object {
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
