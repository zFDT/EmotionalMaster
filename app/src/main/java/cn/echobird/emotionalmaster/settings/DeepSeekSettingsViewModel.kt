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

    fun verifyAndLoadModels(candidateApiKey: String?) {
        if (!controller.beginVerification()) return
        publishState()
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    controller.verifyAndLoadModels(candidateApiKey)
                }
            } catch (exception: CancellationException) {
                throw exception
            } finally {
                publishState()
            }
        }
    }

    fun selectModel(modelId: String) {
        controller.selectModel(modelId)
        publishState()
    }

    fun saveVerifiedConfiguration() {
        if (!controller.beginSaving()) return
        publishState()
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    controller.saveVerifiedConfiguration()
                }
            } catch (exception: CancellationException) {
                throw exception
            } finally {
                publishState()
            }
        }
    }

    fun clearConfiguration() {
        if (!controller.beginClearing()) return
        publishState()
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    controller.clearConfiguration()
                }
            } catch (exception: CancellationException) {
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
