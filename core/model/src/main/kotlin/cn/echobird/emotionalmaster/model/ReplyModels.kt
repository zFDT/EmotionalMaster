package cn.echobird.emotionalmaster.model

data class ReplySuggestion(
    val text: String,
    val strategy: String,
    val intent: String,
    val risk: String,
    val applicableWhen: String,
) {
    init {
        require(text.isNotBlank()) { "建议正文不能为空" }
        require(strategy.isNotBlank()) { "建议策略不能为空" }
        require(intent.isNotBlank()) { "建议意图不能为空" }
        require(risk.isNotBlank()) { "风险提示不能为空" }
        require(applicableWhen.isNotBlank()) { "适用条件不能为空" }
    }
}

class ReplySuggestionSet private constructor(
    val suggestions: List<ReplySuggestion>,
) {
    companion object {
        fun of(suggestions: List<ReplySuggestion>): ReplySuggestionSet {
            require(suggestions.size in 4..6) { "回复集合只能包含四到六条建议" }

            val strategyCount = suggestions
                .map { it.strategy.trim().lowercase() }
                .distinct()
                .size
            require(strategyCount == suggestions.size) { "回复集合不允许重复策略" }

            return ReplySuggestionSet(suggestions.toList())
        }
    }
}
