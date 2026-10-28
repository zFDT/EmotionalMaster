package cn.echobird.emotionalmaster.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun interface DatabaseKeyProvider {
    fun getOrCreatePassphrase(): ByteArray
}

class AndroidKeystoreDatabaseKeyProvider(
    context: Context,
    private val keyAlias: String = "emotional-master-database-key",
) : DatabaseKeyProvider {
    private val applicationContext = context.applicationContext

    @Synchronized
    override fun getOrCreatePassphrase(): ByteArray {
        val wrappingKey = getOrCreateWrappingKey()
        return if (applicationContext.getFileStreamPath(KEY_FILE_NAME).exists()) {
            decrypt(applicationContext.openFileInput(KEY_FILE_NAME).use { it.readBytes() }, wrappingKey)
        } else {
            val passphrase = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
            val wrapped = encrypt(passphrase, wrappingKey)
            applicationContext.openFileOutput(KEY_FILE_NAME, Context.MODE_PRIVATE).use { output ->
                output.write(wrapped)
            }
            passphrase
        }
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(passphrase: ByteArray, wrappingKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey)
        }
        val encrypted = cipher.doFinal(passphrase)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
    }

    private fun decrypt(wrapped: ByteArray, wrappingKey: SecretKey): ByteArray =
        DataInputStream(ByteArrayInputStream(wrapped)).use { input ->
            check(input.readInt() == FORMAT_VERSION) { "不支持的数据库密钥格式" }
            val ivLength = input.readInt()
            check(ivLength in 12..16) { "数据库密钥初始化向量无效" }
            val iv = ByteArray(ivLength).also(input::readFully)
            val encrypted = input.readBytes()
            check(encrypted.isNotEmpty()) { "数据库密钥内容为空" }

            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(encrypted)
            }
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_FILE_NAME = "database-key.wrapped"
        const val FORMAT_VERSION = 1
        const val PASSPHRASE_BYTES = 32
        const val GCM_TAG_BITS = 128
    }
}
