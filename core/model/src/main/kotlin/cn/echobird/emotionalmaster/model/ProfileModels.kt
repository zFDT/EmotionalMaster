package cn.echobird.emotionalmaster.model

import java.time.Instant

data class Evidence(
    val id: String,
    val sourceReference: String,
) {
    init {
        require(id.isNotBlank()) { "证据必须包含标识" }
        require(sourceReference.isNotBlank()) { "证据必须包含来源引用" }
    }
}

@JvmInline
value class Confidence private constructor(val value: Double) {
    companion object {
        fun of(value: Double): Confidence {
            require(value.isFinite() && value in 0.0..1.0) {
                "置信度必须位于零到一之间"
            }
            return Confidence(value)
        }
    }
}

data class ProfileHypothesis(
    val conclusion: String,
    val confidence: Confidence,
    val supportingEvidence: List<Evidence>,
    val contradictingEvidence: List<Evidence>,
    val updatedAt: Instant,
) {
    init {
        require(conclusion.isNotBlank()) { "画像假设必须包含结论" }
        require(supportingEvidence.isNotEmpty()) { "画像假设必须包含支持证据" }
        require(contradictingEvidence.isNotEmpty()) { "画像假设必须包含反向证据" }
    }
}
