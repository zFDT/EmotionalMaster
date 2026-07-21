package cn.echobird.emotionalmaster.analytics

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsEngineTest {
    @Test
    fun `统计消息占比会话发起回复间隔热力和月度趋势`() {
        val parameters = StatsParameters(
            sessionGap = Duration.ofMinutes(5),
            activityZone = ZoneId.of("Asia/Shanghai"),
        )
        val messages = listOf(
            message("1", "2026-01-05T00:00:00Z", MessageDirection.INCOMING),
            message("2", "2026-01-05T00:01:00Z", MessageDirection.OUTGOING),
            message("3", "2026-02-06T01:00:00Z", MessageDirection.OUTGOING),
            message("4", "2026-02-06T01:02:00Z", MessageDirection.INCOMING),
        )

        val stats = StatsEngine(parameters).calculate(messages)

        assertEquals(4, stats.totalMessages)
        assertEquals(DirectionBreakdown(2, 2, 0.5, 0.5), stats.messageDirections)
        assertEquals(2, stats.sessions.sessionCount)
        assertEquals(DirectionCount(1, 1), stats.sessions.initiations)
        assertEquals(Duration.ofMinutes(1), stats.responseIntervals.outgoing.median)
        assertEquals(Duration.ofMinutes(2), stats.responseIntervals.incoming.median)
        assertEquals(7 * 24, stats.activityHeatmap.size)
        assertEquals(2, stats.activityCount(DayOfWeek.MONDAY, 8))
        assertEquals(2, stats.activityCount(DayOfWeek.FRIDAY, 9))
        assertEquals(
            listOf(
                MonthlyActivity(YearMonth.of(2026, 1), incomingCount = 1, outgoingCount = 1),
                MonthlyActivity(YearMonth.of(2026, 2), incomingCount = 1, outgoingCount = 1),
            ),
            stats.monthlyTrend,
        )
        assertEquals(parameters, stats.parameters)
    }

    @Test
    fun `IQR 排除异常回复间隔并报告样本量`() {
        val intervals = listOf(10L, 20L, 30L, 40L, 1_000L)
        var current = Instant.parse("2026-01-01T00:00:00Z")
        val messages = buildList {
            intervals.forEachIndexed { index, seconds ->
                add(message("in:$index", current, MessageDirection.INCOMING))
                current = current.plusSeconds(seconds)
                add(message("out:$index", current, MessageDirection.OUTGOING))
                current = current.plusSeconds(1)
            }
        }

        val stats = StatsEngine(
            StatsParameters(sessionGap = Duration.ofMinutes(30)),
        ).calculate(messages)

        assertEquals(5, stats.responseIntervals.outgoing.observedSampleSize)
        assertEquals(4, stats.responseIntervals.outgoing.includedSampleSize)
        assertEquals(1, stats.responseIntervals.outgoing.excludedOutlierCount)
        assertEquals(Duration.ofSeconds(25), stats.responseIntervals.outgoing.median)
        assertEquals(Duration.ofSeconds(20), stats.responseIntervals.outgoing.q1)
        assertEquals(Duration.ofSeconds(40), stats.responseIntervals.outgoing.q3)
    }

    @Test
    fun `相同输入无论原始顺序都得到相同结果`() {
        val messages = listOf(
            message("1", "2026-01-01T00:00:00Z", MessageDirection.INCOMING),
            message("2", "2026-01-01T00:01:00Z", MessageDirection.OUTGOING),
            message("3", "2026-01-01T00:02:00Z", MessageDirection.INCOMING),
        )
        val engine = StatsEngine()

        assertEquals(engine.calculate(messages), engine.calculate(messages.reversed()))
    }

    private fun message(
        id: String,
        sentAt: String,
        direction: MessageDirection,
    ) = message(id, Instant.parse(sentAt), direction)

    private fun message(
        id: String,
        sentAt: Instant,
        direction: MessageDirection,
    ) = ChatMessage(
        stableSourceId = id,
        sentAt = sentAt,
        direction = direction,
        text = id,
    )
}
