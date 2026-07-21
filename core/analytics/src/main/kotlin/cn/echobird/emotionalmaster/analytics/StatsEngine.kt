package cn.echobird.emotionalmaster.analytics

import cn.echobird.emotionalmaster.model.ChatMessage
import cn.echobird.emotionalmaster.model.MessageDirection
import java.time.DayOfWeek
import java.time.Duration
import java.time.YearMonth
import kotlin.math.floor
import kotlin.math.roundToLong

class StatsEngine(
    private val parameters: StatsParameters = StatsParameters(),
) {
    fun calculate(messages: List<ChatMessage>): ConversationStats {
        val sessions = SessionSegmenter(parameters.sessionGap).segment(messages)
        val sortedMessages = sessions.flatMap(ConversationSession::messages)
        val incomingCount = sortedMessages.count { it.direction == MessageDirection.INCOMING }
        val outgoingCount = sortedMessages.size - incomingCount
        val total = sortedMessages.size

        return ConversationStats(
            totalMessages = total,
            messageDirections = DirectionBreakdown(
                incomingCount = incomingCount,
                outgoingCount = outgoingCount,
                incomingShare = share(incomingCount, total),
                outgoingShare = share(outgoingCount, total),
            ),
            sessions = sessionStats(sessions),
            responseIntervals = responseIntervals(sessions),
            activityHeatmap = activityHeatmap(sortedMessages),
            monthlyTrend = monthlyTrend(sortedMessages),
            parameters = parameters,
        )
    }

    private fun sessionStats(sessions: List<ConversationSession>): SessionStats {
        val incoming = sessions.count {
            it.messages.first().direction == MessageDirection.INCOMING
        }
        return SessionStats(
            sessionCount = sessions.size,
            initiations = DirectionCount(
                incomingCount = incoming,
                outgoingCount = sessions.size - incoming,
            ),
        )
    }

    private fun responseIntervals(
        sessions: List<ConversationSession>,
    ): ResponseIntervalStats {
        val byResponder = sessions
            .flatMap { it.messages.zipWithNext() }
            .filter { (previous, current) -> previous.direction != current.direction }
            .groupBy(
                keySelector = { (_, current) -> current.direction },
                valueTransform = { (previous, current) ->
                    Duration.between(previous.sentAt, current.sentAt)
                },
            )
        return ResponseIntervalStats(
            incoming = distribution(byResponder[MessageDirection.INCOMING].orEmpty()),
            outgoing = distribution(byResponder[MessageDirection.OUTGOING].orEmpty()),
        )
    }

    private fun distribution(samples: List<Duration>): DurationDistribution {
        if (samples.isEmpty()) {
            return DurationDistribution(0, 0, 0, null, null, null)
        }

        val sortedMillis = samples.map(Duration::toMillis).sorted()
        val q1Millis = percentile(sortedMillis, 0.25)
        val q3Millis = percentile(sortedMillis, 0.75)
        val included = if (sortedMillis.size < MINIMUM_IQR_SAMPLE_SIZE) {
            sortedMillis
        } else {
            val iqr = q3Millis - q1Millis
            val lowerBound = q1Millis - parameters.iqrMultiplier * iqr
            val upperBound = q3Millis + parameters.iqrMultiplier * iqr
            sortedMillis.filter { it.toDouble() in lowerBound..upperBound }
        }

        return DurationDistribution(
            observedSampleSize = sortedMillis.size,
            includedSampleSize = included.size,
            excludedOutlierCount = sortedMillis.size - included.size,
            median = duration(percentile(included, 0.5)),
            q1 = duration(q1Millis),
            q3 = duration(q3Millis),
        )
    }

    private fun activityHeatmap(messages: List<ChatMessage>): List<ActivityBucket> {
        val counts = messages.groupingBy { message ->
            val local = message.sentAt.atZone(parameters.activityZone)
            local.dayOfWeek to local.hour
        }.eachCount()

        return DayOfWeek.entries.flatMap { day ->
            (0..23).map { hour ->
                ActivityBucket(day, hour, counts[day to hour] ?: 0)
            }
        }
    }

    private fun monthlyTrend(messages: List<ChatMessage>): List<MonthlyActivity> = messages
        .groupBy { YearMonth.from(it.sentAt.atZone(parameters.activityZone)) }
        .toSortedMap()
        .map { (month, monthlyMessages) ->
            MonthlyActivity(
                month = month,
                incomingCount = monthlyMessages.count {
                    it.direction == MessageDirection.INCOMING
                },
                outgoingCount = monthlyMessages.count {
                    it.direction == MessageDirection.OUTGOING
                },
            )
        }

    private fun percentile(sortedValues: List<Long>, percentile: Double): Double {
        val position = (sortedValues.size - 1) * percentile
        val lowerIndex = floor(position).toInt()
        val upperIndex = kotlin.math.ceil(position).toInt()
        if (lowerIndex == upperIndex) return sortedValues[lowerIndex].toDouble()

        val fraction = position - lowerIndex
        return sortedValues[lowerIndex] +
            (sortedValues[upperIndex] - sortedValues[lowerIndex]) * fraction
    }

    private fun duration(milliseconds: Double): Duration =
        Duration.ofMillis(milliseconds.roundToLong())

    private fun share(count: Int, total: Int): Double =
        if (total == 0) 0.0 else count.toDouble() / total

    private companion object {
        const val MINIMUM_IQR_SAMPLE_SIZE = 4
    }
}
