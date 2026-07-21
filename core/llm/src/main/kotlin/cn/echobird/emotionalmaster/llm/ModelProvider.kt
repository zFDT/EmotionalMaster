package cn.echobird.emotionalmaster.llm

data class ModelRequest(
    val context: RedactedConversation,
)

fun interface ModelProvider {
    fun generate(request: ModelRequest): String
}

sealed class ModelProviderException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Timeout(cause: Throwable) : ModelProviderException("模型请求超时", cause)

    class HttpFailure(val statusCode: Int) :
        ModelProviderException("模型服务返回非成功状态：$statusCode")

    class MalformedResponse(cause: Throwable? = null) :
        ModelProviderException("模型服务响应格式无效", cause)

    class TransportFailure(cause: Throwable) :
        ModelProviderException("模型服务连接失败", cause)
}
