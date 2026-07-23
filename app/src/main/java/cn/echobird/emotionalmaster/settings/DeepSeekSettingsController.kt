package cn.echobird.emotionalmaster.settings

import cn.echobird.emotionalmaster.llm.AvailableModel
import cn.echobird.emotionalmaster.llm.ModelCatalog
import cn.echobird.emotionalmaster.llm.ModelCatalogException
import cn.echobird.emotionalmaster.llm.preferredDeepSeekModel
import cn.echobird.emotionalmaster.storage.DeepSeekConfiguration
import cn.echobird.emotionalmaster.storage.DeepSeekConfigurationStore
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock

data class DeepSeekSettingsUiState(
    val hasSavedConfiguration: Boolean = false,
    val configuredModelId: String? = null,
    val models: List<AvailableModel> = emptyList(),
    val selectedModelId: String? = null,
    val isLoading: Boolean = false,
    val canSave: Boolean = false,
    val errorMessage: String? = null,
    val success: SettingsSuccess? = null,
)

enum class SettingsSuccess {
    SAVED,
    CLEARED,
}

class DeepSeekSettingsController(
    private val configurationStore: DeepSeekConfigurationStore,
    private val modelCatalog: ModelCatalog,
) {
    private var verifiedApiKey: String? = null
    private var pendingOperation: Operation? = null
    private val operationLock = ReentrantLock()

    var state: DeepSeekSettingsUiState = DeepSeekSettingsUiState()
        private set

    fun beginInitialization(): Boolean {
        if (!operationLock.tryLock()) return false
        return try {
            begin(Operation.INITIALIZATION)
        } finally {
            operationLock.unlock()
        }
    }

    fun initializeMetadata() {
        operationLock.lock()
        try {
            if (!startOrContinue(Operation.INITIALIZATION)) return
            try {
                val configuredModelId = configurationStore.configuredModelId()
                state = DeepSeekSettingsUiState(
                    hasSavedConfiguration = configuredModelId != null,
                    configuredModelId = configuredModelId,
                    selectedModelId = configuredModelId,
                )
            } catch (exception: CancellationException) {
                cancelPendingOperation()
                throw exception
            } catch (_: Exception) {
                state = DeepSeekSettingsUiState(errorMessage = INVALID_SAVED_CONFIGURATION_MESSAGE)
            } finally {
                pendingOperation = null
            }
        } finally {
            operationLock.unlock()
        }
    }

    fun beginVerification(): Boolean {
        if (!operationLock.tryLock()) return false
        return try {
            begin(Operation.VERIFICATION)
        } finally {
            operationLock.unlock()
        }
    }

    fun verifyAndLoadModels(candidateApiKey: String?) {
        operationLock.lock()
        try {
            if (!startOrContinue(Operation.VERIFICATION)) return
            try {
                val apiKey = candidateApiKey?.takeIf(String::isNotBlank) ?: loadSavedApiKey()
                if (apiKey == null) {
                    verificationFailed(INPUT_REQUIRED_MESSAGE)
                    return
                }

                val models = modelCatalog.listModels(apiKey)
                if (models.isEmpty()) {
                    verificationFailed(NO_MODELS_MESSAGE)
                    return
                }

                verifiedApiKey = apiKey
                state = state.copy(
                    models = models,
                    selectedModelId = preferredDeepSeekModel(models).id,
                    isLoading = false,
                    canSave = true,
                    errorMessage = null,
                    success = null,
                )
            } catch (exception: CancellationException) {
                cancelPendingOperation()
                throw exception
            } catch (exception: ModelCatalogException) {
                verificationFailed(exception.message ?: VERIFY_FAILED_MESSAGE)
            } catch (_: Exception) {
                verificationFailed(VERIFY_FAILED_MESSAGE)
            } finally {
                pendingOperation = null
            }
        } finally {
            operationLock.unlock()
        }
    }

    fun selectModel(modelId: String) {
        if (!operationLock.tryLock()) return
        try {
            if (state.isLoading) return
            if (state.models.none { it.id == modelId }) {
                state = state.copy(
                    canSave = false,
                    errorMessage = INVALID_MODEL_MESSAGE,
                    success = null,
                )
                return
            }

            state = state.copy(
                selectedModelId = modelId,
                canSave = verifiedApiKey != null,
                errorMessage = null,
                success = null,
            )
        } finally {
            operationLock.unlock()
        }
    }

    fun beginSaving(): Boolean {
        if (!operationLock.tryLock()) return false
        return try {
            begin(Operation.SAVING, requireVerifiedConfiguration = true)
        } finally {
            operationLock.unlock()
        }
    }

    fun saveVerifiedConfiguration() {
        operationLock.lock()
        try {
            if (!startOrContinue(Operation.SAVING, requireVerifiedConfiguration = true)) return
            val apiKey = verifiedApiKey
            val modelId = state.selectedModelId?.takeIf { selectedId -> state.models.any { it.id == selectedId } }
            if (apiKey == null || modelId == null) {
                discardCandidateConfiguration(errorMessage = null)
                return
            }

            try {
                configurationStore.save(DeepSeekConfiguration(modelId, apiKey))
                verifiedApiKey = null
                state = state.copy(
                    hasSavedConfiguration = true,
                    configuredModelId = modelId,
                    isLoading = false,
                    canSave = false,
                    errorMessage = null,
                    success = SettingsSuccess.SAVED,
                )
            } catch (exception: CancellationException) {
                cancelPendingOperation()
                throw exception
            } catch (_: Exception) {
                verifiedApiKey = null
                state = state.copy(
                    isLoading = false,
                    canSave = false,
                    errorMessage = SAVE_FAILED_MESSAGE,
                    success = null,
                )
            } finally {
                pendingOperation = null
            }
        } finally {
            operationLock.unlock()
        }
    }

    fun beginClearing(): Boolean {
        if (!operationLock.tryLock()) return false
        return try {
            begin(Operation.CLEARING)
        } finally {
            operationLock.unlock()
        }
    }

    fun clearConfiguration() {
        operationLock.lock()
        try {
            if (!startOrContinue(Operation.CLEARING)) return
            try {
                configurationStore.clear()
                verifiedApiKey = null
                state = DeepSeekSettingsUiState(success = SettingsSuccess.CLEARED)
            } catch (exception: CancellationException) {
                cancelPendingOperation()
                throw exception
            } catch (_: Exception) {
                verifiedApiKey = null
                state = state.copy(
                    isLoading = false,
                    canSave = false,
                    errorMessage = CLEAR_FAILED_MESSAGE,
                    success = null,
                )
            } finally {
                pendingOperation = null
            }
        } finally {
            operationLock.unlock()
        }
    }

    private fun begin(operation: Operation, requireVerifiedConfiguration: Boolean = false): Boolean {
        if (state.isLoading || (requireVerifiedConfiguration && !hasVerifiedConfiguration())) return false

        pendingOperation = operation
        if (operation == Operation.VERIFICATION) {
            verifiedApiKey = null
        }
        state = state.copy(
            models = if (operation == Operation.VERIFICATION) emptyList() else state.models,
            selectedModelId = if (operation == Operation.VERIFICATION) state.configuredModelId else state.selectedModelId,
            isLoading = true,
            canSave = false,
            errorMessage = null,
            success = null,
        )
        return true
    }

    private fun startOrContinue(operation: Operation, requireVerifiedConfiguration: Boolean = false): Boolean {
        if (state.isLoading && pendingOperation != operation) return false
        if (!state.isLoading && !begin(operation, requireVerifiedConfiguration)) return false
        return true
    }

    private fun hasVerifiedConfiguration(): Boolean =
        state.canSave &&
            verifiedApiKey != null &&
            state.selectedModelId != null &&
            state.models.any { it.id == state.selectedModelId }

    private fun loadSavedApiKey(): String? {
        if (!state.hasSavedConfiguration) return null
        return try {
            configurationStore.load()?.apiKey
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            throw SavedConfigurationLoadException()
        }
    }

    private fun verificationFailed(message: String) {
        discardCandidateConfiguration(message)
    }

    fun discardCandidateConfiguration() {
        operationLock.lock()
        try {
            discardCandidateConfiguration(errorMessage = null)
        } finally {
            operationLock.unlock()
        }
    }

    fun cancelPendingOperation() {
        operationLock.lock()
        try {
            if (state.isLoading || pendingOperation != null) {
                discardCandidateConfiguration(errorMessage = null)
            }
        } finally {
            operationLock.unlock()
        }
    }

    fun consumeSuccess(): SettingsSuccess? {
        operationLock.lock()
        return try {
            state.success?.also { state = state.copy(success = null) }
        } finally {
            operationLock.unlock()
        }
    }

    private fun discardCandidateConfiguration(errorMessage: String?) {
        verifiedApiKey = null
        pendingOperation = null
        state = state.copy(
            models = emptyList(),
            selectedModelId = state.configuredModelId,
            isLoading = false,
            canSave = false,
            errorMessage = errorMessage,
            success = null,
        )
    }

    private enum class Operation {
        INITIALIZATION,
        VERIFICATION,
        SAVING,
        CLEARING,
    }

    private class SavedConfigurationLoadException : RuntimeException()

    private companion object {
        const val INPUT_REQUIRED_MESSAGE = "请输入 DeepSeek API Key"
        const val NO_MODELS_MESSAGE = "DeepSeek 未返回可用模型"
        const val INVALID_MODEL_MESSAGE = "请选择可用的 DeepSeek 模型"
        const val VERIFY_FAILED_MESSAGE = "无法验证 DeepSeek 配置"
        const val SAVE_FAILED_MESSAGE = "无法保存 DeepSeek 配置"
        const val CLEAR_FAILED_MESSAGE = "无法清除 DeepSeek 配置"
        const val INVALID_SAVED_CONFIGURATION_MESSAGE = "DeepSeek 配置无效"
    }
}
