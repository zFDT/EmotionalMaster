package cn.echobird.emotionalmaster

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cn.echobird.emotionalmaster.analysis.AnalysisScreen
import cn.echobird.emotionalmaster.analysis.AnalysisViewModel
import cn.echobird.emotionalmaster.importer.ChatImportFormat
import cn.echobird.emotionalmaster.importer.IdentityMapping
import cn.echobird.emotionalmaster.importer.ImportErrorCode
import cn.echobird.emotionalmaster.importer.JsonChatParser
import cn.echobird.emotionalmaster.importer.StagedImportBatch
import cn.echobird.emotionalmaster.importing.ImportDocumentPreview
import cn.echobird.emotionalmaster.importing.ImportIssue
import cn.echobird.emotionalmaster.importing.ImportIssueSeverity
import cn.echobird.emotionalmaster.importing.ImportScreen
import cn.echobird.emotionalmaster.importing.ImportUiState
import cn.echobird.emotionalmaster.importing.ImportViewModel
import cn.echobird.emotionalmaster.reply.ReplyScreen
import cn.echobird.emotionalmaster.reply.ReplyViewModel
import cn.echobird.emotionalmaster.storage.AndroidKeystoreDatabaseKeyProvider
import cn.echobird.emotionalmaster.storage.EmotionalMasterDatabase
import cn.echobird.emotionalmaster.storage.ImportRepository
import cn.echobird.emotionalmaster.ui.EmotionalMasterTheme
import java.io.Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var importViewModel: ImportViewModel
    private lateinit var analysisViewModel: AnalysisViewModel
    private lateinit var replyViewModel: ReplyViewModel

    private val database by lazy {
        EmotionalMasterDatabase.openEncrypted(
            context = applicationContext,
            databaseName = "emotional-master.db",
            keyProvider = AndroidKeystoreDatabaseKeyProvider(applicationContext),
        )
    }
    private val importRepository by lazy { ImportRepository(database) }

    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::readDocument)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val provider = ViewModelProvider(this)
        importViewModel = provider[ImportViewModel::class.java]
        analysisViewModel = provider[AnalysisViewModel::class.java]
        replyViewModel = provider[ReplyViewModel::class.java]

        setContent {
            EmotionalMasterTheme {
                EmotionalMasterFlow(
                    importViewModel = importViewModel,
                    analysisViewModel = analysisViewModel,
                    replyViewModel = replyViewModel,
                    onChooseDocument = {
                        documentPicker.launch(arrayOf("application/json", "text/plain"))
                    },
                    onImportConfirmed = ::persistImport,
                )
            }
        }
    }

    private fun readDocument(uri: Uri) {
        lifecycleScope.launch {
            val loadingJob = launch {
                delay(300)
                importViewModel.startLoading()
            }
            val result = runCatching {
                withContext(Dispatchers.IO) { parseDocument(contentResolver, uri) }
            }
            loadingJob.cancel()
            result.onSuccess(importViewModel::showPreview)
                .onFailure { importViewModel.showError(it.message ?: "文件格式不受支持") }
        }
    }

    private fun persistImport(
        preview: ImportDocumentPreview,
        completion: (Result<Unit>) -> Unit,
    ) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    importRepository.importBatch(
                        personId = "default-peer",
                        stagedBatch = StagedImportBatch(
                            sourceHash = preview.sourceHash,
                            parsedMessages = preview.messages,
                            errors = emptyList(),
                        ),
                    )
                }
            }.map { }
            completion(result)
        }
    }
}

private enum class MvpScreen {
    IMPORT,
    ANALYSIS,
    REPLY,
}

