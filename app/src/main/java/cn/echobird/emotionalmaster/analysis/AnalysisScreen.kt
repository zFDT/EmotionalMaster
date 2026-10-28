package cn.echobird.emotionalmaster.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.echobird.emotionalmaster.analytics.DurationDistribution
import cn.echobird.emotionalmaster.ui.AppPage
import cn.echobird.emotionalmaster.ui.ParchmentCard
import cn.echobird.emotionalmaster.ui.PrimaryAction
import cn.echobird.emotionalmaster.ui.StepHeader
import cn.echobird.emotionalmaster.ui.isCompactTextLayout
import java.time.Duration
import java.time.DayOfWeek
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AnalysisScreen(
    state: AnalysisUiState,
    onGetReplySuggestions: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = state.stats
    val hasEnoughData = stats.totalMessages >= 4
    AppPage(modifier = modifier) {
        StepHeader(
            step = 2,
            title = "相处节奏",
            description = summary(stats.messageDirections.outgoingShare, stats.totalMessages),
            onBack = onBack,
        )
        Spacer(Modifier.height(24.dp))
        ParchmentCard {
            Text("对话平衡", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            if (hasEnoughData) {
                ShareBar(stats.messageDirections.outgoingShare.toFloat())
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("我 ${percent(stats.messageDirections.outgoingShare)}")
                    Text("对方 ${percent(stats.messageDirections.incomingShare)}")
                }
            } else {
                InsufficientData()
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "基于 ${stats.totalMessages} 条消息；仅反映当前样本，不代表关系质量。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        ParchmentCard {
            Text("回复时长对比", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            if (hasEnoughData) {
                Metric("我", duration(stats.responseIntervals.outgoing))
                Spacer(Modifier.height(12.dp))
                Metric("对方", duration(stats.responseIntervals.incoming))
            } else {
                InsufficientData()
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "使用中位数并剔除明显异常值；样本不足时不作推断。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        ParchmentCard {
            Text("会话发起", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (hasEnoughData) {
                Metric("我发起", "${stats.sessions.initiations.outgoingCount} 次")
                Metric("对方发起", "${stats.sessions.initiations.incomingCount} 次")
            } else {
                InsufficientData()
            }
            Text(
                "连续消息间隔超过 ${stats.parameters.sessionGap.toMinutes()} 分钟计为新会话，共 ${stats.sessions.sessionCount} 次。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        ParchmentCard {
            Text("活跃时段", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (hasEnoughData) {
                Text(activePeriod(state), fontWeight = FontWeight.SemiBold)
                Text(
                    "按当前统计时区汇总消息数量。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                InsufficientData()
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryAction("获取回复建议", onGetReplySuggestions)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InsufficientData() {
    Text(
        "数据不足以形成该项结论；继续导入更多对话后再查看。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun activePeriod(state: AnalysisUiState): String {
    val busiest = state.stats.activityHeatmap.maxByOrNull { it.messageCount }
        ?: return "暂无可用时段"
    return "${dayName(busiest.dayOfWeek)} ${busiest.hour.toString().padStart(2, '0')}:00—" +
        "${(busiest.hour + 1).toString().padStart(2, '0')}:00 更活跃（${busiest.messageCount} 条）"
}

private fun dayName(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

@Composable
private fun ShareBar(outgoingShare: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .weight(outgoingShare.coerceAtLeast(0.01f))
                .height(18.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
        )
        Box(
            Modifier
                .weight((1f - outgoingShare).coerceAtLeast(0.01f))
                .height(18.dp)
                .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(6.dp)),
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    if (isCompactTextLayout()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}

private fun summary(outgoingShare: Double, total: Int): String = when {
    total < 4 -> "当前只有 $total 条消息，数据不足以形成稳定结论。"
    outgoingShare in 0.4..0.6 -> "从当前样本看，双方的消息数量较为接近。"
    outgoingShare > 0.6 -> "从当前样本看，你发出的消息更多，可以留意对方的回应节奏。"
    else -> "从当前样本看，对方发出的消息更多，你的回应相对简短。"
}

private fun percent(value: Double): String =
    String.format(Locale.getDefault(), "%d%%", (value * 100).roundToInt())

private fun duration(distribution: DurationDistribution): String {
    val median = distribution.median ?: return "样本不足"
    return "${formatDuration(median)}（${distribution.includedSampleSize} 个样本）"
}

private fun formatDuration(value: Duration): String = when {
    value.toHours() > 0 -> "${value.toHours()} 小时 ${value.minusHours(value.toHours()).toMinutes()} 分钟"
    value.toMinutes() > 0 -> "${value.toMinutes()} 分钟"
    else -> "${value.seconds} 秒"
}
