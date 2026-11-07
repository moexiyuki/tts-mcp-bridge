package com.example.ttsbridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as DeviceSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ttsbridge.UiState
import com.example.ttsbridge.ui.theme.TtsBridgeTheme

/** 界面所有交互回调，保持 MainScreen 为无状态组件，方便预览与复用。 */
data class MainActions(
    val toggleServer: () -> Unit = {},
    val setTestText: (String) -> Unit = {},
    val speakTest: () -> Unit = {},
    val stopSpeaking: () -> Unit = {},
    val setRate: (Float) -> Unit = {},
    val setPitch: (Float) -> Unit = {},
    val setLanguage: (String) -> Unit = {},
    val retryInit: () -> Unit = {}
)

private val sampleLanguages = listOf("zh-CN", "zh-TW", "en-US", "en-GB", "ja-JP", "ko-KR")

private val toolNames = listOf(
    "speak", "stop_speaking", "list_voices", "set_voice",
    "set_language", "set_speech_rate", "set_pitch", "tts_status"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: UiState, actions: MainActions) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("TTS Bridge")
                        Text(
                            "MCP · Android 系统语音桥",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = { ToolbarMenu() },
                colors = TopAppBarDefaults.largeTopAppBarColors()
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { ServerCard(state, actions.toggleServer) }
            item { TestCard(state, actions) }
            item { ToolsCard() }
            item { ClientConfigCard(state) }
            item {
                Text(
                    "安全提示：服务监听整个局域网且未做鉴权，请勿把端口暴露到公网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------- 顶栏菜单：电池优化 ----------------

@Composable
private fun ToolbarMenu() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // 每次重组（菜单展开）时重新检测，保证从设置页返回后状态是最新的
    val whitelisted = isIgnoringBatteryOptimizations(context)

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "更多选项")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = {
                Text(if (whitelisted) "电池优化白名单（已加入）" else "申请电池优化白名单")
            },
            leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
            enabled = !whitelisted,
            onClick = {
                expanded = false
                requestIgnoreBatteryOptimizations(context)
            }
        )
        DropdownMenuItem(
            text = { Text("电池优化设置列表") },
            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            onClick = {
                expanded = false
                openBatteryOptimizationSettings(context)
            }
        )
    }
}

/** 是否已加入电池优化白名单。 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

/**
 * 申请加入电池优化白名单：弹出系统一次性授权对话框。
 * 厂商 ROM 不支持时回退到应用详情页手动设置。
 */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val request = Intent(DeviceSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
    runCatching { context.startActivity(request) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(DeviceSettings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                )
            }.onFailure {
                Toast.makeText(context, "无法打开电池优化设置", Toast.LENGTH_SHORT).show()
            }
        }
}

/** 打开系统电池优化设置列表。厂商 ROM 不支持时回退到系统设置首页。 */
private fun openBatteryOptimizationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(DeviceSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.onFailure {
        runCatching { context.startActivity(Intent(DeviceSettings.ACTION_SETTINGS)) }
            .onFailure {
                Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
            }
    }
}

// ---------------- 页面卡片 ----------------

@Composable
private fun ServerCard(state: UiState, onToggle: () -> Unit) {
    val context = LocalContext.current
    val endpoint = "http://${state.addresses.firstOrNull() ?: "<设备IP>"}:${state.port}/mcp"

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("MCP 服务", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = state.serverRunning, onCheckedChange = { onToggle() })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (state.serverRunning) Color(0xFF2E7D32)
                            else MaterialTheme.colorScheme.outline,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.serverRunning) "运行中 · 端口 ${state.port}" else "已停止",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (state.serverRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val clipboard =
                            context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("MCP endpoint", endpoint)
                        )
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制端点")
                    }
                }
                if (state.addresses.size > 1) {
                    Text(
                        "其他地址：" + state.addresses.drop(1)
                            .joinToString("  ") { "http://${it}:${state.port}/mcp" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.serverError?.let {
                Text(
                    "启动失败：$it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TestCard(state: UiState, actions: MainActions) {
    val context = LocalContext.current

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("本地朗读测试", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (state.speaking) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = "正在朗读",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            OutlinedTextField(
                value = state.testText,
                onValueChange = actions.setTestText,
                label = { Text("测试文本") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = actions.speakTest) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("朗读")
                }
                OutlinedButton(onClick = actions.stopSpeaking) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            }

            HorizontalDivider()

            Text("语速 ${"%.2f".format(state.rate)}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.rate,
                onValueChange = actions.setRate,
                valueRange = 0.1f..3f
            )

            Text("音调 ${"%.2f".format(state.pitch)}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.pitch,
                onValueChange = actions.setPitch,
                valueRange = 0.5f..2f
            )

            Text("语言", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                sampleLanguages.forEach { tag ->
                    FilterChip(
                        selected = state.languageTag == tag,
                        onClick = { actions.setLanguage(tag) },
                        label = { Text(tag) }
                    )
                }
            }

            Text(
                "引擎：${state.engineName.ifEmpty { "未就绪" }} · 语音：${state.voiceName.ifEmpty { "默认" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 初始化失败：显示原因 + 提供重试和系统语音设置入口
            state.initError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!state.ready) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = actions.retryInit) {
                        Text("重新初始化 TTS")
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent("com.android.settings.TTS_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) {
                        Text("打开语音设置")
                    }
                }
            }

            state.resultMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("暴露给 Agent 的 MCP 工具", style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                toolNames.forEach { name ->
                    AssistChip(onClick = {}, label = { Text(name) })
                }
            }
        }
    }
}

@Composable
private fun ClientConfigCard(state: UiState) {
    val ip = state.addresses.firstOrNull() ?: "<设备IP>"
    val config = """
        {
          "mcpServers": {
            "tts-bridge": {
              "url": "http://$ip:${state.port}/mcp"
            }
          }
        }
    """.trimIndent()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Agent 接入示例", style = MaterialTheme.typography.titleMedium)
            }
            Text("在支持 Streamable HTTP 的 MCP 客户端中添加：")
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    config,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Text(
                "只支持 stdio 的客户端可用 npx mcp-remote http://$ip:${state.port}/mcp 桥接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "试试对 Agent 说：用 speak 工具朗读“今天天气不错”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewMainScreen() {
    TtsBridgeTheme {
        MainScreen(
            state = UiState(
                serverRunning = true,
                addresses = listOf("192.168.1.23"),
                ready = true,
                engineName = "com.google.android.tts"
            ),
            actions = MainActions()
        )
    }
}
