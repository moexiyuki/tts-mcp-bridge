package com.example.ttsbridge

import android.app.Application
import com.example.ttsbridge.mcp.McpServerController
import com.example.ttsbridge.tts.TtsManager

/**
 * 应用入口：持有两个全局单例——
 * - [ttsManager]：系统 TTS 封装（UI 测试与 MCP 工具共用同一实例）
 * - [serverController]：内嵌 MCP HTTP 服务器
 */
class TtsBridgeApp : Application() {

    val ttsManager: TtsManager by lazy { TtsManager(this) }

    val serverController: McpServerController by lazy { McpServerController(ttsManager) }
}
