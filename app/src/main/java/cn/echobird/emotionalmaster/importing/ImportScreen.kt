package cn.echobird.emotionalmaster.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.echobird.emotionalmaster.ui.AppPage
import cn.echobird.emotionalmaster.ui.ParchmentCard
import cn.echobird.emotionalmaster.ui.PrimaryAction
import cn.echobird.emotionalmaster.ui.PrivacyNote
import cn.echobird.emotionalmaster.ui.SecondaryAction
import cn.echobird.emotionalmaster.ui.StepHeader
import cn.echobird.emotionalmaster.ui.isCompactTextLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

@Composable
fun ImportScreen(
    state: ImportUiState,
    onChooseDocument: () -> Unit,
    onSwapIdentities: () -> Unit,
    onConfirmImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppPage(modifier = modifier) {
        when (state) {
            ImportUiState.Empty -> EmptyImport(onChooseDocument)
            ImportUiState.Loading -> LoadingImport()
            is ImportUiState.Error -> ErrorImport(state.message, onChooseDocument)
            is ImportUiState.Preview -> PreviewImport(
                preview = state.document,
                isSaving = state.isSaving,
                persistenceError = state.persistenceError,
                onChooseDocument = onChooseDocument,
                onSwapIdentities = onSwapIdentities,
                onConfirmImport = onConfirmImport,
            )
        }
    }
}

@Composable
private fun EmptyImport(onChooseDocument: () -> Unit) {
    StepHeader(
        step = 1,
        title = "导入聊天记录",
        description = "先在本机预览内容，再决定是否保存。支持 JSON、JSONL。",
    )
    Spacer(Modifier.height(32.dp))
    ParchmentCard {
        Text("把聊天变成可核对的数据", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "我们会展示消息数量、时间范围、参与者和解析问题。原文件以只读方式打开。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        PrivacyNote("文件仅在本机解析；确认前不写入资料库、不上传模型服务。")
    }
    Spacer(Modifier.height(24.dp))
    PrimaryAction("选择聊天文件", onChooseDocument)
}

@Composable
private fun LoadingImport() {
    StepHeader(1, "正在预览", "正在本机读取文件，请稍候。")
    Spacer(Modifier.height(48.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorImport(message: String, onChooseDocument: () -> Unit) {
    StepHeader(1, "无法读取文件", "文件没有被写入资料库。")
    Spacer(Modifier.height(24.dp))
    ParchmentCard {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(24.dp))
    PrimaryAction("重新选择", onChooseDocument)
}

@Composable
private fun PreviewImport(
    preview: ImportDocumentPreview,
    isSaving: Boolean,
    persistenceError: String?,
    onChooseDocument: () -> Unit,
    onSwapIdentities: () -> Unit,
    onConfirmImport: () -> Unit,
) {
    StepHeader(1, "预览聊天记录", "核对文件与双方身份，确认后才会保存。")
    Spacer(Modifier.height(24.dp))
    ParchmentCard {
        Text(preview.fileName, style = MaterialTheme.typography.titleLarge)
        Text(
            formatFileSize(preview.fileSizeBytes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SecondaryAction("更换文件", onChooseDocument)
    }
    Spacer(Modifier.height(16.dp))
    ParchmentCard {
        Text("预览概览", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        MetricRow("消息总数", "${preview.messages.size} 条消息")
        MetricRow("时间范围", formatRange(preview))
        MetricRow("参与者", "${preview.participantIds.size} 人")
        MetricRow("解析问题", if (preview.issues.isEmpty()) "未发现" else "${preview.issues.size} 项")
    }
    Spacer(Modifier.height(16.dp))
    ParchmentCard(modifier = Modifier.testTag("identity-mapping")) {
        Text("身份映射", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("请确认聊天中的发言人分别是谁。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        IdentityRow("我", preview.participantIds.getOrNull(0).orEmpty())
        Spacer(Modifier.height(12.dp))
        IdentityRow("对方", preview.participantIds.getOrNull(1).orEmpty())
        Spacer(Modifier.height(16.dp))
        SecondaryAction("交换双方", onSwapIdentities)
    }
    if (preview.issues.isNotEmpty()) {
        var expanded by remember(preview.issues) { mutableStateOf(false) }
        Spacer(Modifier.height(16.dp))
        ParchmentCard {
            Text(
                if (preview.fatalIssues.isEmpty()) "可继续导入的提示" else "必须处理的问题",
                style = MaterialTheme.typography.titleLarge,
                color = if (preview.fatalIssues.isEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(
                if (preview.fatalIssues.isEmpty()) {
                    "已跳过 ${preview.warnings.size} 条无效记录，可查看详情后继续。"
                } else {
                    "当前文件不能导入，请更换文件。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起详情" else "查看详情")
            }
            if (expanded) preview.issues.forEach { Text("• ${it.summary}") }
        }
    }
    if (persistenceError != null) {
        Spacer(Modifier.height(16.dp))
        ParchmentCard {
            Text("保存失败", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
            Text(persistenceError, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("请检查设备存储状态后重试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(16.dp))
    PrivacyNote("确认后，消息将写入本机加密资料库。此步骤不会调用模型服务。")
    Spacer(Modifier.height(24.dp))
    PrimaryAction(
        text = "确认导入",
        onClick = onConfirmImport,
        enabled = preview.canImport,
        loading = isSaving,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun MetricRow(label: String, value: String) {
    if (isCompactTextLayout()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun IdentityRow(label: String, participant: String) {
    val modifier = Modifier
        .fillMaxWidth()
        .semantics { stateDescription = "$label：$participant" }
    if (isCompactTextLayout()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(participant, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(participant, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    }
}

private fun formatFileSize(bytes: Long): String =
    if (bytes < 1024) "$bytes B" else "${max(1, bytes / 1024)} KB"

private fun formatRange(preview: ImportDocumentPreview): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneId.systemDefault())
    val first = preview.firstMessageAt ?: return "无有效消息"
    val last = preview.lastMessageAt ?: return "无有效消息"
    return "${formatter.format(first)}—${formatter.format(last)}"
}
