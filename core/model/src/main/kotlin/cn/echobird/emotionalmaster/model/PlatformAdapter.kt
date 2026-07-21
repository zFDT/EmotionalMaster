package cn.echobird.emotionalmaster.model

enum class PlatformCapability {
    AUTHORIZED_CAPTURE,
    NORMALIZATION,
    HEALTH_DIAGNOSTICS,
}

data class CaptureAuthorization(val reference: String)

data class RawCapture(
    val stableSourceId: String,
    val payload: String,
)

data class NormalizedCapture(val message: ChatMessage)

enum class AdapterHealthStatus {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
}

data class AdapterHealth(
    val status: AdapterHealthStatus,
    val diagnosticCode: String? = null,
)

interface PlatformAdapter {
    fun detectCapabilities(): Set<PlatformCapability>

    fun captureAuthorized(authorization: CaptureAuthorization): List<RawCapture>

    fun normalize(captures: List<RawCapture>): List<NormalizedCapture>

    fun diagnose(): AdapterHealth
}
