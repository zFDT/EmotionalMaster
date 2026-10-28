package cn.echobird.emotionalmaster.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekConfigurationStoreTest {
    private lateinit var context: Context
    private lateinit var cipher: ReversibleTestCipher
    private lateinit var store: SharedPreferencesDeepSeekConfigurationStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences().edit().clear().commit()
        cipher = ReversibleTestCipher()
        store = SharedPreferencesDeepSeekConfigurationStore(context, cipher)
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun `保存后重建 store 仍可读取且 SharedPreferences 不含明文`() {
        val configuration = DeepSeekConfiguration("deepseek-chat", "sk-test-secret")

        store.save(configuration)
        val reloadedStore = SharedPreferencesDeepSeekConfigurationStore(context, cipher)

        assertEquals(configuration, reloadedStore.load())
        assertFalse(preferences().all.values.joinToString().contains(configuration.apiKey))
    }

    @Test
    fun `配置字符串表示始终脱敏 apiKey`() {
        val apiKey = "sk-secret-must-not-appear"

        val text = DeepSeekConfiguration("deepseek-chat", apiKey).toString()

        assertFalse(text.contains(apiKey))
        assertTrue(text.contains("apiKey=[REDACTED]"))
    }

    @Test
    fun `保存新配置覆盖旧配置`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "first-key"))

        store.save(DeepSeekConfiguration("deepseek-reasoner", "second-key"))

        assertEquals(
            DeepSeekConfiguration("deepseek-reasoner", "second-key"),
            store.load(),
        )
    }

    @Test
    fun `加密失败时保留旧配置`() {
        val oldConfiguration = DeepSeekConfiguration("deepseek-chat", "old-key")
        store.save(oldConfiguration)
        cipher.encryptFailure = IllegalStateException("encrypt-secret")

        val exception = assertThrows(ConfigurationStoreException::class.java) {
            store.save(DeepSeekConfiguration("deepseek-reasoner", "new-key"))
        }

        assertEquals("无法保存 DeepSeek 配置", exception.message)
        assertPublicExceptionDoesNotContain(exception, "encrypt-secret")
        assertEquals(oldConfiguration, store.load())
    }

    @Test
    fun `空或空白 modelId 在构造配置时被拒绝`() {
        listOf("", "   ").forEach { modelId ->
            assertThrows(IllegalArgumentException::class.java) {
                DeepSeekConfiguration(modelId, "key")
            }
        }
    }

    @Test
    fun `空或空白 apiKey 在构造配置时被拒绝`() {
        listOf("", "   ").forEach { apiKey ->
            assertThrows(IllegalArgumentException::class.java) {
                DeepSeekConfiguration("model", apiKey)
            }
        }
    }

    @Test
    fun `配置构造拒绝超长 modelId 和无效或超长 apiKey`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeepSeekConfiguration("m".repeat(129), "key")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeepSeekConfiguration("model", "\uD800")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeepSeekConfiguration("model", "k".repeat(4097))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeepSeekConfiguration("model", "k".repeat(100_000))
        }
    }

    @Test
    fun `Keystore cipher 将读取现有密钥与创建密钥分开`() {
        val methodNames = AndroidKeystoreSecretCipher::class.java.declaredMethods.map { it.name }

        assertTrue(methodNames.contains("getExistingKey"))
        assertTrue(methodNames.contains("getOrCreateKey"))
    }

    @Test
    fun `持久化的空白 modelId 被读取接口稳定拒绝`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        preferences().edit().putString(MODEL_ID, "   ").commit()

        assertInvalidConfiguration("   ") { store.load() }
        assertInvalidConfiguration("   ") { store.configuredModelId() }
    }

    @Test
    fun `解密得到空白 apiKey 时稳定拒绝且不泄露内容`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        cipher.decryptedOverride = "   ".toByteArray()

        assertInvalidConfiguration("   ") { store.load() }
    }

    @Test
    fun `读取无配置返回 null 而部分或损坏配置抛稳定异常`() {
        assertNull(store.load())

        preferences().edit()
            .putInt(CONFIGURATION_FORMAT_VERSION, FORMAT_VERSION)
            .putString(MODEL_ID, "deepseek-chat")
            .commit()

        assertInvalidConfiguration { store.load() }

        putStoredConfiguration(
            configurationVersion = FORMAT_VERSION + 1,
            cipherVersion = FORMAT_VERSION,
            ciphertext = "AQI=",
            iv = "AwQ=",
        )
        assertInvalidConfiguration { store.load() }

        putStoredConfiguration(
            configurationVersion = FORMAT_VERSION,
            cipherVersion = FORMAT_VERSION,
            ciphertext = "%%%",
            iv = "AwQ=",
        )
        assertInvalidConfiguration { store.load() }

        putStoredConfiguration(
            configurationVersion = FORMAT_VERSION,
            cipherVersion = FORMAT_VERSION,
            ciphertext = "",
            iv = "AwQ=",
        )
        assertInvalidConfiguration { store.load() }
    }

    @Test
    fun `读取拒绝短 envelope 和超长 Base64`() {
        val shortCiphertext = Base64.getEncoder().encodeToString(ByteArray(15))
        val validIv = Base64.getEncoder().encodeToString(ByteArray(12))
        putStoredConfiguration(FORMAT_VERSION, FORMAT_VERSION, shortCiphertext, validIv)
        assertInvalidConfiguration { store.load() }

        val validCiphertext = Base64.getEncoder().encodeToString(ByteArray(16))
        val shortIv = Base64.getEncoder().encodeToString(ByteArray(11))
        putStoredConfiguration(FORMAT_VERSION, FORMAT_VERSION, validCiphertext, shortIv)
        assertInvalidConfiguration { store.load() }

        putStoredConfiguration(FORMAT_VERSION, FORMAT_VERSION, "A".repeat(12001), validIv)
        assertInvalidConfiguration { store.load() }

        putStoredConfiguration(FORMAT_VERSION, FORMAT_VERSION, validCiphertext, "A".repeat(65))
        assertInvalidConfiguration { store.load() }
    }

    @Test
    fun `解密失败或无效明文抛稳定异常`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        cipher.decryptFailure = IllegalStateException("decrypt-secret")

        assertInvalidConfiguration("decrypt-secret") { store.load() }

        cipher.decryptFailure = null
        cipher.decryptedOverride = byteArrayOf(0xC3.toByte(), 0x28)
        assertInvalidConfiguration { store.load() }

        cipher.decryptedOverride = byteArrayOf()
        assertInvalidConfiguration { store.load() }
    }

    @Test
    fun `configuredModelId 不解密并在无配置时返回 null`() {
        assertNull(store.configuredModelId())
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        cipher.decryptFailure = IllegalStateException("不应解密")
        cipher.decryptCalls = 0

        assertEquals("deepseek-chat", store.configuredModelId())
        assertEquals(0, cipher.decryptCalls)

        preferences().edit().putInt(CONFIGURATION_FORMAT_VERSION, FORMAT_VERSION + 1).commit()
        assertInvalidConfiguration { store.configuredModelId() }

        preferences().edit()
            .putInt(CONFIGURATION_FORMAT_VERSION, FORMAT_VERSION)
            .putInt(ENCRYPTION_FORMAT_VERSION, FORMAT_VERSION + 1)
            .commit()
        assertInvalidConfiguration { store.configuredModelId() }
    }

    @Test
    fun `configuredModelId 校验完整 envelope 但不解密`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        cipher.decryptFailure = IllegalStateException("must-not-decrypt")
        cipher.decryptCalls = 0

        preferences().edit().remove(API_KEY_IV).commit()
        assertInvalidConfiguration { store.configuredModelId() }
        assertEquals(0, cipher.decryptCalls)

        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        preferences().edit().putString(API_KEY_CIPHERTEXT, "%%%").commit()
        assertInvalidConfiguration { store.configuredModelId() }

        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        preferences().edit().putInt(API_KEY_IV, 1).commit()
        assertInvalidConfiguration { store.configuredModelId() }
    }

    @Test
    fun `清除配置字段并删除加密密钥`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        preferences().edit().putString("unrelated", "kept").commit()

        store.clear()

        assertNull(store.load())
        assertEquals(1, cipher.clearKeyCalls)
        assertEquals("kept", preferences().getString("unrelated", null))
    }

    @Test
    fun `保存或清除提交失败时抛异常并且清除不删除密钥`() {
        val failingContext = CommitFailingContext(context)
        val failingStore = SharedPreferencesDeepSeekConfigurationStore(failingContext, cipher)
        val oldConfiguration = DeepSeekConfiguration("deepseek-chat", "old-key")
        store.save(oldConfiguration)

        val saveException = assertThrows(ConfigurationStoreException::class.java) {
            failingStore.save(DeepSeekConfiguration("deepseek-reasoner", "new-key"))
        }
        assertEquals("无法保存 DeepSeek 配置", saveException.message)
        assertEquals(oldConfiguration, store.load())

        val clearException = assertThrows(ConfigurationStoreException::class.java) {
            failingStore.clear()
        }
        assertEquals("无法清除 DeepSeek 配置", clearException.message)
        assertEquals(0, cipher.clearKeyCalls)
        assertEquals(oldConfiguration, store.load())
    }

    @Test
    fun `删除密钥失败时 SharedPreferences 已清除并抛稳定异常`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        cipher.clearKeyFailure = IllegalStateException("clear-secret")

        val exception = assertThrows(ConfigurationStoreException::class.java) {
            store.clear()
        }

        assertEquals("无法清除 DeepSeek 配置", exception.message)
        assertPublicExceptionDoesNotContain(exception, "clear-secret")
        assertNull(store.load())
    }

    @Test
    fun `EncryptedSecret 对输入和输出数组均防御性复制`() {
        val ciphertext = ByteArray(16) { 7 }
        val iv = ByteArray(12) { 3 }
        val secret = EncryptedSecret(ciphertext, iv)
        ciphertext.fill(0)
        iv.fill(0)

        assertEquals(7, secret.ciphertext[0].toInt())
        assertEquals(3, secret.iv[0].toInt())

        secret.ciphertext.fill(0)
        secret.iv.fill(0)
        assertEquals(7, secret.ciphertext[0].toInt())
        assertEquals(3, secret.iv[0].toInt())
    }

    @Test
    fun `两个 store 并发保存不会产生混合 envelope`() {
        val otherStore = SharedPreferencesDeepSeekConfigurationStore(context, cipher)
        val first = DeepSeekConfiguration("deepseek-chat", "first-key")
        val second = DeepSeekConfiguration("deepseek-reasoner", "second-key")
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        val firstSave = executor.submit {
            ready.countDown()
            start.await()
            store.save(first)
        }
        val secondSave = executor.submit {
            ready.countDown()
            start.await()
            otherStore.save(second)
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        firstSave.get(5, TimeUnit.SECONDS)
        secondSave.get(5, TimeUnit.SECONDS)
        executor.shutdownNow()

        val loaded = store.load()
        assertTrue(loaded == first || loaded == second)
    }

    @Test
    fun `共享锁阻止第二个 store 在第一个加密完成前进入 cipher`() {
        val blockingCipher = BlockingTestCipher()
        val firstStore = SharedPreferencesDeepSeekConfigurationStore(context, blockingCipher)
        val secondStore = SharedPreferencesDeepSeekConfigurationStore(context, blockingCipher)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstSave = executor.submit {
                firstStore.save(DeepSeekConfiguration("deepseek-chat", "first-key"))
            }
            assertTrue(blockingCipher.firstEncryptEntered.await(5, TimeUnit.SECONDS))

            val secondSave = executor.submit {
                secondStore.save(DeepSeekConfiguration("deepseek-reasoner", "second-key"))
            }
            assertFalse(blockingCipher.secondEncryptEntered.await(300, TimeUnit.MILLISECONDS))

            blockingCipher.releaseFirstEncrypt.countDown()
            firstSave.get(5, TimeUnit.SECONDS)
            secondSave.get(5, TimeUnit.SECONDS)
            assertEquals(2, blockingCipher.encryptCalls.get())
            assertTrue(firstStore.load() in setOf(
                DeepSeekConfiguration("deepseek-chat", "first-key"),
                DeepSeekConfiguration("deepseek-reasoner", "second-key"),
            ))
        } finally {
            blockingCipher.releaseFirstEncrypt.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertInvalidConfiguration(vararg forbiddenText: String, block: () -> Unit) {
        val exception = assertThrows(ConfigurationStoreException::class.java, block)
        assertEquals("DeepSeek 配置无效", exception.message)
        assertPublicExceptionDoesNotContain(exception, *forbiddenText)
    }

    private fun assertPublicExceptionDoesNotContain(
        exception: ConfigurationStoreException,
        vararg forbiddenText: String,
    ) {
        assertNull(exception.cause)
        val trace = StringWriter().use { output ->
            PrintWriter(output).use(exception::printStackTrace)
            output.toString()
        }
        forbiddenText.forEach { text ->
            assertFalse(exception.message.orEmpty().contains(text))
            assertFalse(trace.contains(text))
        }
    }

    private fun putStoredConfiguration(
        configurationVersion: Int,
        cipherVersion: Int,
        ciphertext: String,
        iv: String,
    ) {
        preferences().edit().clear()
            .putInt(CONFIGURATION_FORMAT_VERSION, configurationVersion)
            .putString(MODEL_ID, "deepseek-chat")
            .putString(API_KEY_CIPHERTEXT, ciphertext)
            .putString(API_KEY_IV, iv)
            .putInt(ENCRYPTION_FORMAT_VERSION, cipherVersion)
            .commit()
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private class ReversibleTestCipher : SecretCipher {
        var encryptCalls = 0
        var decryptCalls = 0
        var clearKeyCalls = 0
        var encryptFailure: Throwable? = null
        var decryptFailure: Throwable? = null
        var clearKeyFailure: Throwable? = null
        var decryptedOverride: ByteArray? = null

        override fun encrypt(plaintext: ByteArray): EncryptedSecret {
            encryptCalls += 1
            encryptFailure?.let { throw it }
            val ciphertext = ByteArray(16 + plaintext.size) { 9 }
            plaintext.reversedArray().copyInto(ciphertext, destinationOffset = 16)
            return EncryptedSecret(ciphertext, ByteArray(12) { 3 })
        }

        override fun decrypt(secret: EncryptedSecret): ByteArray {
            decryptCalls += 1
            decryptFailure?.let { throw it }
            return decryptedOverride ?: secret.ciphertext.copyOfRange(16, secret.ciphertext.size).reversedArray()
        }

        override fun clearKey() {
            clearKeyCalls += 1
            clearKeyFailure?.let { throw it }
        }
    }

    private class BlockingTestCipher : SecretCipher {
        val encryptCalls = AtomicInteger()
        val firstEncryptEntered = CountDownLatch(1)
        val secondEncryptEntered = CountDownLatch(1)
        val releaseFirstEncrypt = CountDownLatch(1)

        override fun encrypt(plaintext: ByteArray): EncryptedSecret {
            when (encryptCalls.incrementAndGet()) {
                1 -> {
                    firstEncryptEntered.countDown()
                    check(releaseFirstEncrypt.await(5, TimeUnit.SECONDS))
                }

                2 -> secondEncryptEntered.countDown()
            }
            val ciphertext = ByteArray(16 + plaintext.size) { 9 }
            plaintext.reversedArray().copyInto(ciphertext, destinationOffset = 16)
            return EncryptedSecret(ciphertext, ByteArray(12) { 3 })
        }

        override fun decrypt(secret: EncryptedSecret): ByteArray =
            secret.ciphertext.copyOfRange(16, secret.ciphertext.size).reversedArray()

        override fun clearKey() = Unit
    }

    private class CommitFailingContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            CommitFailingSharedPreferences(super.getSharedPreferences(name, mode))
    }

    private class CommitFailingSharedPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = CommitFailingEditor(delegate.edit())
    }

    private class CommitFailingEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            delegate.putString(key, value)
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            delegate.putStringSet(key, values)
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            delegate.putInt(key, value)
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            delegate.putLong(key, value)
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            delegate.putFloat(key, value)
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            delegate.putBoolean(key, value)
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            delegate.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            delegate.clear()
            return this
        }

        override fun commit(): Boolean {
            delegate.commit()
            return false
        }

        override fun apply() = delegate.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "deepseek-configuration"
        const val CONFIGURATION_FORMAT_VERSION = "configuration_format_version"
        const val MODEL_ID = "model_id"
        const val API_KEY_CIPHERTEXT = "api_key_ciphertext"
        const val API_KEY_IV = "api_key_iv"
        const val ENCRYPTION_FORMAT_VERSION = "encryption_format_version"
        const val FORMAT_VERSION = 1
    }
}
