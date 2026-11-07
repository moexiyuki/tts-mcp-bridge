package com.example.ttsbridge.mcp

import com.example.ttsbridge.tts.TtsManager
import com.example.ttsbridge.tts.TtsResult
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*

/**
 * MCP (Model Context Protocol) 服务端协议实现。
 *
 * 只做三件事：initialize / tools/list / tools/call（外加 ping），
 * 传输层（Streamable HTTP）由 [McpServerController] 负责。
 */
class McpProtocol(private val tts: TtsManager) {

    /** 按客户端协商结果回显；不支持的版本回退到最新。 */
    private val supportedVersions = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    companion object {
        const val SERVER_NAME = "tts-bridge"
        const val SERVER_VERSION = "1.0.0"
    }

    /**
     * 处理一条（或一批）JSON-RPC 消息。
     *
     * @return HTTP 状态码 + 响应体；响应体为 null 表示通知/空响应（202 Accepted）
     */
    suspend fun handle(raw: String): Pair<HttpStatusCode, String?> {
        val root = try {
            Json.parseToJsonElement(raw)
        } catch (e: Exception) {
            return HttpStatusCode.OK to rpcError(null, -32700, "Parse error").toString()
        }

        return when (root) {
            is JsonArray -> {
                // 批量请求（旧版客户端）
                val responses = root.mapNotNull { (it as? JsonObject)?.let { msg -> handleMessage(msg) } }
                HttpStatusCode.OK to JsonArray(responses).toString()
            }
            is JsonObject -> {
                val response = handleMessage(root)
                if (response == null) HttpStatusCode.Accepted to null
                else HttpStatusCode.OK to response.toString()
            }
            else -> HttpStatusCode.OK to rpcError(null, -32600, "Invalid Request").toString()
        }
    }

    private suspend fun handleMessage(msg: JsonObject): JsonElement? {
        val method = msg.str("method")
        val id = msg["id"]
        // 没有 method：是客户端回给我们的响应，忽略
        if (method == null) return null
        // 有 method 无 id：通知（如 notifications/initialized），无需回复
        if (id == null || id is JsonNull) return null
        return try {
            dispatch(method, msg["params"], id)
        } catch (e: Exception) {
            rpcError(id, -32603, "Internal error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun dispatch(method: String, params: JsonElement?, id: JsonElement): JsonElement =
        when (method) {
            "initialize" -> rpcResult(id, initializeResult(params))
            "ping" -> rpcResult(id, buildJsonObject { })
            "tools/list" -> rpcResult(id, buildJsonObject { put("tools", toolsJson()) })
            "tools/call" -> toolsCall(params, id)
            else -> rpcError(id, -32601, "Method not found: $method")
        }

    // ---------------- initialize ----------------

    private fun initializeResult(params: JsonElement?): JsonObject {
        val requested = (params as? JsonObject)?.str("protocolVersion")
        val version = if (requested != null && requested in supportedVersions) {
            requested
        } else {
            supportedVersions.first()
        }
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
            })
            put("serverInfo", buildJsonObject {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            })
            put(
                "instructions",
                "通过此服务器驱动 Android 设备的系统 TTS 朗读。speak 是同步接口：" +
                    "返回即代表语音已播放完成。可先用 list_voices / set_language 调整声音，" +
                    "用 stop_speaking 随时打断。"
            )
        }
    }

    // ---------------- tools ----------------

