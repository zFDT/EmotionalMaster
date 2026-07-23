package cn.echobird.emotionalmaster.settings

import cn.echobird.emotionalmaster.llm.AvailableModel
import cn.echobird.emotionalmaster.llm.ModelCatalog
import cn.echobird.emotionalmaster.llm.ModelCatalogException
import cn.echobird.emotionalmaster.storage.ConfigurationStoreException
import cn.echobird.emotionalmaster.storage.DeepSeekConfiguration
import cn.echobird.emotionalmaster.storage.DeepSeekConfigurationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeepSeekSettingsControllerTest {
    @Test
    fun `构造时仅读取模型元数据而不解密`() {
        val store = FakeStore(configuration = DeepSeekConfiguration("saved-model", "saved-secret"))

        val controller = DeepSeekSettingsController(store, FakeCatalog())

        assertEquals(1, store.configuredModelIdCalls)
        assertEquals(0, store.loadCalls)
        assertTrue(controller.state.hasSavedConfiguration)
        assertEquals("saved-model", controller.state.configuredModelId)
        assertEquals("saved-model", controller.state.selectedModelId)
    }

    @Test
    fun `构造时无配置或损坏配置均不解密`() {
        val emptyStore = FakeStore()
        val emptyController = DeepSeekSettingsController(emptyStore, FakeCatalog())

        assertFalse(emptyController.state.hasSavedConfiguration)
        assertNull(emptyController.state.errorMessage)
        assertEquals(0, emptyStore.loadCalls)

        val corruptStore = FakeStore(metadataFailure = ConfigurationStoreException("metadata-secret"))
        val corruptController = DeepSeekSettingsController(corruptStore, FakeCatalog())

        assertFalse(corruptController.state.hasSavedConfiguration)
        assertEquals("DeepSeek 配置无效", corruptController.state.errorMessage)
        assertEquals(0, corruptStore.loadCalls)
        assertSecretIsNotObservable(corruptController)
    }

    @Test
    fun `新密钥验证成功优先选择 v4 pro 且未保存`() {
        val store = FakeStore()
        val controller = DeepSeekSettingsController(
            store,
            FakeCatalog(models = listOf(model("other"), model("deepseek-v4-pro"))),
        )

        controller.beginVerification()
        controller.verifyAndLoadModels("candidate-secret")

        assertEquals("deepseek-v4-pro", controller.state.selectedModelId)
        assertTrue(controller.state.canSave)
        assertEquals(0, store.saveCalls)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `缺少 v4 pro 时验证选择第一个模型`() {
        val controller = DeepSeekSettingsController(
            FakeStore(),
            FakeCatalog(models = listOf(model("first"), model("second"))),
        )

        controller.verifyAndLoadModels("candidate-secret")

        assertEquals("first", controller.state.selectedModelId)
    }

    @Test
    fun `空候选密钥使用已保存配置刷新模型`() {
        val store = FakeStore(configuration = DeepSeekConfiguration("saved", "saved-secret"))
        val catalog = FakeCatalog(models = listOf(model("fresh")))
        val controller = DeepSeekSettingsController(store, catalog)

        controller.verifyAndLoadModels("  ")

        assertEquals("saved-secret", catalog.receivedApiKey)
        assertEquals(1, store.loadCalls)
        assertEquals("fresh", controller.state.selectedModelId)
        assertTrue(controller.state.canSave)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `无候选或已保存密钥时提示输入`() {
        val controller = DeepSeekSettingsController(FakeStore(), FakeCatalog())

        controller.verifyAndLoadModels(null)

        assertEquals("请输入 DeepSeek API Key", controller.state.errorMessage)
        assertFalse(controller.state.isLoading)
        assertFalse(controller.state.canSave)
    }

    @Test
    fun `目录错误映射并且不覆盖旧配置`() {
        val cases = listOf(
            ModelCatalogException.InvalidApiKey() to "API Key 无效",
            ModelCatalogException.InsufficientBalance() to "DeepSeek 账户余额不足",
            ModelCatalogException.RateLimited() to "请求过于频繁，请稍后重试",
            IllegalStateException("internal-secret") to "无法验证 DeepSeek 配置",
        )
        cases.forEach { (failure, message) ->
            val controller = DeepSeekSettingsController(
                FakeStore(configuration = DeepSeekConfiguration("old-model", "saved-secret")),
                FakeCatalog(failure = failure),
            )

            controller.verifyAndLoadModels("candidate-secret")

            assertTrue(controller.state.hasSavedConfiguration)
            assertEquals("old-model", controller.state.configuredModelId)
            assertEquals(message, controller.state.errorMessage)
            assertFalse(controller.state.canSave)
            assertSecretIsNotObservable(controller)
        }
    }

    @Test
    fun `未经验证不能保存`() {
        val store = FakeStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog())

        controller.saveVerifiedConfiguration()

        assertEquals(0, store.saveCalls)
        assertFalse(controller.state.hasSavedConfiguration)
        assertFalse(controller.state.canSave)
    }

    @Test
    fun `验证选择模型后保存并且不能重复保存`() {
        val store = FakeStore()
        val controller = DeepSeekSettingsController(
            store,
            FakeCatalog(models = listOf(model("first"), model("second"))),
        )
        controller.verifyAndLoadModels("candidate-secret")
        controller.selectModel("second")

        controller.saveVerifiedConfiguration()

        assertEquals(DeepSeekConfiguration("second", "candidate-secret"), store.savedConfiguration)
        assertTrue(controller.state.hasSavedConfiguration)
        assertEquals("second", controller.state.configuredModelId)
        assertFalse(controller.state.canSave)
        assertTrue(controller.state.operationSucceeded)
        controller.saveVerifiedConfiguration()
        assertEquals(1, store.saveCalls)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `选择不存在模型后不得保存`() {
        val store = FakeStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("available"))))
        controller.verifyAndLoadModels("candidate-secret")

        controller.selectModel("missing")
        controller.saveVerifiedConfiguration()

        assertEquals(0, store.saveCalls)
        assertEquals("available", controller.state.selectedModelId)
    }

    @Test
    fun `候选验证失败清除可保存状态和模型`() {
        val controller = DeepSeekSettingsController(
            FakeStore(),
            FakeCatalog(failure = ModelCatalogException.InvalidApiKey()),
        )

        controller.verifyAndLoadModels("candidate-secret")

        assertTrue(controller.state.models.isEmpty())
        assertFalse(controller.state.canSave)
        assertEquals("API Key 无效", controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `保存失败保留旧配置元数据并清除验证密钥`() {
        val store = FakeStore(
            configuration = DeepSeekConfiguration("old-model", "saved-secret"),
            saveFailure = ConfigurationStoreException("无法保存 DeepSeek 配置"),
        )
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("new-model"))))
        controller.verifyAndLoadModels("candidate-secret")

        controller.saveVerifiedConfiguration()

        assertTrue(controller.state.hasSavedConfiguration)
        assertEquals("old-model", controller.state.configuredModelId)
        assertFalse(controller.state.canSave)
        assertEquals("无法保存 DeepSeek 配置", controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `清除成功完全重置且失败保留旧元数据`() {
        val successfulStore = FakeStore(configuration = DeepSeekConfiguration("saved", "saved-secret"))
        val successfulController = DeepSeekSettingsController(successfulStore, FakeCatalog())

        successfulController.clearConfiguration()

        assertFalse(successfulController.state.hasSavedConfiguration)
        assertNull(successfulController.state.configuredModelId)
        assertNull(successfulController.state.selectedModelId)
        assertTrue(successfulController.state.models.isEmpty())
        assertNull(successfulController.state.errorMessage)
        assertTrue(successfulController.state.operationSucceeded)

        val failingController = DeepSeekSettingsController(
            FakeStore(
                configuration = DeepSeekConfiguration("saved", "saved-secret"),
                clearFailure = ConfigurationStoreException("无法清除 DeepSeek 配置"),
            ),
            FakeCatalog(),
        )
        failingController.clearConfiguration()

        assertTrue(failingController.state.hasSavedConfiguration)
        assertEquals("saved", failingController.state.configuredModelId)
        assertEquals("无法清除 DeepSeek 配置", failingController.state.errorMessage)
    }

    @Test
    fun `loading 时重复操作被忽略`() {
        val store = FakeStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog())

        controller.beginVerification()
        controller.beginVerification()
        controller.saveVerifiedConfiguration()
        controller.clearConfiguration()

        assertTrue(controller.state.isLoading)
        assertEquals(0, store.saveCalls)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun `验证进行时并发验证被忽略`() {
        val catalog = BlockingCatalog()
        val controller = DeepSeekSettingsController(FakeStore(), catalog)
        val executor = Executors.newFixedThreadPool(2)
        try {
            controller.beginVerification()
            val first = executor.submit { controller.verifyAndLoadModels("first-secret") }
            assertTrue(catalog.firstCallEntered.await(1, TimeUnit.SECONDS))

            val duplicateCompleted = CountDownLatch(1)
            val duplicate = executor.submit {
                controller.verifyAndLoadModels("second-secret")
                duplicateCompleted.countDown()
            }
            assertTrue(duplicateCompleted.await(200, TimeUnit.MILLISECONDS))

            catalog.releaseFirstCall.countDown()
            first.get(1, TimeUnit.SECONDS)
            duplicate.get(1, TimeUnit.SECONDS)
            assertEquals(1, catalog.calls)
        } finally {
            catalog.releaseFirstCall.countDown()
            executor.shutdownNow()
        }
    }

    private fun model(id: String) = AvailableModel(id, isDeprecated = false)

    private fun assertSecretIsNotObservable(controller: DeepSeekSettingsController) {
        val observable = controller.state.toString() + controller.state.errorMessage.orEmpty()
        listOf("candidate-secret", "saved-secret", "internal-secret").forEach { secret ->
            assertFalse(observable.contains(secret))
        }
    }

    private class FakeStore(
        private var configuration: DeepSeekConfiguration? = null,
        private val metadataFailure: Throwable? = null,
        private val loadFailure: Throwable? = null,
        private val saveFailure: Throwable? = null,
        private val clearFailure: Throwable? = null,
    ) : DeepSeekConfigurationStore {
        var configuredModelIdCalls = 0
        var loadCalls = 0
        var saveCalls = 0
        var clearCalls = 0
        var savedConfiguration: DeepSeekConfiguration? = null

        override fun load(): DeepSeekConfiguration? {
            loadCalls += 1
            loadFailure?.let { throw it }
            return configuration
        }

        override fun configuredModelId(): String? {
            configuredModelIdCalls += 1
            metadataFailure?.let { throw it }
            return configuration?.modelId
        }

        override fun save(configuration: DeepSeekConfiguration) {
            saveCalls += 1
            saveFailure?.let { throw it }
            this.configuration = configuration
            savedConfiguration = configuration
        }

        override fun clear() {
            clearCalls += 1
            clearFailure?.let { throw it }
            configuration = null
        }
    }

    private class FakeCatalog(
        private val models: List<AvailableModel> = emptyList(),
        private val failure: Throwable? = null,
    ) : ModelCatalog {
        var receivedApiKey: String? = null

        override fun listModels(apiKey: String): List<AvailableModel> {
            receivedApiKey = apiKey
            failure?.let { throw it }
            return models
        }
    }

    private class BlockingCatalog : ModelCatalog {
        val firstCallEntered = CountDownLatch(1)
        val releaseFirstCall = CountDownLatch(1)
        var calls = 0

        override fun listModels(apiKey: String): List<AvailableModel> {
            calls += 1
            if (calls == 1) {
                firstCallEntered.countDown()
                check(releaseFirstCall.await(1, TimeUnit.SECONDS))
            }
            return listOf(AvailableModel("model", isDeprecated = false))
        }
    }
}
