package cn.echobird.emotionalmaster.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeepSeekConfiguration(
    val modelId: String,
    val apiKey: String,
) {
    init {
        require(modelId.isNotBlank()) { "DeepSeek 模型标识不能为空" }
        require(modelId.length <= MAX_MODEL_ID_LENGTH) { "DeepSeek 模型标识过长" }
        require(apiKey.isNotBlank()) { "DeepSeek API Key 不能为空" }
        val encodedApiKey = requireNotNull(strictUtf8Bytes(apiKey)) { "DeepSeek API Key 无效" }
        try {
            require(encodedApiKey.size in 1..MAX_API_KEY_BYTES) { "DeepSeek API Key 长度无效" }
        } finally {
            encodedApiKey.fill(0)
        }
    }

    override fun toString(): String =
        "DeepSeekConfiguration(modelId=$modelId, apiKey=[REDACTED])"
}

interface DeepSeekConfigurationStore {
    fun load(): DeepSeekConfiguration?

    fun configuredModelId(): String?

    fun save(configuration: DeepSeekConfiguration)

    fun clear()
}

internal class EncryptedSecret(ciphertext: ByteArray, iv: ByteArray) {
    private val encryptedCiphertext = ciphertext.copyOf()
    private val initializationVector = iv.copyOf()

    val ciphertext: ByteArray
        get() = encryptedCiphertext.copyOf()

    val iv: ByteArray
        get() = initializationVector.copyOf()
}

internal interface SecretCipher {
    fun encrypt(plaintext: ByteArray): EncryptedSecret

    fun decrypt(secret: EncryptedSecret): ByteArray

    fun clearKey()
}

class ConfigurationStoreException(message: String) : RuntimeException(message)

/**
 * 以应用私有 SharedPreferences 保存 DeepSeek 配置。
 *
 * 该实现只保证单进程内多个 store 实例的一致性；多进程访问不受支持。
 */
