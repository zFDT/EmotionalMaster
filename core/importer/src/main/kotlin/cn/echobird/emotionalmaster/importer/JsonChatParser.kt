package cn.echobird.emotionalmaster.importer

import cn.echobird.emotionalmaster.model.ChatMessage
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsonChatParser(
    private val validator: ImportValidator = ImportValidator(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(
        input: InputStream,
        format: ChatImportFormat,
        identityMapping: IdentityMapping,
    ): StagedImportBatch {
        val digest = MessageDigest.getInstance("SHA-256")
        val digestInput = DigestInputStream(input, digest)
        val messages = mutableListOf<ChatMessage>()
        val errors = mutableListOf<ImportError>()

        when (format) {
            ChatImportFormat.JSON -> parseJsonArray(
                digestInput,
                identityMapping,
                messages,
                errors,
            )

            ChatImportFormat.JSONL -> parseJsonLines(
                digestInput,
                identityMapping,
                messages,
                errors,
            )
        }

        drain(digestInput)
        return StagedImportBatch(
            sourceHash = digest.digest().toHex(),
            parsedMessages = messages.toList(),
            errors = errors.toList(),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parseJsonArray(
        input: InputStream,
        identityMapping: IdentityMapping,
        messages: MutableList<ChatMessage>,
        errors: MutableList<ImportError>,
    ) {
        try {
            json.decodeToSequence(
                input,
                JsonObject.serializer(),
                DecodeSequenceMode.ARRAY_WRAPPED,
            ).forEach { element ->
                validate(element, identityMapping, null, messages, errors)
            }
        } catch (_: Exception) {
            errors += ImportError(
                code = ImportErrorCode.MALFORMED_JSON,
                summary = "JSON 数组格式无效",
            )
        }
    }

    private fun parseJsonLines(
        input: InputStream,
        identityMapping: IdentityMapping,
        messages: MutableList<ChatMessage>,
        errors: MutableList<ImportError>,
    ) {
        input.bufferedReader().lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed

            try {
                val element = json.decodeFromString(JsonObject.serializer(), line)
                validate(element, identityMapping, index + 1, messages, errors)
            } catch (_: Exception) {
                errors += ImportError(
                    code = ImportErrorCode.MALFORMED_JSON,
                    lineNumber = index + 1,
                    summary = "JSONL 行格式无效",
                )
            }
        }
    }

    private fun validate(
        element: JsonObject,
        identityMapping: IdentityMapping,
        lineNumber: Int?,
        messages: MutableList<ChatMessage>,
        errors: MutableList<ImportError>,
    ) {
        try {
            messages += validator.validate(element.toRawMessage(), identityMapping)
        } catch (error: ImportValidationException) {
            errors += ImportError(
                code = error.code,
                lineNumber = lineNumber,
                summary = error.message ?: "消息校验失败",
            )
        }
    }

    private fun JsonObject.toRawMessage() = RawImportMessage(
        id = requiredString("id"),
        senderId = requiredString("senderId"),
        sentAt = requiredString("sentAt"),
        text = this["text"]?.jsonPrimitive?.contentOrNull,
        attachments = this["attachments"]?.jsonArray?.map { element ->
            val attachment = element.jsonObject
            RawImportAttachment(
                sourceId = attachment.requiredString("sourceId"),
                mediaType = attachment.requiredString("mediaType"),
            )
        }.orEmpty(),
    )

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("缺少字段 $name")

    private fun drain(input: InputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (input.read(buffer) != -1) {
            // 继续读取以确保来源哈希覆盖完整文件。
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