    private fun toolsJson(): JsonArray = buildJsonArray {
        add(tool(
            name = "speak",
            description = "让设备朗读文本（系统 TTS）。同步接口：返回结果时语音已播放完成。",
            properties = buildJsonObject {
                put("text", prop("string", "要朗读的文本"))
                put("queue", prop("boolean", "false=打断当前朗读立即开始（默认），true=排队追加到之后", JsonPrimitive(false)))
                put("language", prop("string", "BCP-47 语言标签，如 zh-CN、en-US；不填沿用当前设置"))
                put("voice", prop("string", "语音名称，见 list_voices"))
                put("rate", prop("number", "语速 0.1~4.0，1.0 为正常"))
                put("pitch", prop("number", "音调 0.1~2.0，1.0 为正常"))
            },
            required = listOf("text")
        ))
        add(tool(
            name = "stop_speaking",
            description = "立即停止当前所有朗读。",
            properties = buildJsonObject { }
        ))
        add(tool(
            name = "list_voices",
            description = "列出设备 TTS 引擎可用的语音（名称、语言、质量）。",
            properties = buildJsonObject {
                put("language", prop("string", "按语言前缀过滤，如 zh-CN"))
            }
        ))
        add(tool(
            name = "set_voice",
            description = "切换到指定语音。",
            properties = buildJsonObject {
                put("voice", prop("string", "语音名称，见 list_voices"))
            },
            required = listOf("voice")
        ))
        add(tool(
            name = "set_language",
            description = "切换朗读语言。",
            properties = buildJsonObject {
                put("language", prop("string", "BCP-47 语言标签"))
            },
            required = listOf("language")
        ))
        add(tool(
            name = "set_speech_rate",
            description = "设置语速。",
            properties = buildJsonObject {
                put("rate", prop("number", "0.1~4.0，1.0 为正常"))
            },
            required = listOf("rate")
        ))
        add(tool(
            name = "set_pitch",
            description = "设置音调。",
            properties = buildJsonObject {
                put("pitch", prop("number", "0.1~2.0，1.0 为正常"))
            },
            required = listOf("pitch")
        ))
        add(tool(
            name = "tts_status",
            description = "查看 TTS 当前状态（引擎、语言、语音、语速、是否在朗读）。",
            properties = buildJsonObject { }
        ))
    }

    private suspend fun toolsCall(params: JsonElement?, id: JsonElement): JsonElement {
        val obj = params as? JsonObject ?: return rpcError(id, -32602, "Invalid params")
        val name = obj.str("name") ?: return rpcError(id, -32602, "缺少工具名称")
        val args = obj["arguments"] as? JsonObject ?: buildJsonObject { }
        return try {
            when (name) {
                "speak" -> toolResult(id, runSpeak(args))
                "stop_speaking" -> {
                    tts.stop()
                    toolResult(id, TtsResult(true, "已停止朗读"))
                }
                "list_voices" -> toolResult(id, TtsResult(true, tts.listVoices(args.str("language"))))
                "set_voice" -> {
                    val voice = args.str("voice")
                        ?: return toolResponse(id, "缺少 voice 参数", true)
                    toolResult(id, tts.setVoice(voice))
                }
                "set_language" -> {
                    val language = args.str("language")
                        ?: return toolResponse(id, "缺少 language 参数", true)
                    toolResult(id, tts.setLanguage(language))
                }
                "set_speech_rate" -> {
                    val rate = args.num("rate")
                        ?: return toolResponse(id, "缺少 rate 参数", true)
                    toolResult(id, tts.setRate(rate))
                }
                "set_pitch" -> {
                    val pitch = args.num("pitch")
                        ?: return toolResponse(id, "缺少 pitch 参数", true)
                    toolResult(id, tts.setPitch(pitch))
                }
                "tts_status" -> toolResult(id, TtsResult(true, tts.statusText()))
                else -> rpcError(id, -32602, "Unknown tool: $name")
            }
        } catch (e: Exception) {
            // 工具执行出错属于"业务错误"，按规范放进 result 并置 isError
            toolResponse(id, "工具执行异常：${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    private suspend fun runSpeak(args: JsonObject): TtsResult {
        val text = args.str("text") ?: return TtsResult(false, "缺少 text 参数")
        return tts.speak(
            text = text,
            queue = args.bool("queue") ?: false,
            language = args.str("language"),
            voice = args.str("voice"),
            rate = args.num("rate"),
            pitch = args.num("pitch")
        )
    }

    // ---------------- JSON 构造辅助 ----------------

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String> = emptyList()
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", properties)
            putJsonArray("required") { required.forEach { add(it) } }
        })
    }

    private fun prop(type: String, description: String, default: JsonElement? = null): JsonObject =
        buildJsonObject {
            put("type", type)
            put("description", description)
            if (default != null) put("default", default)
        }

    private fun toolResult(id: JsonElement, result: TtsResult): JsonObject =
        toolResponse(id, result.message, !result.ok)

    private fun toolResult(id: JsonElement, text: String): JsonObject =
        toolResponse(id, text, false)

    private fun toolResponse(id: JsonElement, text: String, isError: Boolean): JsonObject =
        rpcResult(id, buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            put("isError", isError)
        })

    private fun rpcResult(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