@Composable
fun EmotionalMasterFlow(
    importViewModel: ImportViewModel,
    analysisViewModel: AnalysisViewModel,
    replyViewModel: ReplyViewModel,
    onChooseDocument: () -> Unit,
    onImportConfirmed: (ImportDocumentPreview, (Result<Unit>) -> Unit) -> Unit = { _, completion ->
        completion(Result.success(Unit))
    },
) {
    var screenName by rememberSaveable { mutableStateOf(MvpScreen.IMPORT.name) }
    val screen = MvpScreen.valueOf(screenName)

    fun navigate(target: MvpScreen) {
        screenName = target.name
    }

    BackHandler(enabled = screen != MvpScreen.IMPORT) {
        navigate(if (screen == MvpScreen.REPLY) MvpScreen.ANALYSIS else MvpScreen.IMPORT)
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        key(screen) {
        when (screen) {
            MvpScreen.IMPORT -> ImportScreen(
                state = importViewModel.uiState,
                onChooseDocument = onChooseDocument,
                onSwapIdentities = importViewModel::swapIdentities,
                onConfirmImport = {
                    val state = importViewModel.uiState as? ImportUiState.Preview
                    val messages = importViewModel.confirmedMessages()
                    if (state != null && messages != null) {
                        importViewModel.startSaving()
                        onImportConfirmed(state.document) { result ->
                            result.onSuccess {
                                analysisViewModel.analyze(messages)
                                navigate(MvpScreen.ANALYSIS)
                            }.onFailure { error ->
                                importViewModel.showPersistenceError(error.message ?: "无法写入本机资料库")
                            }
                        }
                    }
                },
            )

            MvpScreen.ANALYSIS -> analysisViewModel.uiState?.let { state ->
                AnalysisScreen(
                    state = state,
                    onGetReplySuggestions = {
                        replyViewModel.start(state.messages)
                        navigate(MvpScreen.REPLY)
                    },
                    onBack = { navigate(MvpScreen.IMPORT) },
                )
            }

            MvpScreen.REPLY -> ReplyScreen(
                state = replyViewModel.uiState,
                onCurrentMessageChange = replyViewModel::updateCurrentMessage,
                onPreviewContext = replyViewModel::showContextPreview,
                onDismissContextPreview = replyViewModel::dismissContextPreview,
                onGenerateSuggestions = replyViewModel::generateSuggestions,
                onBack = { navigate(MvpScreen.ANALYSIS) },
            )
        }
        }
    }
}

private fun parseDocument(
    resolver: ContentResolver,
    uri: Uri,
): ImportDocumentPreview {
    val metadata = resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "chat.json"
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
        name to size
    } ?: ("chat.json" to 0L)

    val participants = resolver.openInputStream(uri)?.bufferedReader()?.use(::findParticipants)
        ?: error("无法打开所选文件")
    require(participants.size == 2) { "需要识别到恰好两位聊天参与者" }

    val format = if (metadata.first.endsWith(".jsonl", ignoreCase = true)) {
        ChatImportFormat.JSONL
    } else {
        ChatImportFormat.JSON
    }
    val batch = resolver.openInputStream(uri)?.use { input ->
        JsonChatParser().parse(
            input = input,
            format = format,
            identityMapping = IdentityMapping(participants[0], participants[1]),
        )
    } ?: error("无法再次读取所选文件")

    return ImportDocumentPreview(
        fileName = metadata.first,
        fileSizeBytes = metadata.second,
        participantIds = participants,
        messages = batch.parsedMessages,
        issues = batch.errors.map { error ->
            ImportIssue(
                summary = error.lineNumber?.let { "第 $it 行：${error.summary}" } ?: error.summary,
                severity = if (
                    batch.parsedMessages.isEmpty() ||
                    error.code == ImportErrorCode.MALFORMED_JSON && format == ChatImportFormat.JSON
                ) {
                    ImportIssueSeverity.FATAL
                } else {
                    ImportIssueSeverity.WARNING
                },
            )
        },
        sourceHash = batch.sourceHash,
    )
}

private fun findParticipants(reader: Reader): List<String> {
    val senderPattern = Regex("\\\"senderId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    val participants = linkedSetOf<String>()
    val buffer = CharArray(4_096)
    var tail = ""
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        val window = tail + String(buffer, 0, count)
        senderPattern.findAll(window).forEach { match -> participants += match.groupValues[1] }
        tail = window.takeLast(256)
    }
    return participants.toList()
}
