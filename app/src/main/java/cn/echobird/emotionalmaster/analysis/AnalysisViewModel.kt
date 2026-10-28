package cn.echobird.emotionalmaster.analysis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import cn.echobird.emotionalmaster.analytics.ConversationStats
import cn.echobird.emotionalmaster.analytics.StatsEngine
import cn.echobird.emotionalmaster.model.ChatMessage

data class AnalysisUiState(
    val messages: List<ChatMessage>,
    val stats: ConversationStats,
)

class AnalysisViewModel(
    private val statsEngine: StatsEngine = StatsEngine(),
) : ViewModel() {
    var uiState: AnalysisUiState? by mutableStateOf(null)
        private set

    fun analyze(messages: List<ChatMessage>) {
        uiState = AnalysisUiState(messages, statsEngine.calculate(messages))
    }
}
