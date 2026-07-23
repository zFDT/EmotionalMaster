package cn.echobird.emotionalmaster.settings

import cn.echobird.emotionalmaster.llm.AvailableModel
import cn.echobird.emotionalmaster.llm.ModelCatalog
import cn.echobird.emotionalmaster.llm.ModelCatalogException
import cn.echobird.emotionalmaster.llm.preferredDeepSeekModel
import cn.echobird.emotionalmaster.storage.DeepSeekConfiguration
import cn.echobird.emotionalmaster.storage.DeepSeekConfigurationStore
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val operationLock = ReentrantLock()
    private var activeOperation: ActiveOperation? = null
    private var nextToken = 0L
    private var verifiedApiKey: String? = null

    @Volatile
    var state: DeepSeekSettingsUiState = DeepSeekSettingsUiState()
        private set

    fun beginInitialization(): Long? = begin(Operation.INITIALIZATION)

    fun initializeMetadata() {
        val token = claimActiveToken(Operation.INITIALIZATION) ?: beginInitialization()?.also {
            claimToken(it, Operation.INITIALIZATION)
        } ?: return
        initializeMetadataClaimed(token)
    }

    fun initializeMetadata(token: Long) {
        if (!claimToken(token, Operation.INITIALIZATION)) return
        initializeMetadataClaimed(token)
    }

    private fun initializeMetadataClaimed(token: Long) {
        try {
            val configuredModelId = configurationStore.configuredModelId()
            completeInitialization(token, configuredModelId)
        } catch (exception: CancellationException) {
            cancelPendingOperation(token)
            throw exception
        } catch (_: Exception) {
            completeInitializationFailure(token)
        }
    }

    fun beginVerification(): Long? = begin(Operation.VERIFICATION)

    fun verifyAndLoadModels(candidateApiKey: String?) {
        val token = claimActiveToken(Operation.VERIFICATION) ?: beginVerification()?.also {
            claimToken(it, Operation.VERIFICATION)
        } ?: return
        verifyAndLoadModelsClaimed(candidateApiKey, token)
    }

    fun verifyAndLoadModels(candidateApiKey: String?, token: Long) {
        if (!claimToken(token, Operation.VERIFICATION)) return
        verifyAndLoadModelsClaimed(candidateApiKey, token)
    }

    private fun verifyAndLoadModelsClaimed(candidateApiKey: String?, token: Long) {
        val source = operationLock.withLock {
            if (!isActiveLocked(token, Operation.VERIFICATION)) return
            VerificationSource(candidateApiKey?.takeIf(String::isNotBlank), state.hasSavedConfiguration)
        }
        val apiKey = when {
            source.candidateApiKey != null -> source.candidateApiKey
            !source.loadSavedConfiguration -> null
            else -> try {
                configurationStore.load()?.apiKey
            } catch (exception: CancellationException) {
                cancelPendingOperation(token)
                throw exception
            } catch (_: Exception) {
                verificationFailed(token, VERIFY_FAILED_MESSAGE)
                return
            }
        }
        if (apiKey == null) {
            verificationFailed(token, INPUT_REQUIRED_MESSAGE)
            return
        }

        try {
            val models = modelCatalog.listModels(apiKey)
            if (models.isEmpty()) {
                verificationFailed(token, NO_MODELS_MESSAGE)
                return
            }
            operationLock.withLock {
                if (!isActiveLocked(token, Operation.VERIFICATION)) return
                verifiedApiKey = apiKey
                activeOperation = null
                state = state.copy(
                    models = models,
                    selectedModelId = preferredDeepSeekModel(models).id,
                    isLoading = false,
                    canSave = true,
                    errorMessage = null,
                    success = null,
                )
            }
        } catch (exception: CancellationException) {
            cancelPendingOperation(token)
            throw exception
        } catch (exception: ModelCatalogException) {
            verificationFailed(token, exception.message ?: VERIFY_FAILED_MESSAGE)
        } catch (_: Exception) {
            verificationFailed(token, VERIFY_FAILED_MESSAGE)
        }
    }

    fun selectModel(modelId: String) {
        operationLock.withLock {
            if (state.isLoading) return
            if (state.models.none { it.id == modelId }) {
                state = state.copy(canSave = false, errorMessage = INVALID_MODEL_MESSAGE, success = null)
                return
            }
            state = state.copy(
                selectedModelId = modelId,
                canSave = verifiedApiKey != null,
                errorMessage = null,
                success = null,
            )
        }
    }

    fun beginSaving(): Long? = begin(Operation.SAVING, requireVerifiedConfiguration = true)

    fun saveVerifiedConfiguration() {
        val token = claimActiveToken(Operation.SAVING) ?: beginSaving()?.also {
            claimToken(it, Operation.SAVING)
        } ?: return
        saveVerifiedConfigurationClaimed(token)
    }

    /**
     * 保存已由用户触发；token 过期后即使底层保存完成也不会再回写 UI，后续初始化会读取真实存储。
     */
    fun saveVerifiedConfiguration(token: Long) {
        if (!claimToken(token, Operation.SAVING)) return
        saveVerifiedConfigurationClaimed(token)
    }

    private fun saveVerifiedConfigurationClaimed(token: Long) {
        val request = operationLock.withLock {
            if (!isActiveLocked(token, Operation.SAVING)) return
            val apiKey = verifiedApiKey ?: return@withLock null
            val modelId = state.selectedModelId?.takeIf { selectedId -> state.models.any { it.id == selectedId } }
            modelId?.let { SaveRequest(apiKey, it) }
        } ?: run {
            cancelPendingOperation(token)
            return
        }
        try {
            configurationStore.save(DeepSeekConfiguration(request.modelId, request.apiKey))
            operationLock.withLock {
                if (!isActiveLocked(token, Operation.SAVING)) return
                verifiedApiKey = null
                activeOperation = null
                state = state.copy(
                    hasSavedConfiguration = true,
                    configuredModelId = request.modelId,
                    isLoading = false,
                    canSave = false,
                    errorMessage = null,
                    success = SettingsSuccess.SAVED,
                )
            }
        } catch (exception: CancellationException) {
            cancelPendingOperation(token)
            throw exception
        } catch (_: Exception) {
            saveFailed(token)
        }
    }

    fun beginClearing(): Long? = begin(Operation.CLEARING)

    fun clearConfiguration() {
        val token = claimActiveToken(Operation.CLEARING) ?: beginClearing()?.also {
            claimToken(it, Operation.CLEARING)
        } ?: return
        clearConfigurationClaimed(token)
    }

    /**
     * 清除已由用户触发；token 过期后即使底层清除完成也不会再回写 UI，后续初始化会读取真实存储。
     */
    fun clearConfiguration(token: Long) {
        if (!claimToken(token, Operation.CLEARING)) return
        clearConfigurationClaimed(token)
    }

    private fun clearConfigurationClaimed(token: Long) {
        try {
            configurationStore.clear()
            operationLock.withLock {
                if (!isActiveLocked(token, Operation.CLEARING)) return
                verifiedApiKey = null
                activeOperation = null
                state = DeepSeekSettingsUiState(success = SettingsSuccess.CLEARED)
            }
        } catch (exception: CancellationException) {
            cancelPendingOperation(token)
            throw exception
        } catch (_: Exception) {
            clearFailed(token)
        }
    }

    fun discardCandidateConfiguration() {
        operationLock.withLock { discardCandidateLocked() }
    }

    fun cancelPendingOperation(token: Long? = null) {
        operationLock.withLock {
            if (token == null || activeOperation?.token == token) {
                discardCandidateLocked()
            }
        }
    }

    fun consumeSuccess(): SettingsSuccess? = operationLock.withLock {
        state.success?.also { state = state.copy(success = null) }
    }

    private fun begin(operation: Operation, requireVerifiedConfiguration: Boolean = false): Long? = operationLock.withLock {
        if (activeOperation != null || state.isLoading || (requireVerifiedConfiguration && !hasVerifiedConfiguration())) {
            return null
        }
        val token = ++nextToken
        activeOperation = ActiveOperation(operation, token)
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
        token
    }

    private fun claimActiveToken(operation: Operation): Long? = operationLock.withLock {
        activeOperation?.takeIf { it.operation == operation && !it.workerClaimed }?.also {
            it.workerClaimed = true
        }?.token
    }

    private fun claimToken(token: Long, operation: Operation): Boolean = operationLock.withLock {
        activeOperation?.takeIf { it.token == token && it.operation == operation && !it.workerClaimed }?.let {
            it.workerClaimed = true
            true
        } ?: false
    }

    private fun isActiveLocked(token: Long, operation: Operation): Boolean =
        activeOperation?.let { it.token == token && it.operation == operation } == true

    private fun hasVerifiedConfiguration(): Boolean =
        state.canSave && verifiedApiKey != null && state.selectedModelId != null && state.models.any { it.id == state.selectedModelId }

    private fun completeInitialization(token: Long, configuredModelId: String?) {
        operationLock.withLock {
            if (!isActiveLocked(token, Operation.INITIALIZATION)) return
            activeOperation = null
            state = DeepSeekSettingsUiState(
                hasSavedConfiguration = configuredModelId != null,
                configuredModelId = configuredModelId,
                selectedModelId = configuredModelId,
            )
        }
    }

    private fun completeInitializationFailure(token: Long) {
        operationLock.withLock {
            if (!isActiveLocked(token, Operation.INITIALIZATION)) return
            activeOperation = null
            state = DeepSeekSettingsUiState(errorMessage = INVALID_SAVED_CONFIGURATION_MESSAGE)
        }
    }

    private fun verificationFailed(token: Long, message: String) {
        operationLock.withLock {
            if (!isActiveLocked(token, Operation.VERIFICATION)) return
            discardCandidateLocked(message)
        }
    }

    private fun saveFailed(token: Long) {
        operationLock.withLock {
            if (!isActiveLocked(token, Operation.SAVING)) return
            verifiedApiKey = null
            activeOperation = null
            state = state.copy(
                isLoading = false,
                canSave = false,
                errorMessage = SAVE_FAILED_MESSAGE,
                success = null,
            )
        }
    }

    private fun clearFailed(token: Long) {
        operationLock.withLock {
            if (!isActiveLocked(token, Operation.CLEARING)) return
            verifiedApiKey = null
            activeOperation = null
            state = state.copy(
                isLoading = false,
                canSave = false,
                errorMessage = CLEAR_FAILED_MESSAGE,
                success = null,
            )
        }
    }

    private fun discardCandidateLocked(errorMessage: String? = null) {
        nextToken += 1
        verifiedApiKey = null
        activeOperation = null
        state = state.copy(
            models = emptyList(),
            selectedModelId = state.configuredModelId,
            isLoading = false,
            canSave = false,
            errorMessage = errorMessage,
            success = null,
        )
    }

    private data class ActiveOperation(
        val operation: Operation,
        val token: Long,
        var workerClaimed: Boolean = false,
    )

    private data class VerificationSource(
        val candidateApiKey: String?,
        val loadSavedConfiguration: Boolean,
    )

    private data class SaveRequest(
        val apiKey: String,
        val modelId: String,
    )

    private enum class Operation {
        INITIALIZATION,
        VERIFICATION,
        SAVING,
        CLEARING,
    }

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
