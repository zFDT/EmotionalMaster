package cn.echobird.emotionalmaster.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.UUID
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
        cipher.encryptFailure = IllegalStateException("不得泄露的加密错误")

        val exception = assertThrows(ConfigurationStoreException::class.java) {
            store.save(DeepSeekConfiguration("deepseek-reasoner", "new-key"))
        }

        assertEquals("无法保存 DeepSeek 配置", exception.message)
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
    fun `解密失败或无效明文抛稳定异常`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        cipher.decryptFailure = IllegalStateException("不得泄露的解密错误")

        assertInvalidConfiguration { store.load() }

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

        val saveException = assertThrows(ConfigurationStoreException::class.java) {
            failingStore.save(DeepSeekConfiguration("deepseek-chat", "key"))
        }
        assertEquals("无法保存 DeepSeek 配置", saveException.message)

        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        val clearException = assertThrows(ConfigurationStoreException::class.java) {
            failingStore.clear()
        }
        assertEquals("无法清除 DeepSeek 配置", clearException.message)
        assertEquals(0, cipher.clearKeyCalls)
        assertTrue(preferences().contains(MODEL_ID))
    }

    @Test
    fun `删除密钥失败时 SharedPreferences 已清除并抛稳定异常`() {
        store.save(DeepSeekConfiguration("deepseek-chat", "key"))
        cipher.clearKeyFailure = IllegalStateException("不得泄露的删除错误")

        val exception = assertThrows(ConfigurationStoreException::class.java) {
            store.clear()
        }

        assertEquals("无法清除 DeepSeek 配置", exception.message)
        assertNull(store.load())
    }

    private fun assertInvalidConfiguration(vararg forbiddenText: String, block: () -> Unit) {
        val exception = assertThrows(ConfigurationStoreException::class.java, block)
        assertEquals("DeepSeek 配置无效", exception.message)
        forbiddenText.forEach { text ->
            assertFalse(exception.message.orEmpty().contains(text))
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
            return EncryptedSecret(plaintext.reversedArray(), byteArrayOf(1, 2, 3))
        }

        override fun decrypt(secret: EncryptedSecret): ByteArray {
            decryptCalls += 1
            decryptFailure?.let { throw it }
            return decryptedOverride ?: secret.ciphertext.reversedArray()
        }

        override fun clearKey() {
            clearKeyCalls += 1
            clearKeyFailure?.let { throw it }
        }
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

        override fun commit(): Boolean = false

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
