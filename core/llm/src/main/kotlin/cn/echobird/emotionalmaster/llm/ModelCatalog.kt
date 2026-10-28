package cn.echobird.emotionalmaster.llm

data class AvailableModel(
    val id: String,
    val isDeprecated: Boolean,
)

fun interface ModelCatalog {
    fun listModels(apiKey: String): List<AvailableModel>
}

sealed class ModelCatalogException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class InvalidApiKey : ModelCatalogException("API Key 无效")

    class InsufficientBalance : ModelCatalogException("DeepSeek 账户余额不足")

    class RateLimited : ModelCatalogException("请求过于频繁，请稍后重试")

    class InvalidRequest(val statusCode: Int) : ModelCatalogException("DeepSeek 请求格式不兼容")

    class ServiceUnavailable(val statusCode: Int) : ModelCatalogException("DeepSeek 服务暂时不可用")

    class Timeout(cause: Throwable) : ModelCatalogException("连接 DeepSeek 超时", cause)

    class Transport(cause: Throwable) : ModelCatalogException("无法连接 DeepSeek", cause)

    class MalformedResponse(cause: Throwable? = null) :
        ModelCatalogException("DeepSeek 模型列表无效", cause)
}

fun preferredDeepSeekModel(models: List<AvailableModel>): AvailableModel {
    require(models.isNotEmpty()) { "DeepSeek 模型列表不能为空" }
    return models.firstOrNull { it.id == DEEPSEEK_V4_PRO } ?: models.first()
}

private const val DEEPSEEK_V4_PRO = "deepseek-v4-pro"
