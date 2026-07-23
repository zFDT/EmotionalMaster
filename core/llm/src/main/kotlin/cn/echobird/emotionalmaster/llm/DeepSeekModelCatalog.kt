package cn.echobird.emotionalmaster.llm

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class DeepSeekModelCatalog(
    endpoint: String = DEFAULT_ENDPOINT,
    private val client: OkHttpClient = OkHttpClient(),
) : ModelCatalog {
    private val endpoint = endpoint.toHttpUrl()
    private val json = Json { isLenient = false }

    override fun listModels(apiKey: String): List<AvailableModel> {
        require(apiKey.isNotBlank()) { "DeepSeek API Key 不能为空" }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (error: SocketTimeoutException) {
            throw ModelCatalogException.Timeout(error)
        } catch (error: IOException) {
            throw ModelCatalogException.Transport(error)
        }

        response.use {
            if (!it.isSuccessful) throw httpException(it.code)
            return parseModels(it.body.string())
        }
    }

    private fun parseModels(body: String): List<AvailableModel> = try {
        val root = json.parseToJsonElement(body).asJsonObject()
        if (root.requiredString("object") != "list") malformed()
        val models = root.requiredArray("data")
        if (models.isEmpty()) malformed()
        models.map { item ->
            val model = item.asJsonObject()
            val id = model.requiredString("id")
            if (id.isBlank() || model.requiredString("object") != "model") malformed()
            model.requiredString("owned_by")
            AvailableModel(id = id, isDeprecated = id in DEPRECATED_MODEL_IDS)
        }.distinctBy(AvailableModel::id)
            .sortedWith(compareBy<AvailableModel> { it.id != PREFERRED_MODEL_ID }.thenBy(AvailableModel::id))
    } catch (_: Exception) {
        throw ModelCatalogException.MalformedResponse()
    }

    private fun httpException(statusCode: Int): ModelCatalogException = when (statusCode) {
        401 -> ModelCatalogException.InvalidApiKey()
        402 -> ModelCatalogException.InsufficientBalance()
        429 -> ModelCatalogException.RateLimited()
        400, 422 -> ModelCatalogException.InvalidRequest(statusCode)
        in 500..599 -> ModelCatalogException.ServiceUnavailable(statusCode)
        else -> ModelCatalogException.InvalidRequest(statusCode)
    }

    private fun JsonObject.requiredString(name: String): String = this[name]
        ?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?: malformed()

    private fun JsonObject.requiredArray(name: String): JsonArray = this[name]
        .asJsonArray()
        ?: malformed()

    private fun kotlinx.serialization.json.JsonElement?.asJsonObject(): JsonObject =
        this as? JsonObject ?: malformed()

    private fun kotlinx.serialization.json.JsonElement?.asJsonArray(): JsonArray? = this as? JsonArray

    private fun malformed(): Nothing = throw ModelCatalogException.MalformedResponse()

    private companion object {
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/models"
        const val PREFERRED_MODEL_ID = "deepseek-v4-pro"
        val DEPRECATED_MODEL_IDS = setOf("deepseek-chat", "deepseek-reasoner")
    }
}
