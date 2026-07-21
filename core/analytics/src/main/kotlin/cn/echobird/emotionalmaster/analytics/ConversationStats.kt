package cn.echobird.emotionalmaster.analytics

import java.time.DayOfWeek
import java.time.Duration
import java.time.YearMonth
import java.time.ZoneId

data class StatsParameters(
    val sessionGap: Duration = Duration.ofMinutes(5),
    val activityZone: ZoneId = ZoneId.of("UTC"),
    val iqrMultiplier: Double = 1.5,
) {
    init {
        require(!sessionGap.isNegative && !sessionGap.isZero) { "会话间隔必须大于零" }
        require(iqrMultiplier.isFinite() && iqrMultiplier > 0.0) { "IQR 倍数必须大于零" }
    }
}

data class DirectionCount(
    val incomingCount: Int,
    val outgoingCount: Int,
)

data class DirectionBreakdown(
    val incomingCount: Int,
    val outgoingCount: Int,
    val incomingShare: Double,
    val outgoingShare: Double,
)

data class SessionStats(
    val sessionCount: Int,
    val initiations: DirectionCount,
)

data class DurationDistribution(
    val observedSampleSize: Int,
    val includedSampleSize: Int,
    val excludedOutlierCount: Int,
    val median: Duration?,
    val q1: Duration?,
    val q3: Duration?,
)

data class ResponseIntervalStats(
    val incoming: DurationDistribution,
    val outgoing: DurationDistribution,
)

data class ActivityBucket(
    val dayOfWeek: DayOfWeek,
    val hour: Int,
    val messageCount: Int,
)

data class MonthlyActivity(
    val month: YearMonth,
    val incomingCount: Int,
    val outgoingCount: Int,
) {
    val totalMessages: Int = incomingCount + outgoingCount
}

data class ConversationStats(
    val totalMessages: Int,
    val messageDirections: DirectionBreakdown,
    val sessions: SessionStats,
    val responseIntervals: ResponseIntervalStats,
    val activityHeatmap: List<ActivityBucket>,
    val monthlyTrend: List<MonthlyActivity>,
    val parameters: StatsParameters,
) {
    fun activityCount(dayOfWeek: DayOfWeek, hour: Int): Int {
        require(hour in 0..23) { "小时必须位于 0 到 23 之间" }
        return activityHeatmap.first {
            it.dayOfWeek == dayOfWeek && it.hour == hour
        }.messageCount
    }
}
