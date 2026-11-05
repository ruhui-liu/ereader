package com.ruhuiliu.ereader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruhuiliu.ereader.BuildConfig
import com.ruhuiliu.ereader.data.settings.Engines
import com.ruhuiliu.ereader.data.settings.Languages
import com.ruhuiliu.ereader.data.settings.TranslationSettings
import com.ruhuiliu.ereader.translate.ClaudeEngine

private val OPENAI_PRESETS = listOf(
    "DeepSeek" to ("https://api.deepseek.com/v1" to "deepseek-chat"),
    "通义千问" to ("https://dashscope.aliyuncs.com/compatible-mode/v1" to "qwen-plus"),
    "Kimi" to ("https://api.moonshot.cn/v1" to "moonshot-v1-8k"),
    "OpenAI" to ("https://api.openai.com/v1" to ""),
    "Ollama（局域网）" to ("http://192.168.1.100:11434/v1" to "qwen2.5:7b"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val tsOrNull by vm.translation.collectAsStateWithLifecycle()
    val reader by vm.reader.collectAsStateWithLifecycle()
    val test by vm.testResult.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val modelBusy by vm.modelBusy.collectAsStateWithLifecycle()
    val cacheCount by vm.cacheCount.collectAsStateWithLifecycle()
    val fonts by vm.fonts.collectAsStateWithLifecycle()
    val dropboxLoggedIn by vm.dropboxLoggedIn.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        val ts = tsOrNull ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("翻译引擎")
            Card {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Engines.all.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.update { it.copy(engine = e) } }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = ts.engine == e, onClick = { vm.update { it.copy(engine = e) } })
                            Text(Engines.label(e), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            EngineConfig(ts, vm, models, modelBusy)

            Section("翻译选项")
            Text("译文语言", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Languages.targets.forEach { (code, name) ->
                    FilterChip(selected = ts.targetLang == code, onClick = { vm.update { it.copy(targetLang = code) } }, label = { Text(name) })
                }
            }
            Text("新打开的书默认", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "不翻译", 1 to "双语对照", 2 to "仅译文").forEach { (m, label) ->
                    FilterChip(selected = ts.defaultMode == m, onClick = { vm.update { it.copy(defaultMode = m) } }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.test() }) { Text("测试翻译") }
                Spacer(Modifier.size(12.dp))
                Text("已缓存 $cacheCount 段译文", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.clearCache() }) { Text("清空缓存") }
            }
            test?.let {
                Card { Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            }

            Section("阅读")
            SwitchRow("音量键翻页", reader.volumeKeys) { v -> vm.updateReader { it.copy(volumeKeys = v) } }
            SwitchRow("阅读时屏幕常亮", reader.keepScreenOn) { v -> vm.updateReader { it.copy(keepScreenOn = v) } }
            SwitchRow("翻页动画", reader.pageAnimation) { v -> vm.updateReader { it.copy(pageAnimation = v) } }
            Text(
                "字号、字体、行距、背景、翻页方式等可在阅读时点屏幕中央 → “外观”中调整。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fonts.isNotEmpty()) {
                Text("已导入的字体", style = MaterialTheme.typography.labelLarge)
                fonts.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { vm.deleteFont(f) }) { Icon(Icons.Default.Delete, "删除字体") }
                    }
                }
            }

            Section("云存储")
            Text(
                "Dropbox：" + when {
                    !vm.dropboxConfigured -> "未配置 App Key（见 README）"
                    dropboxLoggedIn -> "已登录"
                    else -> "未登录"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (dropboxLoggedIn) OutlinedButton(onClick = { vm.logoutDropbox() }) { Text("退出 Dropbox") }
            Text(
                "Google Drive：使用 Google 账号授权（只读）。如果提示配置错误，请参考 README 在 Google Cloud Console 中注册应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("译读 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineConfig(ts: TranslationSettings, vm: SettingsViewModel, models: Set<String>, modelBusy: String?) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (ts.engine) {
                Engines.MLKIT -> {
                    Text(
                        "Google ML Kit 在手机本地翻译，免费、离线可用，但质量一般；中文仅支持简体。首次使用会自动下载约 30MB 的语言模型（需要能访问 Google 服务）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("离线模型", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsViewModel.MLKIT_MODELS.forEach { (code, name) ->
                            val have = code in models || code == "en"
                            AssistChip(
                                onClick = {
                                    when {
                                        modelBusy != null -> Unit
                                        code in models && code != "en" -> vm.deleteModel(code)
                                        code !in models -> vm.downloadModel(code)
                                    }
                                },
                                label = { Text(if (have) "$name ✓" else "$name ↓") },
                                leadingIcon = if (modelBusy == code) {
                                    { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
                                } else null,
                            )
                        }
                    }
                    Text("点击未下载的语言下载模型，点击已下载的语言删除模型（英语模型为内置）。", style = MaterialTheme.typography.labelSmall)
                }
                Engines.GOOGLE -> {
                    KeyField("Google Cloud API Key", ts.googleKey) { v -> vm.update { it.copy(googleKey = v) } }
                    Hint("在 Google Cloud Console 启用 Cloud Translation API 并创建 API 密钥。")
                }
                Engines.DEEPL -> {
                    KeyField("DeepL API Key", ts.deeplKey) { v -> vm.update { it.copy(deeplKey = v) } }
                    Hint("免费版密钥以 “:fx” 结尾，会自动使用 api-free.deepl.com。")
                }
                Engines.AZURE -> {
                    KeyField("Azure 翻译密钥", ts.azureKey) { v -> vm.update { it.copy(azureKey = v) } }
                    TextField("区域（如 eastasia，全局资源可留空）", ts.azureRegion) { v -> vm.update { it.copy(azureRegion = v) } }
                }
                Engines.OPENAI -> {
                    Text("快速填写", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OPENAI_PRESETS.forEach { (name, preset) ->
                            AssistChip(onClick = {
                                vm.update { it.copy(openaiBaseUrl = preset.first, openaiModel = preset.second.ifEmpty { it.openaiModel }) }
                            }, label = { Text(name) })
                        }
                    }
                    TextField("接口地址 Base URL", ts.openaiBaseUrl, KeyboardType.Uri) { v -> vm.update { it.copy(openaiBaseUrl = v) } }
                    KeyField("API Key（本地模型可留空）", ts.openaiKey) { v -> vm.update { it.copy(openaiKey = v) } }
                    TextField("模型名称", ts.openaiModel) { v -> vm.update { it.copy(openaiModel = v) } }
                    LlmPromptField(ts, vm)
                }
                Engines.CLAUDE -> {
                    KeyField("Anthropic API Key", ts.claudeKey) { v -> vm.update { it.copy(claudeKey = v) } }
                    Text("模型", style = MaterialTheme.typography.labelLarge)
                    ClaudeEngine.MODELS.forEach { (id, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.update { it.copy(claudeModel = id) } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = ts.claudeModel == id, onClick = { vm.update { it.copy(claudeModel = id) } })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Hint("整本书翻译会产生不少 API 费用，Sonnet / Haiku 更便宜。已翻译的段落会缓存在本地，不会重复计费。")
                    LlmPromptField(ts, vm)
                }
            }
        }
    }
}

@Composable
private fun LlmPromptField(ts: TranslationSettings, vm: SettingsViewModel) {
    var text by remember(ts.llmExtraPrompt) { mutableStateOf(ts.llmExtraPrompt) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; vm.update { s -> s.copy(llmExtraPrompt = it) } },
        label = { Text("额外翻译要求（可选）") },
        placeholder = { Text("例如：人名保留原文；语气更口语化；专有名词附原文") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextField(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
