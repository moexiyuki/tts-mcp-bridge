package com.example.ttsbridge.mcp

import com.example.ttsbridge.tts.TtsManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 内嵌 MCP HTTP 服务器（Ktor CIO，监听 0.0.0.0，局域网可访问）。
 *
 * 端点：
 * - POST /mcp    —— MCP Streamable HTTP 传输（JSON-RPC 2.0）
 * - GET  /health —— 健康检查
 * - GET  /       —— 简易说明页
 */
class McpServerController(private val tts: TtsManager) {

    companion object {
        const val DEFAULT_PORT = 8765

        /** 枚举本机 IPv4 地址（排除回环），用于展示给用户配置 Agent。 */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var stopServer: (() -> Unit)? = null

    fun start(port: Int = DEFAULT_PORT) {
        if (_running.value) return
        try {
            val protocol = McpProtocol(tts)
            val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                routing {
                    post("/mcp") {
                        val body = call.receiveText()
                        val (status, response) = protocol.handle(body)
                        if (response == null) {
                            // 通知或空响应：按 Streamable HTTP 规范返回 202
                            call.respondText("", status = status)
                        } else {
                            call.respondText(response, ContentType.Application.Json, status)
                        }
                    }
                    get("/health") {
                        call.respondText(
                            """{"status":"ok","server":"${McpProtocol.SERVER_NAME}","ttsReady":${tts.state.value.ready}}""",
                            ContentType.Application.Json
                        )
                    }
                    get("/") {
                        call.respondText(
                            buildString {
                                appendLine("tts-bridge ${McpProtocol.SERVER_VERSION} - MCP server")
                                appendLine("POST JSON-RPC messages to /mcp")
                                appendLine("GET /health")
                            },
                            ContentType.Text.Plain
                        )
                    }
                }
            }
            server.start(wait = false)
            stopServer = { server.stop(500, 3000) }
            _running.value = true
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = e.message ?: e.javaClass.simpleName
            _running.value = false
        }
    }

    fun stop() {
        val stop = stopServer
        stopServer = null
        _running.value = false
        if (stop != null) {
            // stop 可能阻塞到超时，放到后台线程执行
            Thread { runCatching { stop.invoke() } }.start()
        }
    }
}
