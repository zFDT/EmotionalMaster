package cn.echobird.emotionalmaster.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
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
)

interface DeepSeekConfigurationStore {
    fun load(): DeepSeekConfiguration?

    fun configuredModelId(): String?

    fun save(configuration: DeepSeekConfiguration)

    fun clear()
}

data class EncryptedSecret(
    val ciphertext: ByteArray,
    val iv: ByteArray,
)

internal interface SecretCipher {
    fun encrypt(plaintext: ByteArray): EncryptedSecret

    fun decrypt(secret: EncryptedSecret): ByteArray

    fun clearKey()
}

class ConfigurationStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

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

    override fun load(): DeepSeekConfiguration? {
        if (!hasAnyConfigurationField()) {
            return null
        }

        val stored = readCompleteConfiguration()
        val plaintext = try {
            cipher.decrypt(EncryptedSecret(stored.ciphertext, stored.iv))
        } catch (exception: Exception) {
            throw invalidConfiguration(exception)
        }
        val apiKey = decodeUtf8(plaintext)
        if (apiKey.isEmpty()) {
            throw invalidConfiguration()
        }
        return DeepSeekConfiguration(stored.modelId, apiKey)
    }

    override fun configuredModelId(): String? {
        if (!hasAnyConfigurationField()) {
            return null
        }

        return try {
            val configurationVersion = preferences.getInt(CONFIGURATION_FORMAT_VERSION, -1)
            val encryptionVersion = preferences.getInt(ENCRYPTION_FORMAT_VERSION, -1)
            val modelId = preferences.getString(MODEL_ID, null)
            if (
                configurationVersion != FORMAT_VERSION ||
                encryptionVersion != FORMAT_VERSION ||
                modelId.isNullOrEmpty()
            ) {
                throw invalidConfiguration()
            }
            modelId
        } catch (exception: ConfigurationStoreException) {
            throw exception
        } catch (exception: Exception) {
            throw invalidConfiguration(exception)
        }
    }

    override fun save(configuration: DeepSeekConfiguration) {
        if (configuration.modelId.isEmpty() || configuration.apiKey.isEmpty()) {
            throw saveFailure()
        }

        val encrypted = try {
            cipher.encrypt(configuration.apiKey.toByteArray(StandardCharsets.UTF_8))
        } catch (exception: Exception) {
            throw saveFailure(exception)
        }
        if (encrypted.ciphertext.isEmpty() || encrypted.iv.isEmpty()) {
            throw saveFailure()
        }

        val saved = try {
            preferences.edit()
                .putInt(CONFIGURATION_FORMAT_VERSION, FORMAT_VERSION)
                .putString(MODEL_ID, configuration.modelId)
                .putString(API_KEY_CIPHERTEXT, Base64.getEncoder().encodeToString(encrypted.ciphertext))
                .putString(API_KEY_IV, Base64.getEncoder().encodeToString(encrypted.iv))
                .putInt(ENCRYPTION_FORMAT_VERSION, FORMAT_VERSION)
                .commit()
        } catch (exception: Exception) {
            throw saveFailure(exception)
        }
        if (!saved) {
            throw saveFailure()
        }
    }

    override fun clear() {
        val cleared = try {
            preferences.edit()
                .remove(CONFIGURATION_FORMAT_VERSION)
                .remove(MODEL_ID)
                .remove(API_KEY_CIPHERTEXT)
                .remove(API_KEY_IV)
                .remove(ENCRYPTION_FORMAT_VERSION)
                .commit()
        } catch (exception: Exception) {
            throw clearFailure(exception)
        }
        if (!cleared) {
            throw clearFailure()
        }

        try {
            cipher.clearKey()
        } catch (exception: Exception) {
            throw clearFailure(exception)
        }
    }

    private fun readCompleteConfiguration(): StoredConfiguration = try {
        if (!hasAllConfigurationFields()) {
            throw invalidConfiguration()
        }

        val configurationVersion = preferences.getInt(CONFIGURATION_FORMAT_VERSION, -1)
        val encryptionVersion = preferences.getInt(ENCRYPTION_FORMAT_VERSION, -1)
        val modelId = preferences.getString(MODEL_ID, null)
        val ciphertext = preferences.getString(API_KEY_CIPHERTEXT, null)
        val iv = preferences.getString(API_KEY_IV, null)
        if (
            configurationVersion != FORMAT_VERSION ||
            encryptionVersion != FORMAT_VERSION ||
            modelId.isNullOrEmpty() ||
            ciphertext.isNullOrEmpty() ||
            iv.isNullOrEmpty()
        ) {
            throw invalidConfiguration()
        }

        val decodedCiphertext = Base64.getDecoder().decode(ciphertext)
        val decodedIv = Base64.getDecoder().decode(iv)
        if (decodedCiphertext.isEmpty() || decodedIv.isEmpty()) {
            throw invalidConfiguration()
        }
        StoredConfiguration(modelId, decodedCiphertext, decodedIv)
    } catch (exception: ConfigurationStoreException) {
        throw exception
    } catch (exception: Exception) {
        throw invalidConfiguration(exception)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (exception: CharacterCodingException) {
        throw invalidConfiguration(exception)
    }

    private fun hasAnyConfigurationField(): Boolean = CONFIGURATION_FIELDS.any(preferences::contains)

    private fun hasAllConfigurationFields(): Boolean = CONFIGURATION_FIELDS.all(preferences::contains)

    private fun invalidConfiguration(cause: Throwable? = null) =
        ConfigurationStoreException("DeepSeek 配置无效", cause)

    private fun saveFailure(cause: Throwable? = null) =
        ConfigurationStoreException("无法保存 DeepSeek 配置", cause)

    private fun clearFailure(cause: Throwable? = null) =
        ConfigurationStoreException("无法清除 DeepSeek 配置", cause)

    private data class StoredConfiguration(
        val modelId: String,
        val ciphertext: ByteArray,
        val iv: ByteArray,
    )

    private companion object {
        const val PREFERENCES_NAME = "deepseek-configuration"
        const val CONFIGURATION_FORMAT_VERSION = "configuration_format_version"
        const val MODEL_ID = "model_id"
        const val API_KEY_CIPHERTEXT = "api_key_ciphertext"
        const val API_KEY_IV = "api_key_iv"
        const val ENCRYPTION_FORMAT_VERSION = "encryption_format_version"
        const val FORMAT_VERSION = 1
        val CONFIGURATION_FIELDS = arrayOf(
            CONFIGURATION_FORMAT_VERSION,
            MODEL_ID,
            API_KEY_CIPHERTEXT,
            API_KEY_IV,
            ENCRYPTION_FORMAT_VERSION,
        )
    }
}

internal class AndroidKeystoreSecretCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        return EncryptedSecret(cipher.doFinal(plaintext), cipher.iv)
    }

    override fun decrypt(secret: EncryptedSecret): ByteArray = Cipher.getInstance(TRANSFORMATION).run {
        init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, secret.iv))
        doFinal(secret.ciphertext)
    }

    override fun clearKey() {
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
