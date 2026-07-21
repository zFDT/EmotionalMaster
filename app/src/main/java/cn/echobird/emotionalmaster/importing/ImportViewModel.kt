package cn.echobird.emotionalmaster.importing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import java.time.Instant

enum class ImportIssueSeverity {
    WARNING,
    FATAL,
}

data class ImportIssue(
    val summary: String,
    val severity: ImportIssueSeverity,
)

data class ImportDocumentPreview(
    val fileName: String,
    val fileSizeBytes: Long,
    val participantIds: List<String>,
    val messages: List<ChatMessage>,
    val issues: List<ImportIssue>,
    val sourceHash: String = "preview-only",
) {
    val firstMessageAt: Instant? = messages.minOfOrNull(ChatMessage::sentAt)
    val lastMessageAt: Instant? = messages.maxOfOrNull(ChatMessage::sentAt)
    val warnings: List<ImportIssue> = issues.filter { it.severity == ImportIssueSeverity.WARNING }
    val fatalIssues: List<ImportIssue> = issues.filter { it.severity == ImportIssueSeverity.FATAL }
    val canImport: Boolean = messages.isNotEmpty() && participantIds.size == 2 && fatalIssues.isEmpty()
}

sealed interface ImportUiState {
    data object Empty : ImportUiState
    data object Loading : ImportUiState
    data class Preview(
        val document: ImportDocumentPreview,
        val isSaving: Boolean = false,
        val persistenceError: String? = null,
    ) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

class ImportViewModel : ViewModel() {
    var uiState: ImportUiState by mutableStateOf(ImportUiState.Empty)
        private set

    fun startLoading() {
        uiState = ImportUiState.Loading
    }

    fun showPreview(preview: ImportDocumentPreview) {
        uiState = ImportUiState.Preview(preview)
    }

    fun showError(message: String) {
        uiState = ImportUiState.Error(message)
    }

    fun startSaving() {
        val current = uiState as? ImportUiState.Preview ?: return
        uiState = current.copy(isSaving = true, persistenceError = null)
    }

    fun showPersistenceError(message: String) {
        val current = uiState as? ImportUiState.Preview ?: return
        uiState = current.copy(isSaving = false, persistenceError = message)
    }

    fun swapIdentities() {
        val current = (uiState as? ImportUiState.Preview)?.document ?: return
        uiState = (uiState as ImportUiState.Preview).copy(
            document = current.copy(
                participantIds = current.participantIds.reversed(),
                messages = current.messages.map { message ->
                    message.copy(
                        direction = when (message.direction) {
                            MessageDirection.INCOMING -> MessageDirection.OUTGOING
                            MessageDirection.OUTGOING -> MessageDirection.INCOMING
                        },
                    )
                },
            ),
            persistenceError = null,
        )
    }

    fun confirmedMessages(): List<ChatMessage>? =
        (uiState as? ImportUiState.Preview)?.document
            ?.takeIf(ImportDocumentPreview::canImport)
            ?.messages
}
