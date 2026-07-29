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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class DeepSeekSettingsControllerTest {
    @Test
    fun `构造时不读取存储且初始化仅读取模型元数据`() {
        val store = FakeStore(configuration = DeepSeekConfiguration("saved-model", "saved-secret"))

        val controller = DeepSeekSettingsController(store, FakeCatalog())

        assertEquals(0, store.configuredModelIdCalls)
        assertEquals(0, store.loadCalls)
        controller.beginInitialization()
        controller.initializeMetadata()

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
        assertEquals(0, emptyStore.configuredModelIdCalls)
        emptyController.beginInitialization()
        emptyController.initializeMetadata()
        assertEquals(1, emptyStore.configuredModelIdCalls)

        val corruptStore = FakeStore(metadataFailure = ConfigurationStoreException("metadata-secret"))
        val corruptController = DeepSeekSettingsController(corruptStore, FakeCatalog())

        assertEquals(0, corruptStore.configuredModelIdCalls)
        assertEquals(0, corruptStore.loadCalls)
        corruptController.beginInitialization()
        corruptController.initializeMetadata()
        assertFalse(corruptController.state.hasSavedConfiguration)
        assertEquals("DeepSeek 配置无效", corruptController.state.errorMessage)
        assertEquals(0, corruptStore.loadCalls)
        assertSecretIsNotObservable(corruptController)
    }

    @Test
    fun `初始化取消会结束 loading 并继续抛出取消`() {
        val controller = DeepSeekSettingsController(
            FakeStore(metadataFailure = CancellationException("initialization-secret")),
            FakeCatalog(),
        )
        assertTrue(controller.beginInitialization() != null)

        assertThrows(CancellationException::class.java) {
            controller.initializeMetadata()
        }

        assertFalse(controller.state.isLoading)
        assertFalse(controller.state.hasSavedConfiguration)
        assertFalse(controller.state.canSave)
        assertSecretIsNotObservable(controller)
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
    fun `缺少 v4 pro 时自动选择首个未弃用模型`() {
        val controller = DeepSeekSettingsController(
            FakeStore(),
            FakeCatalog(
                models = listOf(
                    AvailableModel("deepseek-chat", isDeprecated = true),
                    model("deepseek-v4-flash"),
                ),
            ),
        )

        controller.verifyAndLoadModels("candidate-secret")

        assertEquals("deepseek-v4-flash", controller.state.selectedModelId)
        assertTrue(controller.state.canSave)
    }

    @Test
    fun `仅有弃用模型时等待用户主动选择`() {
        val controller = DeepSeekSettingsController(
            FakeStore(),
            FakeCatalog(
                models = listOf(
                    AvailableModel("deepseek-chat", isDeprecated = true),
                    AvailableModel("deepseek-reasoner", isDeprecated = true),
                ),
            ),
        )

        controller.verifyAndLoadModels("candidate-secret")

        assertNull(controller.state.selectedModelId)
        assertFalse(controller.state.canSave)
        controller.selectModel("deepseek-reasoner")
        assertEquals("deepseek-reasoner", controller.state.selectedModelId)
        assertTrue(controller.state.canSave)
    }

    @Test
    fun `空候选密钥使用已保存配置刷新模型`() {
        val store = FakeStore(configuration = DeepSeekConfiguration("saved", "saved-secret"))
        val catalog = FakeCatalog(models = listOf(model("fresh")))
        val controller = DeepSeekSettingsController(store, catalog)
        initialize(controller)

        controller.verifyAndLoadModels("  ")

        assertEquals("saved-secret", catalog.receivedApiKey)
        assertEquals(1, store.loadCalls)
        assertEquals("fresh", controller.state.selectedModelId)
        assertTrue(controller.state.canSave)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `载密临时对象与 controller 字符串表示不暴露密钥`() {
        val secret = "temporary-marker-secret"
        val privateValues = listOf(
            "VerificationSource" to arrayOf<Any>(secret, false),
            "SaveRequest" to arrayOf<Any>(secret, "model"),
        ).map { (simpleName, arguments) ->
            val type = DeepSeekSettingsController::class.java.declaredClasses
                .single { it.simpleName == simpleName }
            type.declaredConstructors.single { it.parameterCount == arguments.size }.run {
                isAccessible = true
                newInstance(*arguments)
            }
        }
        val controller = DeepSeekSettingsController(
            FakeStore(),
            FakeCatalog(models = listOf(model("model"))),
        )
        controller.verifyAndLoadModels(secret)

        privateValues.forEach { value ->
            assertFalse(value.toString().contains(secret))
        }
        assertFalse(controller.toString().contains(secret))
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
            initialize(controller)

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
        val initialRevision = controller.state.configurationRevision

        controller.saveVerifiedConfiguration()

        assertEquals(0, store.saveCalls)
        assertEquals(initialRevision, controller.state.configurationRevision)
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
        assertEquals(SettingsSuccess.SAVED, controller.consumeSuccess())
        assertNull(controller.consumeSuccess())
        controller.saveVerifiedConfiguration()
        assertEquals(1, store.saveCalls)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `同模型再次保存新密钥递增配置修订且消费成功事件后保持`() {
        val controller = DeepSeekSettingsController(
            FakeStore(configuration = DeepSeekConfiguration("same-model", "old-secret")),
            FakeCatalog(models = listOf(model("same-model"))),
        )
        initialize(controller)
        val initialRevision = controller.state.configurationRevision
        controller.verifyAndLoadModels("new-secret")

        controller.saveVerifiedConfiguration()

        assertEquals("same-model", controller.state.configuredModelId)
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
        assertEquals(SettingsSuccess.SAVED, controller.consumeSuccess())
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
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
    fun `取消验证立即丢弃旧候选并且不得保存`() {
        val store = FakeStore(configuration = DeepSeekConfiguration("saved-model", "saved-secret"))
        val controller = DeepSeekSettingsController(store, FirstSuccessThenCancelledCatalog())
        initialize(controller)
        controller.verifyAndLoadModels("first-candidate-secret")

        assertTrue(controller.state.canSave)
        controller.beginVerification()

        assertTrue(controller.state.models.isEmpty())
        assertEquals("saved-model", controller.state.selectedModelId)
        assertFalse(controller.state.canSave)
        assertThrows(CancellationException::class.java) {
            controller.verifyAndLoadModels("second-candidate-secret")
        }

        assertTrue(controller.state.hasSavedConfiguration)
        assertEquals("saved-model", controller.state.configuredModelId)
        assertTrue(controller.state.models.isEmpty())
        assertEquals("saved-model", controller.state.selectedModelId)
        assertFalse(controller.state.isLoading)
        assertFalse(controller.state.canSave)
        controller.selectModel("first-model")
        controller.saveVerifiedConfiguration()
        assertEquals(0, store.saveCalls)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `保存失败保留旧配置元数据并清除验证密钥`() {
        val store = FakeStore(
            configuration = DeepSeekConfiguration("old-model", "saved-secret"),
            saveFailure = ConfigurationStoreException("无法保存 DeepSeek 配置"),
        )
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("new-model"))))
        initialize(controller)
        val initialRevision = controller.state.configurationRevision
        controller.verifyAndLoadModels("candidate-secret")

        controller.saveVerifiedConfiguration()

        assertTrue(controller.state.hasSavedConfiguration)
        assertEquals("old-model", controller.state.configuredModelId)
        assertEquals("old-model", controller.state.selectedModelId)
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
        assertEquals(2, store.configuredModelIdCalls)
        assertFalse(controller.state.canSave)
        assertEquals("无法保存 DeepSeek 配置", controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `保存写入新元数据后抛错按新配置对账`() {
        val store = FakeStore(
            configuration = DeepSeekConfiguration("old-model", "saved-secret"),
            saveFailure = ConfigurationStoreException("无法保存 DeepSeek 配置"),
            saveConfigurationBeforeFailure = true,
        )
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("new-model"))))
        initialize(controller)
        val initialRevision = controller.state.configurationRevision
        controller.verifyAndLoadModels("candidate-secret")

        controller.saveVerifiedConfiguration()

        assertTrue(controller.state.hasSavedConfiguration)
        assertEquals("new-model", controller.state.configuredModelId)
        assertEquals("new-model", controller.state.selectedModelId)
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
        assertEquals(2, store.configuredModelIdCalls)
        assertEquals("无法保存 DeepSeek 配置", controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `保存抛错且元数据重读失败时安全置为未配置`() {
        val store = FakeStore(
            configuration = DeepSeekConfiguration("old-model", "saved-secret"),
            saveFailure = ConfigurationStoreException("无法保存 DeepSeek 配置"),
        )
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("new-model"))))
        initialize(controller)
        val initialRevision = controller.state.configurationRevision
        store.metadataFailure = ConfigurationStoreException("metadata-secret")
        controller.verifyAndLoadModels("candidate-secret")

        controller.saveVerifiedConfiguration()

        assertFalse(controller.state.hasSavedConfiguration)
        assertNull(controller.state.configuredModelId)
        assertNull(controller.state.selectedModelId)
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
        assertEquals(2, store.configuredModelIdCalls)
        assertEquals("无法保存 DeepSeek 配置", controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `保存失败后元数据重读取消仍安全对账并传播取消`() {
        val store = FakeStore(
            configuration = DeepSeekConfiguration("old-model", "saved-secret"),
            saveFailure = ConfigurationStoreException("无法保存 DeepSeek 配置"),
        )
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("new-model"))))
        initialize(controller)
        val initialRevision = controller.state.configurationRevision
        store.metadataFailure = CancellationException("metadata-cancel-secret")
        controller.verifyAndLoadModels("candidate-secret")

        assertThrows(CancellationException::class.java) {
            controller.saveVerifiedConfiguration()
        }

        assertFalse(controller.state.hasSavedConfiguration)
        assertNull(controller.state.configuredModelId)
        assertNull(controller.state.selectedModelId)
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
        assertEquals(2, store.configuredModelIdCalls)
        assertEquals("无法保存 DeepSeek 配置", controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `保存写入后取消仍对账并推进修订`() {
        val store = FakeStore(
            configuration = DeepSeekConfiguration("old-model", "saved-secret"),
            saveFailure = CancellationException("save-cancel-secret"),
            saveConfigurationBeforeFailure = true,
        )
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("new-model"))))
        initialize(controller)
        val initialRevision = controller.state.configurationRevision
        controller.verifyAndLoadModels("candidate-secret")

        assertThrows(CancellationException::class.java) {
            controller.saveVerifiedConfiguration()
        }

        assertEquals("new-model", controller.state.configuredModelId)
        assertEquals("new-model", controller.state.selectedModelId)
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
        assertEquals(2, store.configuredModelIdCalls)
        assertNull(controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `清除成功完全重置且失败保留旧元数据`() {
        val successfulStore = FakeStore(configuration = DeepSeekConfiguration("saved", "saved-secret"))
        val successfulController = DeepSeekSettingsController(successfulStore, FakeCatalog())
        initialize(successfulController)
        val initialRevision = successfulController.state.configurationRevision

        successfulController.clearConfiguration()

        assertFalse(successfulController.state.hasSavedConfiguration)
        assertNull(successfulController.state.configuredModelId)
        assertNull(successfulController.state.selectedModelId)
        assertTrue(successfulController.state.models.isEmpty())
        assertNull(successfulController.state.errorMessage)
        assertEquals(initialRevision + 1, successfulController.state.configurationRevision)
        assertEquals(SettingsSuccess.CLEARED, successfulController.consumeSuccess())
        assertEquals(initialRevision + 1, successfulController.state.configurationRevision)
        assertNull(successfulController.consumeSuccess())

        val failingController = DeepSeekSettingsController(
            FakeStore(
                configuration = DeepSeekConfiguration("saved", "saved-secret"),
                clearFailure = ConfigurationStoreException("无法清除 DeepSeek 配置"),
            ),
            FakeCatalog(),
        )
        initialize(failingController)
        val failingInitialRevision = failingController.state.configurationRevision
        failingController.clearConfiguration()

        assertTrue(failingController.state.hasSavedConfiguration)
        assertEquals("saved", failingController.state.configuredModelId)
        assertEquals("saved", failingController.state.selectedModelId)
        assertEquals(failingInitialRevision + 1, failingController.state.configurationRevision)
        assertEquals("无法清除 DeepSeek 配置", failingController.state.errorMessage)
    }

    @Test
    fun `清除元数据后删除密钥失败按未配置对账并推进修订`() {
        val store = FakeStore(
            configuration = DeepSeekConfiguration("saved", "saved-secret"),
            clearFailure = ConfigurationStoreException("无法清除 DeepSeek 配置"),
            clearConfigurationBeforeFailure = true,
        )
        val controller = DeepSeekSettingsController(store, FakeCatalog())
        initialize(controller)
        val initialRevision = controller.state.configurationRevision

        controller.clearConfiguration()

        assertFalse(controller.state.hasSavedConfiguration)
        assertNull(controller.state.configuredModelId)
        assertNull(controller.state.selectedModelId)
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
        assertEquals(2, store.configuredModelIdCalls)
        assertEquals("无法清除 DeepSeek 配置", controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `清除失败后元数据重读取消仍安全对账并传播取消`() {
        val store = FakeStore(
            configuration = DeepSeekConfiguration("saved", "saved-secret"),
            clearFailure = ConfigurationStoreException("无法清除 DeepSeek 配置"),
        )
        val controller = DeepSeekSettingsController(store, FakeCatalog())
        initialize(controller)
        val initialRevision = controller.state.configurationRevision
        store.metadataFailure = CancellationException("metadata-cancel-secret")

        assertThrows(CancellationException::class.java) {
            controller.clearConfiguration()
        }

        assertFalse(controller.state.hasSavedConfiguration)
        assertNull(controller.state.configuredModelId)
        assertNull(controller.state.selectedModelId)
        assertEquals(initialRevision + 1, controller.state.configurationRevision)
        assertEquals(2, store.configuredModelIdCalls)
        assertEquals("无法清除 DeepSeek 配置", controller.state.errorMessage)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `失败结果元数据对账不持锁且过期 token 不得回写`() {
        val store = BlockingFailureReconciliationStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("new-model"))))
        initialize(controller)
        controller.verifyAndLoadModels("candidate-secret")
        val initialRevision = controller.state.configurationRevision
        val token = controller.beginSaving()!!
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = executor.submit { controller.saveVerifiedConfiguration(token) }
            assertTrue(store.reconciliationReadEntered.await(1, TimeUnit.SECONDS))

            val lock = controllerLock(controller)
            assertTrue(lock.tryLock())
            lock.unlock()
            controller.cancelPendingOperation(token)

            store.releaseReconciliationRead.countDown()
            worker.get(1, TimeUnit.SECONDS)
            assertEquals(initialRevision, controller.state.configurationRevision)
            assertEquals("old-model", controller.state.configuredModelId)
            assertNull(controller.state.errorMessage)
        } finally {
            store.releaseReconciliationRead.countDown()
            executor.shutdownNow()
        }
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

            val duplicate = executor.submit<Long?> { controller.beginVerification() }
            assertNull(duplicate.get(1, TimeUnit.SECONDS))

            catalog.releaseFirstCall.countDown()
            first.get(1, TimeUnit.SECONDS)
            assertEquals(1, catalog.calls)
        } finally {
            catalog.releaseFirstCall.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `已预约验证 worker 在锁暂时被占用后仍会执行`() {
        val controller = DeepSeekSettingsController(
            FakeStore(),
            FakeCatalog(models = listOf(model("verified-model"))),
        )
        val lock = controllerLock(controller)
        val executor = Executors.newFixedThreadPool(2)
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val workerCompleted = CountDownLatch(1)
        try {
            controller.beginVerification()
            val holder = executor.submit {
                lock.lock()
                try {
                    lockHeld.countDown()
                    check(releaseLock.await(1, TimeUnit.SECONDS))
                } finally {
                    lock.unlock()
                }
            }
            assertTrue(lockHeld.await(1, TimeUnit.SECONDS))

            val worker = executor.submit {
                controller.verifyAndLoadModels("candidate-secret")
                workerCompleted.countDown()
            }
            assertFalse(workerCompleted.await(200, TimeUnit.MILLISECONDS))

            releaseLock.countDown()
            holder.get(1, TimeUnit.SECONDS)
            worker.get(1, TimeUnit.SECONDS)
            assertTrue(workerCompleted.await(1, TimeUnit.SECONDS))
            assertFalse(controller.state.isLoading)
            assertTrue(controller.state.canSave)
        } finally {
            releaseLock.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `丢弃候选不会等待阻塞验证且旧 worker 不得回写`() {
        val catalog = BlockingModelCatalog()
        val controller = DeepSeekSettingsController(FakeStore(), catalog)
        val executor = Executors.newFixedThreadPool(2)
        val discardReturned = CountDownLatch(1)
        try {
            controller.beginVerification()
            val worker = executor.submit { controller.verifyAndLoadModels("candidate-secret") }
            assertTrue(catalog.callEntered.await(1, TimeUnit.SECONDS))

            val discard = executor.submit {
                controller.discardCandidateConfiguration()
                discardReturned.countDown()
            }
            assertTrue(discardReturned.await(200, TimeUnit.MILLISECONDS))
            assertFalse(controller.state.isLoading)
            assertTrue(controller.state.models.isEmpty())
            assertFalse(controller.state.canSave)

            catalog.releaseCall.countDown()
            discard.get(1, TimeUnit.SECONDS)
            worker.get(1, TimeUnit.SECONDS)
            assertTrue(controller.state.models.isEmpty())
            assertFalse(controller.state.canSave)
        } finally {
            catalog.releaseCall.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `陈旧初始化结果不得覆盖较新验证状态`() {
        val store = BlockingMetadataStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("new-model"))))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val initializationToken = controller.beginInitialization()!!
            val initialization = executor.submit { controller.initializeMetadata(initializationToken) }
            assertTrue(store.readEntered.await(1, TimeUnit.SECONDS))

            controller.cancelPendingOperation(initializationToken)
            controller.verifyAndLoadModels("candidate-secret")
            assertEquals("new-model", controller.state.selectedModelId)
            assertTrue(controller.state.canSave)

            store.releaseRead.countDown()
            initialization.get(1, TimeUnit.SECONDS)
            assertEquals("new-model", controller.state.selectedModelId)
            assertTrue(controller.state.canSave)
            assertFalse(controller.state.hasSavedConfiguration)
        } finally {
            store.releaseRead.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `离页丢弃不取消阻塞初始化的最终对账`() {
        val store = BlockingMetadataStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val token = controller.beginInitialization()!!
            val worker = executor.submit { controller.initializeMetadata(token) }
            assertTrue(store.readEntered.await(1, TimeUnit.SECONDS))
            assertFalse(controller.discardCandidateConfiguration())
            assertTrue(controller.state.isLoading)
            store.releaseRead.countDown()
            worker.get(1, TimeUnit.SECONDS)
            assertTrue(controller.state.hasSavedConfiguration)
            assertEquals("stale-model", controller.state.configuredModelId)
        } finally {
            store.releaseRead.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `离页丢弃不取消阻塞保存的最终对账`() {
        val store = BlockingSaveStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("model"))))
        val executor = Executors.newSingleThreadExecutor()
        try {
            controller.verifyAndLoadModels("candidate-secret")
            val token = controller.beginSaving()!!
            val worker = executor.submit { controller.saveVerifiedConfiguration(token) }
            assertTrue(store.saveEntered.await(1, TimeUnit.SECONDS))
            assertFalse(controller.discardCandidateConfiguration())
            store.releaseSave.countDown()
            worker.get(1, TimeUnit.SECONDS)
            assertTrue(controller.state.hasSavedConfiguration)
            assertEquals("model", controller.state.configuredModelId)
        } finally {
            store.releaseSave.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `离页丢弃不取消阻塞清除的最终对账`() {
        val store = BlockingClearStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog())
        val executor = Executors.newSingleThreadExecutor()
        try {
            initialize(controller)
            val token = controller.beginClearing()!!
            val worker = executor.submit { controller.clearConfiguration(token) }
            assertTrue(store.clearEntered.await(1, TimeUnit.SECONDS))
            assertFalse(controller.discardCandidateConfiguration())
            store.releaseClear.countDown()
            worker.get(1, TimeUnit.SECONDS)
            assertFalse(controller.state.hasSavedConfiguration)
            assertEquals(SettingsSuccess.CLEARED, controller.consumeSuccess())
        } finally {
            store.releaseClear.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `刷新旧密钥在读取后被丢弃时不调用目录`() {
        val store = BlockingLoadStore()
        val catalog = CountingCatalog()
        val controller = DeepSeekSettingsController(store, catalog)
        val executor = Executors.newSingleThreadExecutor()
        try {
            initialize(controller)
            val token = controller.beginVerification()!!
            val worker = executor.submit { controller.verifyAndLoadModels(null, token) }
            assertTrue(store.loadEntered.await(1, TimeUnit.SECONDS))
            assertTrue(controller.discardCandidateConfiguration())
            store.releaseLoad.countDown()
            worker.get(1, TimeUnit.SECONDS)
            assertEquals(0, catalog.calls)
            assertTrue(controller.state.models.isEmpty())
            assertFalse(controller.state.canSave)
        } finally {
            store.releaseLoad.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `idle 状态并发无 token 验证只调用一次目录`() {
        val catalog = BlockingCountingCatalog()
        val controller = DeepSeekSettingsController(FakeStore(), catalog)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val oneWorkerReturned = CountDownLatch(1)
        try {
            val first = executor.submit {
                ready.countDown()
                start.await()
                controller.verifyAndLoadModels("candidate-secret")
                oneWorkerReturned.countDown()
            }
            val duplicate = executor.submit {
                ready.countDown()
                start.await()
                controller.verifyAndLoadModels("candidate-secret")
                oneWorkerReturned.countDown()
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(catalog.firstCallEntered.await(1, TimeUnit.SECONDS))
            assertTrue(oneWorkerReturned.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1, catalog.calls)

            catalog.releaseFirstCall.countDown()
            first.get(1, TimeUnit.SECONDS)
            duplicate.get(1, TimeUnit.SECONDS)
            assertEquals(1, catalog.calls)
        } finally {
            catalog.releaseFirstCall.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `idle 状态并发无 token 保存只调用一次存储`() {
        val store = BlockingSaveStore()
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("model"))))
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val oneWorkerReturned = CountDownLatch(1)
        try {
            controller.verifyAndLoadModels("candidate-secret")
            val first = executor.submit {
                ready.countDown()
                start.await()
                controller.saveVerifiedConfiguration()
                oneWorkerReturned.countDown()
            }
            val duplicate = executor.submit {
                ready.countDown()
                start.await()
                controller.saveVerifiedConfiguration()
                oneWorkerReturned.countDown()
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(store.saveEntered.await(1, TimeUnit.SECONDS))
            assertTrue(oneWorkerReturned.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1, store.saveCalls)

            store.releaseSave.countDown()
            first.get(1, TimeUnit.SECONDS)
            duplicate.get(1, TimeUnit.SECONDS)
            assertEquals(1, store.saveCalls)
        } finally {
            store.releaseSave.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `丢弃候选配置后无法保存且不保留模型`() {
        val store = FakeStore(configuration = DeepSeekConfiguration("saved-model", "saved-secret"))
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("candidate-model"))))
        initialize(controller)
        controller.verifyAndLoadModels("candidate-secret")

        controller.discardCandidateConfiguration()

        assertTrue(controller.state.models.isEmpty())
        assertEquals("saved-model", controller.state.selectedModelId)
        assertFalse(controller.state.canSave)
        controller.saveVerifiedConfiguration()
        assertEquals(0, store.saveCalls)
        assertSecretIsNotObservable(controller)
    }

    @Test
    fun `刷新已保存密钥后的候选也可被丢弃`() {
        val store = FakeStore(configuration = DeepSeekConfiguration("saved-model", "saved-secret"))
        val controller = DeepSeekSettingsController(store, FakeCatalog(models = listOf(model("fresh-model"))))
        initialize(controller)
        controller.verifyAndLoadModels(null)

        controller.discardCandidateConfiguration()

        assertEquals(1, store.loadCalls)
        assertTrue(controller.state.models.isEmpty())
        assertFalse(controller.state.canSave)
        controller.saveVerifiedConfiguration()
        assertEquals(0, store.saveCalls)
        assertSecretIsNotObservable(controller)
    }

    private fun model(id: String) = AvailableModel(id, isDeprecated = false)

    private fun initialize(controller: DeepSeekSettingsController) {
        assertTrue(controller.beginInitialization() != null)
        controller.initializeMetadata()
    }

    private fun controllerLock(controller: DeepSeekSettingsController): ReentrantLock =
        DeepSeekSettingsController::class.java.getDeclaredField("operationLock").run {
            isAccessible = true
            get(controller) as ReentrantLock
        }

    private fun assertSecretIsNotObservable(controller: DeepSeekSettingsController) {
        val observable = controller.state.toString() + controller.state.errorMessage.orEmpty()
        listOf(
            "candidate-secret",
            "saved-secret",
            "internal-secret",
            "metadata-secret",
            "metadata-cancel-secret",
            "save-cancel-secret",
            "temporary-marker-secret",
        ).forEach { secret ->
            assertFalse(observable.contains(secret))
        }
    }

    private class FakeStore(
        private var configuration: DeepSeekConfiguration? = null,
        var metadataFailure: Throwable? = null,
        private val saveFailure: Throwable? = null,
        private val clearFailure: Throwable? = null,
        private val saveConfigurationBeforeFailure: Boolean = false,
        private val clearConfigurationBeforeFailure: Boolean = false,
    ) : DeepSeekConfigurationStore {
        var configuredModelIdCalls = 0
        var loadCalls = 0
        var saveCalls = 0
        var clearCalls = 0
        var savedConfiguration: DeepSeekConfiguration? = null

        override fun load(): DeepSeekConfiguration? {
            loadCalls += 1
            return configuration
        }

        override fun configuredModelId(): String? {
            configuredModelIdCalls += 1
            metadataFailure?.let { throw it }
            return configuration?.modelId
        }

        override fun save(configuration: DeepSeekConfiguration) {
            saveCalls += 1
            if (saveConfigurationBeforeFailure) {
                this.configuration = configuration
            }
            saveFailure?.let { throw it }
            this.configuration = configuration
            savedConfiguration = configuration
        }

        override fun clear() {
            clearCalls += 1
            if (clearConfigurationBeforeFailure) {
                configuration = null
            }
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

    private class FirstSuccessThenCancelledCatalog : ModelCatalog {
        private var calls = 0

        override fun listModels(apiKey: String): List<AvailableModel> = when (++calls) {
            1 -> listOf(AvailableModel("first-model", isDeprecated = false))
            else -> throw CancellationException("cancel-secret")
        }
    }

    private class BlockingModelCatalog : ModelCatalog {
        val callEntered = CountDownLatch(1)
        val releaseCall = CountDownLatch(1)

        override fun listModels(apiKey: String): List<AvailableModel> {
            callEntered.countDown()
            check(releaseCall.await(1, TimeUnit.SECONDS))
            return listOf(AvailableModel("late-model", isDeprecated = false))
        }
    }

    private class BlockingMetadataStore : DeepSeekConfigurationStore {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)

        override fun load(): DeepSeekConfiguration? = null

        override fun configuredModelId(): String? {
            readEntered.countDown()
            check(releaseRead.await(1, TimeUnit.SECONDS))
            return "stale-model"
        }

        override fun save(configuration: DeepSeekConfiguration) = Unit

        override fun clear() = Unit
    }

    private class BlockingFailureReconciliationStore : DeepSeekConfigurationStore {
        val reconciliationReadEntered = CountDownLatch(1)
        val releaseReconciliationRead = CountDownLatch(1)
        private var configuredModelIdCalls = 0

        override fun load(): DeepSeekConfiguration? = null

        override fun configuredModelId(): String {
            configuredModelIdCalls += 1
            if (configuredModelIdCalls > 1) {
                reconciliationReadEntered.countDown()
                check(releaseReconciliationRead.await(1, TimeUnit.SECONDS))
                return "new-model"
            }
            return "old-model"
        }

        override fun save(configuration: DeepSeekConfiguration) {
            throw ConfigurationStoreException("无法保存 DeepSeek 配置")
        }

        override fun clear() = Unit
    }

    private class BlockingCountingCatalog : ModelCatalog {
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

    private class BlockingSaveStore : DeepSeekConfigurationStore {
        val saveEntered = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        var saveCalls = 0

        override fun load(): DeepSeekConfiguration? = null

        override fun configuredModelId(): String? = null

        override fun save(configuration: DeepSeekConfiguration) {
            saveCalls += 1
            if (saveCalls == 1) {
                saveEntered.countDown()
                check(releaseSave.await(1, TimeUnit.SECONDS))
            }
        }

        override fun clear() = Unit
    }

    private class BlockingClearStore : DeepSeekConfigurationStore {
        val clearEntered = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)

        override fun load(): DeepSeekConfiguration? = null

        override fun configuredModelId(): String? = "saved-model"

        override fun save(configuration: DeepSeekConfiguration) = Unit

        override fun clear() {
            clearEntered.countDown()
            check(releaseClear.await(1, TimeUnit.SECONDS))
        }
    }

    private class BlockingLoadStore : DeepSeekConfigurationStore {
        val loadEntered = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)

        override fun load(): DeepSeekConfiguration? {
            loadEntered.countDown()
            check(releaseLoad.await(1, TimeUnit.SECONDS))
            return DeepSeekConfiguration("saved-model", "saved-secret")
        }

        override fun configuredModelId(): String? = "saved-model"

        override fun save(configuration: DeepSeekConfiguration) = Unit

        override fun clear() = Unit
    }

    private class CountingCatalog : ModelCatalog {
        var calls = 0

        override fun listModels(apiKey: String): List<AvailableModel> {
            calls += 1
            return listOf(AvailableModel("fresh-model", isDeprecated = false))
        }
    }
}
