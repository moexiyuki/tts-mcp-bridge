package com.example.ttsbridge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ttsbridge.mcp.McpServerController
import com.example.ttsbridge.mcp.McpServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 界面状态快照。 */
data class UiState(
    val serverRunning: Boolean = false,
    val serverError: String? = null,
    val port: Int = McpServerController.DEFAULT_PORT,
    val addresses: List<String> = emptyList(),
    val ready: Boolean = false,
    val initError: String? = null,
    val engineName: String = "",
    val speaking: Boolean = false,
    val languageTag: String = "zh-CN",
    val voiceName: String = "",
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val testText: String = "你好，我是运行在 Android 设备上的 TTS Bridge。",
    val resultMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TtsBridgeApp
    private val tts = app.ttsManager
    private val server = app.serverController

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        _ui.update { it.copy(addresses = McpServerController.localAddresses()) }
        viewModelScope.launch {
            tts.state.collect { s ->
                _ui.update {
                    it.copy(
                        ready = s.ready,
                        initError = s.initError,
                        engineName = s.engineName,
                        speaking = s.speaking,
                        languageTag = s.languageTag,
                        voiceName = s.voiceName,
                        rate = s.rate,
                        pitch = s.pitch
                    )
                }
            }
        }
        viewModelScope.launch {
            server.running.collect { r -> _ui.update { it.copy(serverRunning = r) } }
        }
        viewModelScope.launch {
            server.lastError.collect { e -> _ui.update { it.copy(serverError = e) } }
        }
    }

    /** 启动 / 停止 MCP 前台服务。 */
    fun toggleServer() {
        if (_ui.value.serverRunning) {
            McpServerService.stop(getApplication())
        } else {
            _ui.update { it.copy(addresses = McpServerController.localAddresses()) }
            McpServerService.start(getApplication())
        }
    }

    /** TTS 初始化失败后重新初始化（比如用户刚装好语音引擎）。 */
    fun retryInit() {
        tts.retryInit()
    }

    fun setTestText(text: String) {
        _ui.update { it.copy(testText = text) }
    }

    fun speakTest() {
        viewModelScope.launch {
            val result = tts.speak(_ui.value.testText)
            _ui.update { it.copy(resultMessage = result.message) }
        }
    }

    fun stopSpeaking() {
        tts.stop()
        _ui.update { it.copy(resultMessage = "已停止朗读") }
    }

    fun setRate(value: Float) {
        viewModelScope.launch { tts.setRate(value) }
    }

    fun setPitch(value: Float) {
        viewModelScope.launch { tts.setPitch(value) }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            val result = tts.setLanguage(tag)
            _ui.update { it.copy(resultMessage = result.message) }
        }
    }
}
