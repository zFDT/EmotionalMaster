package cn.echobird.emotionalmaster.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.echobird.emotionalmaster.ui.AppPage
import cn.echobird.emotionalmaster.ui.ParchmentCard
import cn.echobird.emotionalmaster.ui.PrimaryAction
import cn.echobird.emotionalmaster.ui.PrivacyNote
import cn.echobird.emotionalmaster.ui.SecondaryAction
import cn.echobird.emotionalmaster.ui.StepHeader

@Composable
fun DeepSeekSettingsScreen(
    state: DeepSeekSettingsUiState,
    onVerify: (String?) -> Unit,
    onCandidateEdited: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiKey by remember { mutableStateOf("") }

    AppPage(modifier) {
        StepHeader(
            step = 3,
            title = "模型设置",
            description = "连接 DeepSeek 官方服务，并选择用于生成回复的模型。",
            onBack = onBack,
        )
        Spacer(Modifier.height(24.dp))
        ParchmentCard {
            Text("DeepSeek 官方服务", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "api.deepseek.com",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state.hasSavedConfiguration) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "已保存 · ${state.configuredModelId}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                onCandidateEdited()
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("deepseek-api-key"),
            label = { Text("DeepSeek API Key") },
            supportingText = {
                Text(
                    if (state.hasSavedConfiguration) {
                        "已有 Key 仅显示为已保存；留空可用已保存配置刷新模型。"
                    } else {
                        "验证成功前不会保存。"
                    },
                )
            },
            enabled = !state.isLoading,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(16.dp))
        if (state.models.isEmpty()) {
            PrimaryAction(
                text = "验证并获取模型",
                onClick = { onVerify(apiKey.takeIf(String::isNotBlank)) },
                enabled = (apiKey.isNotBlank() || state.hasSavedConfiguration) && !state.isLoading,
                loading = state.isLoading,
            )
        }
        if (state.errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
        }
        if (state.models.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("选择模型", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            ParchmentCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.models.forEach { model ->
                        val label = if (model.isDeprecated) "${model.id}（已弃用）" else model.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.selectedModelId == model.id,
                                    enabled = !state.isLoading,
                                    role = Role.RadioButton,
                                    onClick = { onSelectModel(model.id) },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.selectedModelId == model.id,
                                onClick = null,
                                enabled = !state.isLoading,
                            )
                            Text(label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SecondaryAction(
                text = "刷新模型列表",
                onClick = { onVerify(apiKey.takeIf(String::isNotBlank)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            )
            Spacer(Modifier.height(16.dp))
            PrimaryAction(
                text = "保存并启用",
                onClick = onSave,
                enabled = state.canSave && !state.isLoading,
                loading = state.isLoading,
            )
        }
        if (state.hasSavedConfiguration) {
            Spacer(Modifier.height(12.dp))
            SecondaryAction(
                text = "清除已保存配置",
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            )
        }
        Spacer(Modifier.height(24.dp))
        PrivacyNote(
            "API Key 使用 Android Keystore 加密后保存在本机。验证模型列表不会发送聊天内容；" +
                "实际生成时，只有你确认后的脱敏上下文会交由 DeepSeek 第三方服务处理。",
        )
        Spacer(Modifier.height(16.dp))
    }
}
