package cn.echobird.emotionalmaster.reply

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.echobird.emotionalmaster.model.ReplySuggestion
import cn.echobird.emotionalmaster.ui.AppPage
import cn.echobird.emotionalmaster.ui.ParchmentCard
import cn.echobird.emotionalmaster.ui.PrimaryAction
import cn.echobird.emotionalmaster.ui.PrivacyNote
import cn.echobird.emotionalmaster.ui.SecondaryAction
import cn.echobird.emotionalmaster.ui.StepHeader
import cn.echobird.emotionalmaster.ui.isCompactTextLayout
import kotlinx.coroutines.launch

@Composable
fun ReplyScreen(
    state: ReplyUiState,
    onCurrentMessageChange: (String) -> Unit,
    onPreviewContext: () -> Unit,
    onDismissContextPreview: () -> Unit,
    onGenerateSuggestions: () -> Unit,
    onUseOfflineSuggestions: () -> Unit = {},
    modelConfigurationLabel: String = "未配置 DeepSeek",
    onOpenModelSettings: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val compact = isCompactTextLayout()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        AppPage(modifier = Modifier.padding(padding)) {
            StepHeader(
                step = 3,
                title = if (state.suggestions.isEmpty()) "准备当前情境" else "选择一种回复思路",
                description = if (state.suggestions.isEmpty()) {
                    if (compact) {
                        "输入对方当前的消息。生成前可核对脱敏上下文。"
                    } else {
                        "输入对方当前的消息。生成前，你会先看到将上传的脱敏上下文。"
                    }
                } else {
                    "每条建议都可单独复制和修改，不会自动发送。"
                },
                onBack = onBack,
            )
            Spacer(Modifier.height(24.dp))
            ParchmentCard {
                Text("当前模型", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.isResolvingOnlineProvider) {
                        "正在读取模型配置"
                    } else {
                        modelConfigurationLabel
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SecondaryAction(
                    text = "模型设置",
                    onClick = onOpenModelSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (state.suggestions.isEmpty()) {
                OutlinedTextField(
                    value = state.currentMessage,
                    onValueChange = onCurrentMessageChange,
                    modifier = Modifier.fillMaxWidth().testTag("current-message"),
                    label = { Text("对方当前消息") },
                    supportingText = { Text("请勿填入与当前回复无关的敏感信息") },
                    minLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                    ),
                )
                Spacer(Modifier.height(16.dp))
                PrivacyNote("下一步确认后，脱敏上下文才会发送给模型服务。")
                Spacer(Modifier.height(24.dp))
                PrimaryAction(
                    text = if (state.isResolvingOnlineProvider) {
                        "正在读取模型配置"
                    } else {
                        "预览上传内容"
                    },
                    onClick = onPreviewContext,
                    enabled = state.currentMessage.isNotBlank() &&
                        !state.isResolvingOnlineProvider,
                    loading = state.isResolvingOnlineProvider,
                )
            } else {
                if (state.isOfflineFallback) {
                    ParchmentCard {
                        Text("离线降级", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "当前未调用云端模型，以下为本地保守建议。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                state.suggestions.forEach { suggestion ->
                    SuggestionCard(
                        suggestion = suggestion,
                        onCopy = {
                            clipboard.setText(AnnotatedString(suggestion.text))
                            scope.launch {
                                snackbarHostState.showSnackbar("已复制到剪贴板，请留意剪贴板隐私")
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.fillMaxWidth().height(16.dp).testTag("suggestions-end"))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (state.showContextPreview) {
        ContextPreviewSheet(
            state = state,
            onDismiss = onDismissContextPreview,
            onConfirm = onGenerateSuggestions,
            onUseOfflineSuggestions = onUseOfflineSuggestions,
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: ReplySuggestion,
    onCopy: () -> Unit,
) {
    ParchmentCard {
        if (isCompactTextLayout()) {
            Column(Modifier.fillMaxWidth()) {
                Text(suggestion.strategy, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onCopy, modifier = Modifier.align(Alignment.End)) { Text("复制") }
            }
        } else Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                suggestion.strategy,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy) { Text("复制") }
        }
        Spacer(Modifier.height(8.dp))
        Text(suggestion.text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Detail("意图", suggestion.intent)
        Detail("风险", suggestion.risk)
        Detail("适用", suggestion.applicableWhen)
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    if (isCompactTextLayout()) {
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(value, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ContextPreviewSheet(
    state: ReplyUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onUseOfflineSuggestions: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("将上传的上下文", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "请核对范围。点击取消不会发送任何内容。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                ParchmentCard {
                    Detail(
                        label = "对方当前消息",
                        value = state.redactedMessages.lastOrNull()?.text.orEmpty(),
                        modifier = Modifier.testTag("context-current-message"),
                    )
                }
                Spacer(Modifier.height(12.dp))
                ParchmentCard {
                    Detail("供应商", state.providerLabel)
                    Detail("上传范围", "最近 ${state.redactedMessages.size} 条消息")
                    Detail("不包含", "原文件路径、真实身份、密钥")
                }
                Spacer(Modifier.height(12.dp))
                ParchmentCard {
                    Text("历史脱敏内容", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    state.redactedMessages.dropLast(1).forEach { message ->
                        Detail(message.role, message.text)
                    }
                }
                if (state.onlineFailureMessage != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "在线生成失败",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(state.onlineFailureMessage, color = MaterialTheme.colorScheme.error)
                    Text("可以重试；取消后输入内容仍会保留。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SecondaryAction(
                        text = "使用本地建议",
                        onClick = onUseOfflineSuggestions,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryAction("取消", onDismiss, Modifier.weight(1f))
                PrimaryAction(
                    text = if (state.onlineFailureMessage == null) "确认生成" else "重试",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    loading = state.isGenerating,
                )
            }
        }
    }
}
