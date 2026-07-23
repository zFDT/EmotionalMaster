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
    val providerLabel: String = "未配置 DeepSeek",
    val hasOnlineProvider: Boolean = false,
    val isResolvingOnlineProvider: Boolean = true,
    val isGenerating: Boolean = false,
    val onlineFailureMessage: String? = null,
)

class ReplyViewModel(
    private val serviceFactory: () -> ReplySuggestionService? = { null },
    private val providerLabelProvider: () -> String? = { null },
    private val redactor: ContextRedactor = ContextRedactor(),
    private val fallback: RuleFallback = RuleFallback(),
) : ViewModel() {
    var uiState: ReplyUiState by mutableStateOf(ReplyUiState())
        private set
    private var providerRefreshGeneration = 0

    fun start(messages: List<ChatMessage>) {
        if (uiState.messages != messages) {
            uiState = ReplyUiState(messages = messages)
        }
        refreshOnlineProvider()
    }

    fun updateCurrentMessage(value: String) {
        uiState = uiState.copy(currentMessage = value)
    }

    fun showContextPreview() {
        if (
            uiState.currentMessage.isNotBlank() &&
            !uiState.isResolvingOnlineProvider
        ) {
            val redacted = redactor.redact(requestMessages()).messages.map { message ->
                RedactedPreviewMessage(
                    role = if (message.role == ConversationRole.SELF) "我" else "对方",
                    text = message.text,
                )
            }
            uiState = uiState.copy(
                showContextPreview = true,
                redactedMessages = redacted,
                onlineFailureMessage = null,
            )
        }
    }

    fun dismissContextPreview() {
        uiState = uiState.copy(showContextPreview = false)
    }

    fun generateSuggestions() {
        if (uiState.isGenerating) return
        if (uiState.isResolvingOnlineProvider) return
        if (!uiState.hasOnlineProvider) {
            uiState = onlineFailure(MISSING_CONFIGURATION_MESSAGE)
            return
        }
        val messages = requestMessages()
        uiState = uiState.copy(isGenerating = true, onlineFailureMessage = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    serviceFactory()?.suggest(messages)
                }
                uiState = when (result) {
                    null -> onlineFailure(MISSING_CONFIGURATION_MESSAGE)
                    is OnlineSuccess -> uiState.copy(
                        showContextPreview = false,
                        suggestions = result.suggestions.suggestions,
                        isOfflineFallback = false,
                        isGenerating = false,
                        onlineFailureMessage = null,
                    )
                    is OnlineFailure -> onlineFailure(result.message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                uiState = onlineFailure(CONFIGURATION_UNAVAILABLE_MESSAGE)
            }
        }
    }

    fun useOfflineSuggestions() {
        if (uiState.isGenerating || uiState.onlineFailureMessage == null) return
        uiState = uiState.copy(
            showContextPreview = false,
            suggestions = fallback.suggestions().suggestions,
            isOfflineFallback = true,
            onlineFailureMessage = null,
        )
    }

    fun refreshOnlineProvider() {
        val generation = ++providerRefreshGeneration
        uiState = uiState.copy(
            showContextPreview = false,
            providerLabel = RESOLVING_CONFIGURATION_LABEL,
            hasOnlineProvider = false,
            isResolvingOnlineProvider = true,
            onlineFailureMessage = null,
        )
        viewModelScope.launch {
            val label = try {
                withContext(Dispatchers.IO) { providerLabelProvider() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (generation == providerRefreshGeneration) {
                uiState = uiState.copy(
                    providerLabel = label ?: "未配置 DeepSeek",
                    hasOnlineProvider = label != null,
                    isResolvingOnlineProvider = false,
                )
            }
        }
    }

    private fun onlineFailure(message: String): ReplyUiState = uiState.copy(
        showContextPreview = true,
        suggestions = emptyList(),
        isOfflineFallback = false,
        isGenerating = false,
        onlineFailureMessage = message,
    )

    private fun requestMessages(): List<ChatMessage> {
        val lastTimestamp = uiState.messages.maxOfOrNull(ChatMessage::sentAt) ?: Instant.EPOCH
        return uiState.messages + ChatMessage(
            stableSourceId = "current-message",
            sentAt = lastTimestamp.plusNanos(1),
            direction = MessageDirection.INCOMING,
            text = uiState.currentMessage,
        )
    }

    private companion object {
        const val MISSING_CONFIGURATION_MESSAGE = "尚未配置 DeepSeek，请先打开模型设置"
        const val CONFIGURATION_UNAVAILABLE_MESSAGE = "DeepSeek 配置不可用，请重新打开模型设置"
        const val RESOLVING_CONFIGURATION_LABEL = "正在读取模型配置"
    }
}
