package com.mgmemo.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mgmemo.app.viewmodel.NotesViewModel

private data class ThemeEntry(val label: String, val mode: String)

private val themeOptions = listOf(
    ThemeEntry("跟随系统", "system"),
    ThemeEntry("浅色", "light"),
    ThemeEntry("深色", "dark"),
    ThemeEntry("护眼绿", "green")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: NotesViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()

    var aiUrl by remember { mutableStateOf(settings.aiApiUrl) }
    var aiKey by remember { mutableStateOf(settings.aiApiKey) }
    var aiModel by remember { mutableStateOf(settings.aiModel) }

    LaunchedEffect(settings) {
        aiUrl = settings.aiApiUrl
        aiKey = settings.aiApiKey
        aiModel = settings.aiModel
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                title = { Text("设置") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "外观",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Text(
                    text = "主题模式（点击可实时预览）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(themeOptions.size) { index ->
                val option = themeOptions[index]
                ThemeOptionRow(
                    label = option.label,
                    selected = settings.themeMode == option.mode,
                    onClick = { viewModel.updateThemeMode(option.mode) }
                )
            }

            item {
                Text(
                    text = "编辑页布局",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            item {
                Text(
                    text = "默认视图，编辑页工具栏可随时切换",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                ThemeOptionRow(
                    label = "仅渲染预览",
                    selected = settings.editorLayout == "preview",
                    onClick = { viewModel.updateEditorLayout("preview") }
                )
            }
            item {
                ThemeOptionRow(
                    label = "双栏实时预览",
                    selected = settings.editorLayout == "split",
                    onClick = { viewModel.updateEditorLayout("split") }
                )
            }

            item { HorizontalDivider() }

            item {
                Text(
                    text = "隐私与安全",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("生物识别锁", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "从后台返回超过 5 分钟需验证",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.enableBiometric,
                        onCheckedChange = viewModel::updateBiometric
                    )
                }
            }

            item { HorizontalDivider() }

            item {
                Text(
                    text = "AI 智能摘要",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                OutlinedTextField(
                    value = aiUrl,
                    onValueChange = { aiUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API 地址（OpenAI 兼容）") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = aiKey,
                    onValueChange = { aiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            item {
                OutlinedTextField(
                    value = aiModel,
                    onValueChange = { aiModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型名称") },
                    placeholder = { Text("gpt-4o-mini") },
                    singleLine = true
                )
            }
            item {
                Button(
                    onClick = { viewModel.updateAiConfig(aiUrl, aiKey, aiModel) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("保存 AI 配置")
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "MGMemo v1.0 · 全能型原生备忘录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}