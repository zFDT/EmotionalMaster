package cn.echobird.emotionalmaster.llm

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

fun officialDeepSeekModelCatalog(): ModelCatalog = DeepSeekModelCatalog()

class DeepSeekModelCatalog private constructor(
    private val endpoint: HttpUrl,
    private val client: OkHttpClient,
) : ModelCatalog {
    private val json = Json { isLenient = false }

    constructor(client: OkHttpClient = OkHttpClient()) :
        this(DEFAULT_ENDPOINT.toHttpUrl(), client)

    internal constructor(
        endpoint: String,
        client: OkHttpClient = OkHttpClient(),
    ) : this(endpoint.toHttpUrl(), client)

    override fun listModels(apiKey: String): List<AvailableModel> {
        require(apiKey.isNotBlank()) { "DeepSeek API Key 不能为空" }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpException(response.code)
                parseModels(response.body.string())
            }
        } catch (error: InterruptedIOException) {
            throw ModelCatalogException.Timeout(error)
        } catch (error: IOException) {
            throw ModelCatalogException.Transport(error)
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