class SharedPreferencesDeepSeekConfigurationStore private constructor(
    private val preferences: SharedPreferences,
    private val cipher: SecretCipher,
) : DeepSeekConfigurationStore {
    public constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        AndroidKeystoreSecretCipher(),
    )

    internal constructor(context: Context, cipher: SecretCipher) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        cipher,
    )

    override fun load(): DeepSeekConfiguration? = synchronized(processLock) {
        val snapshot = preferences.all.toMap()
        if (!hasAnyConfigurationField(snapshot)) {
            return@synchronized null
        }

        val stored = readCompleteConfiguration(snapshot)
        try {
            val plaintext = try {
                cipher.decrypt(EncryptedSecret(stored.ciphertext, stored.iv))
            } catch (exception: Exception) {
                throw invalidConfiguration()
            }
            try {
                if (plaintext.size !in 1..MAX_API_KEY_BYTES) {
                    throw invalidConfiguration()
                }
                val apiKey = decodeUtf8(plaintext)
                if (apiKey.isNotBlank().not()) {
                    throw invalidConfiguration()
                }
                try {
                    DeepSeekConfiguration(stored.modelId, apiKey)
                } catch (exception: IllegalArgumentException) {
                    throw invalidConfiguration()
                }
            } finally {
                plaintext.fill(0)
            }
        } finally {
            stored.clear()
        }
    }

    override fun configuredModelId(): String? = synchronized(processLock) {
        val snapshot = preferences.all.toMap()
        if (!hasAnyConfigurationField(snapshot)) {
            return@synchronized null
        }

        val stored = readCompleteConfiguration(snapshot)
        try {
            stored.modelId
        } finally {
            stored.clear()
        }
    }

    override fun save(configuration: DeepSeekConfiguration): Unit = synchronized(processLock) {
        if (
            configuration.modelId.isNotBlank().not() ||
            configuration.modelId.length > MAX_MODEL_ID_LENGTH ||
            configuration.apiKey.isNotBlank().not()
        ) {
            throw saveFailure()
        }

        val plaintext = strictUtf8Bytes(configuration.apiKey) ?: throw saveFailure()
        try {
            if (plaintext.size !in 1..MAX_API_KEY_BYTES) {
                throw saveFailure()
            }
            val encrypted = try {
                cipher.encrypt(plaintext)
            } catch (exception: Exception) {
                throw saveFailure()
            }
            val ciphertext = encrypted.ciphertext
            val iv = encrypted.iv
            try {
                if (!hasValidEnvelopeSizes(ciphertext, iv)) {
                    throw saveFailure()
                }
                val before = configurationSnapshot(preferences.all.toMap())
                val saved = try {
                    preferences.edit()
                        .putInt(CONFIGURATION_FORMAT_VERSION, FORMAT_VERSION)
                        .putString(MODEL_ID, configuration.modelId)
                        .putString(API_KEY_CIPHERTEXT, Base64.getEncoder().encodeToString(ciphertext))
                        .putString(API_KEY_IV, Base64.getEncoder().encodeToString(iv))
                        .putInt(ENCRYPTION_FORMAT_VERSION, FORMAT_VERSION)
                        .commit()
                } catch (exception: Exception) {
                    restoreConfiguration(before)
                    throw saveFailure()
                }
                if (!saved) {
                    restoreConfiguration(before)
                    throw saveFailure()
                }
            } finally {
                ciphertext.fill(0)
                iv.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override fun clear(): Unit = synchronized(processLock) {
        val before = configurationSnapshot(preferences.all.toMap())
        val cleared = try {
            preferences.edit().also(::removeConfigurationFields).commit()
        } catch (_: Exception) {
            restoreConfiguration(before)
            throw clearFailure()
        }
        if (!cleared) {
            restoreConfiguration(before)
            throw clearFailure()
        }

        try {
            cipher.clearKey()
        } catch (exception: Exception) {
            throw clearFailure()
        }
    }

    private fun readCompleteConfiguration(snapshot: Map<String, *>): StoredConfiguration = try {
        if (!hasAllConfigurationFields(snapshot)) {
            throw invalidConfiguration()
        }

        val configurationVersion = snapshot[CONFIGURATION_FORMAT_VERSION] as? Int
        val encryptionVersion = snapshot[ENCRYPTION_FORMAT_VERSION] as? Int
        val modelId = snapshot[MODEL_ID] as? String
        val ciphertext = snapshot[API_KEY_CIPHERTEXT] as? String
        val iv = snapshot[API_KEY_IV] as? String
        if (
            configurationVersion != FORMAT_VERSION ||
            encryptionVersion != FORMAT_VERSION ||
            modelId.isNullOrBlank() ||
            modelId.length > MAX_MODEL_ID_LENGTH ||
            ciphertext.isNullOrEmpty() ||
            ciphertext.length > MAX_CIPHERTEXT_BASE64_LENGTH ||
            iv.isNullOrEmpty() ||
            iv.length > MAX_IV_BASE64_LENGTH
        ) {
            throw invalidConfiguration()
        }

        val decodedCiphertext = Base64.getDecoder().decode(ciphertext)
        val decodedIv = Base64.getDecoder().decode(iv)
        if (!hasValidEnvelopeSizes(decodedCiphertext, decodedIv)) {
            decodedCiphertext.fill(0)
            decodedIv.fill(0)
            throw invalidConfiguration()
        }
        StoredConfiguration(modelId, decodedCiphertext, decodedIv)
    } catch (exception: ConfigurationStoreException) {
        throw exception
    } catch (exception: Exception) {
        throw invalidConfiguration()
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (exception: CharacterCodingException) {
        throw invalidConfiguration()
    }

    private fun hasAnyConfigurationField(snapshot: Map<String, *>): Boolean =
        CONFIGURATION_FIELDS.any(snapshot::containsKey)

    private fun hasAllConfigurationFields(snapshot: Map<String, *>): Boolean =
        CONFIGURATION_FIELDS.all(snapshot::containsKey)

    private fun configurationSnapshot(snapshot: Map<String, *>): Map<String, Any> =
        CONFIGURATION_FIELDS.mapNotNull { key ->
            (snapshot[key] as? Any)?.let { key to it }
        }.toMap()

    private fun restoreConfiguration(snapshot: Map<String, Any>) {
        try {
            preferences.edit().also { editor ->
                removeConfigurationFields(editor)
                snapshot.forEach { (key, value) -> putPreferenceValue(editor, key, value) }
            }.commit()
        } catch (_: Exception) {
            // 回滚失败不能改变当前操作对外暴露的稳定错误类别。
        }
    }

    private fun removeConfigurationFields(editor: SharedPreferences.Editor): SharedPreferences.Editor = editor.apply {
        CONFIGURATION_FIELDS.forEach(::remove)
    }

    @Suppress("UNCHECKED_CAST")
    private fun putPreferenceValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
        }
    }

    private fun hasValidEnvelopeSizes(ciphertext: ByteArray, iv: ByteArray): Boolean =
        ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES &&
            iv.size in MIN_IV_BYTES..MAX_IV_BYTES

    private fun invalidConfiguration() = ConfigurationStoreException("DeepSeek 配置无效")

    private fun saveFailure() = ConfigurationStoreException("无法保存 DeepSeek 配置")

    private fun clearFailure() = ConfigurationStoreException("无法清除 DeepSeek 配置")

    private class StoredConfiguration(
        val modelId: String,
        val ciphertext: ByteArray,
        val iv: ByteArray,
    ) {
        fun clear() {
            ciphertext.fill(0)
            iv.fill(0)
        }
    }

    companion object {
        internal val processLock = Any()
        private const val PREFERENCES_NAME = "deepseek-configuration"
        private const val CONFIGURATION_FORMAT_VERSION = "configuration_format_version"
        private const val MODEL_ID = "model_id"
        private const val API_KEY_CIPHERTEXT = "api_key_ciphertext"
        private const val API_KEY_IV = "api_key_iv"
        private const val ENCRYPTION_FORMAT_VERSION = "encryption_format_version"
        private const val FORMAT_VERSION = 1
        private val CONFIGURATION_FIELDS = arrayOf(
            CONFIGURATION_FORMAT_VERSION,
            MODEL_ID,
            API_KEY_CIPHERTEXT,
            API_KEY_IV,
            ENCRYPTION_FORMAT_VERSION,
        )
    }
}

internal class AndroidKeystoreSecretCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedSecret = synchronized(
        SharedPreferencesDeepSeekConfigurationStore.processLock,
    ) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        EncryptedSecret(cipher.doFinal(plaintext), cipher.iv)
    }

    override fun decrypt(secret: EncryptedSecret): ByteArray = synchronized(
        SharedPreferencesDeepSeekConfigurationStore.processLock,
    ) {
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, secret.iv))
            doFinal(secret.ciphertext)
        }
    }

    override fun clearKey(): Unit = synchronized(SharedPreferencesDeepSeekConfigurationStore.processLock) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.run {
            if (containsAlias(KEY_ALIAS)) {
                deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "emotional-master-deepseek-api-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

private fun strictUtf8Bytes(value: String): ByteArray? = try {
    val buffer = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
    ByteArray(buffer.remaining()).also(buffer::get)
} catch (exception: CharacterCodingException) {
    null
}

private const val MAX_MODEL_ID_LENGTH = 128
private const val MAX_API_KEY_BYTES = 4096
private const val MIN_IV_BYTES = 12
private const val MAX_IV_BYTES = 16
private const val MIN_CIPHERTEXT_BYTES = 16
private const val MAX_CIPHERTEXT_BYTES = 8192
private const val MAX_CIPHERTEXT_BASE64_LENGTH = 12000
private const val MAX_IV_BASE64_LENGTH = 64
