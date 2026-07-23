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
    private var activeToken: Long? = null

    init {
        initializeMetadata()
    }

    private fun initializeMetadata() {
        val token = controller.beginInitialization() ?: return
        publishState()
        launchOperation(token, controller::initializeMetadata)
    }

    fun verifyAndLoadModels(candidateApiKey: String?) {
        val token = controller.beginVerification() ?: return
        publishState()
        launchOperation(token) { controller.verifyAndLoadModels(candidateApiKey, token) }
    }

    fun selectModel(modelId: String) {
        controller.selectModel(modelId)
        publishState()
    }

    fun saveVerifiedConfiguration() {
        val token = controller.beginSaving() ?: return
        publishState()
        launchOperation(token, controller::saveVerifiedConfiguration)
    }

    fun clearConfiguration() {
        val token = controller.beginClearing() ?: return
        publishState()
        launchOperation(token, controller::clearConfiguration)
    }

    fun discardCandidateConfiguration() {
        operationJob?.cancel()
        activeToken = null
        controller.discardCandidateConfiguration()
        publishState()
    }

    fun consumeSuccess(): SettingsSuccess? {
        val success = controller.consumeSuccess()
        publishState()
        return success
    }

    private fun launchOperation(token: Long, operation: (Long) -> Unit) {
        activeToken = token
        operationJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    operation(token)
                }
            } catch (exception: CancellationException) {
                controller.cancelPendingOperation(token)
                throw exception
            } finally {
                if (activeToken == token) {
                    activeToken = null
                    operationJob = null
                }
                publishState()
            }
        }
    }

    private fun publishState() {
        uiState = controller.state
    }
}
