package cn.echobird.emotionalmaster.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.echobird.emotionalmaster.llm.ModelCatalog
import cn.echobird.emotionalmaster.storage.DeepSeekConfigurationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeepSeekSettingsViewModel(
    private val controller: DeepSeekSettingsController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    constructor(
        configurationStore: DeepSeekConfigurationStore,
        modelCatalog: ModelCatalog,
    ) : this(DeepSeekSettingsController(configurationStore, modelCatalog))

    var uiState: DeepSeekSettingsUiState by mutableStateOf(controller.state)
        private set

    private var operationJob: Job? = null

    init {
        initializeMetadata()
    }

    private fun initializeMetadata() {
        if (!controller.beginInitialization()) return
        publishState()
        launchOperation(controller::initializeMetadata)
    }

    fun verifyAndLoadModels(candidateApiKey: String?) {
        if (!controller.beginVerification()) return
        publishState()
        launchOperation { controller.verifyAndLoadModels(candidateApiKey) }
    }

    fun selectModel(modelId: String) {
        controller.selectModel(modelId)
        publishState()
    }

    fun saveVerifiedConfiguration() {
        if (!controller.beginSaving()) return
        publishState()
        launchOperation(controller::saveVerifiedConfiguration)
    }

    fun clearConfiguration() {
        if (!controller.beginClearing()) return
        publishState()
        launchOperation(controller::clearConfiguration)
    }

    fun discardCandidateConfiguration() {
        operationJob?.cancel()
        controller.discardCandidateConfiguration()
        publishState()
    }

    fun consumeSuccess(): SettingsSuccess? {
        val success = controller.consumeSuccess()
        publishState()
        return success
    }

    private fun launchOperation(operation: () -> Unit) {
        operationJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    operation()
                }
            } catch (exception: CancellationException) {
                controller.cancelPendingOperation()
                throw exception
            } finally {
                publishState()
            }
        }
    }

    private fun publishState() {
        uiState = controller.state
    }
}
