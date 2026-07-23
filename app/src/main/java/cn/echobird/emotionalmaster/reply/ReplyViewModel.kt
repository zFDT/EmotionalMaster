package cn.echobird.emotionalmaster.reply

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.echobird.emotionalmaster.llm.ContextRedactor
import cn.echobird.emotionalmaster.llm.ConversationRole
import cn.echobird.emotionalmaster.llm.OnlineFailure
import cn.echobird.emotionalmaster.llm.OnlineSuccess
import cn.echobird.emotionalmaster.llm.ReplySuggestionService
import cn.echobird.emotionalmaster.llm.RuleFallback
import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import cn.echobird.emotionalmaster.model.ReplySuggestion
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RedactedPreviewMessage(
    val role: String,
    val text: String,
)

data class ReplyUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentMessage: String = "",
    val showContextPreview: Boolean = false,
    val suggestions: List<ReplySuggestion> = emptyList(),
    val isOfflineFallback: Boolean = false,
    val redactedMessages: List<RedactedPreviewMessage> = emptyList(),
    val providerLabel: String = "未配置（将使用本地降级）",
    val isGenerating: Boolean = false,
    val generationError: String? = null,
)

class ReplyViewModel(
    private val service: ReplySuggestionService? = null,
    private val redactor: ContextRedactor = ContextRedactor(),
    private val fallback: RuleFallback = RuleFallback(),
    private val providerLabel: String = "未配置（将使用本地降级）",
) : ViewModel() {
    var uiState: ReplyUiState by mutableStateOf(ReplyUiState())
        private set

    fun start(messages: List<ChatMessage>) {
        if (uiState.messages != messages) {
            uiState = ReplyUiState(messages = messages, providerLabel = providerLabel)
        }
    }

    fun updateCurrentMessage(value: String) {
        uiState = uiState.copy(currentMessage = value)
    }

    fun showContextPreview() {
        if (uiState.currentMessage.isNotBlank()) {
            val redacted = redactor.redact(requestMessages()).messages.map { message ->
                RedactedPreviewMessage(
                    role = if (message.role == ConversationRole.SELF) "我" else "对方",
                    text = message.text,
                )
            }
            uiState = uiState.copy(
                showContextPreview = true,
                redactedMessages = redacted,
                generationError = null,
            )
        }
    }

    fun dismissContextPreview() {
        uiState = uiState.copy(showContextPreview = false)
    }

    fun generateSuggestions() {
        if (uiState.isGenerating) return
        uiState = uiState.copy(isGenerating = true, generationError = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { service?.suggest(requestMessages()) }
                uiState = when (result) {
                    null -> {
                        val suggestions = fallback.suggestions()
                        uiState.copy(
                            showContextPreview = false,
                            suggestions = suggestions.suggestions,
                            isOfflineFallback = true,
                            isGenerating = false,
                        )
                    }

                    is OnlineSuccess -> uiState.copy(
                        showContextPreview = false,
                        suggestions = result.suggestions.suggestions,
                        isOfflineFallback = false,
                        isGenerating = false,
                    )

                    is OnlineFailure -> uiState.copy(
                        showContextPreview = true,
                        suggestions = emptyList(),
                        isOfflineFallback = false,
                        isGenerating = false,
                        generationError = result.message,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                uiState = uiState.copy(
                    showContextPreview = true,
                    suggestions = emptyList(),
                    isOfflineFallback = false,
                    isGenerating = false,
                    generationError = "在线回复暂时不可用",
                )
            }
        }
    }

    private fun requestMessages(): List<ChatMessage> {
        val lastTimestamp = uiState.messages.maxOfOrNull(ChatMessage::sentAt) ?: Instant.EPOCH
        return uiState.messages + ChatMessage(
            stableSourceId = "current-message",
            sentAt = lastTimestamp.plusNanos(1),
            direction = MessageDirection.INCOMING,
            text = uiState.currentMessage,
        )
    }
}
