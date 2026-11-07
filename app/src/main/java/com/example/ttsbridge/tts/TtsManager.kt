package com.example.ttsbridge.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** TTS 状态快照，供 UI 与 tts_status 工具读取。 */
data class TtsState(
    val ready: Boolean = false,
    val engineName: String = "",
    val speaking: Boolean = false,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val languageTag: String = "zh-CN",
    val voiceName: String = "",
    /** 初始化失败原因；null 表示无错误。 */
    val initError: String? = null
)

/** 统一的操作结果。 */
data class TtsResult(val ok: Boolean, val message: String)

/**
 * 对 Android [TextToSpeech] 的封装：
 *
 * 1. 所有 TTS 调用都切回主线程执行（TTS 要求带 Looper 的线程）；
 * 2. [speak] 是同步语义：挂起直到语音真正播完（或超时 / 被打断），
 *    这样 Agent 能拿到"已播完"的可靠回执；
 * 3. 通过 [StateFlow] 把状态（含初始化错误）推给 UI 层；
 * 4. [retryInit] 支持初始化失败后重建引擎。
 *
 * 注意：Android 11+ 必须在 AndroidManifest 声明
 * `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`，
 * 否则查不到系统语音引擎，onInit 会一直返回 ERROR。
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    /** 初始化结果信号，多个协程可同时等待；retryInit 时会重置。 */
    @Volatile
    private var initDeferred = CompletableDeferred<Boolean>()

    /** utteranceId -> 播完信号。 */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    @Volatile private var rate: Float = 1f
    @Volatile private var pitch: Float = 1f
    @Volatile private var languageTag: String = "zh-CN"
    @Volatile private var voiceName: String = ""

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            _state.update { it.copy(speaking = true) }
        }

        override fun onDone(utteranceId: String) {
            pending.remove(utteranceId)?.complete(true)
            if (pending.isEmpty()) _state.update { it.copy(speaking = false) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            pending.remove(utteranceId)?.complete(false)
            if (pending.isEmpty()) _state.update { it.copy(speaking = false) }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            pending.remove(utteranceId)?.complete(false)
            if (pending.isEmpty()) _state.update { it.copy(speaking = false) }
        }
    }

    init {
        mainHandler.post { createEngine() }
    }

    /** 创建（或重建）TTS 引擎。必须在主线程调用。 */
    private fun createEngine() {
        runCatching { tts?.shutdown() }
        tts = null
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val t = tts ?: return
            t.setOnUtteranceProgressListener(utteranceListener)
            t.setSpeechRate(rate)
            t.setPitch(pitch)
            val langResult = t.setLanguage(Locale.forLanguageTag(languageTag))
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                languageTag = t.language?.toLanguageTag() ?: languageTag
            }
            voiceName = t.voice?.name ?: ""
            _state.update {
                it.copy(
                    ready = true,
                    engineName = t.defaultEngine ?: "system",
                    languageTag = languageTag,
                    voiceName = voiceName,
                    initError = null
                )
            }
            initDeferred.complete(true)
        } else {
            val message = "TTS 初始化失败（status=$status）：未找到可用的语音引擎。" +
                "请安装语音引擎（如 Google 语音服务）并下载语言数据，然后点『重新初始化 TTS』"
            _state.update { it.copy(ready = false, engineName = "", initError = message) }
            initDeferred.complete(false)
        }
    }

    /** 初始化失败后重建引擎（例如用户刚装好语音引擎 / 下载完语音数据）。 */
    fun retryInit() {
        if (_state.value.ready) return
        initDeferred.complete(false) // 让还在等待的调用先返回失败
        initDeferred = CompletableDeferred() // 重置初始化信号
        _state.update { it.copy(initError = null) }
        mainHandler.post { createEngine() }
    }

    private suspend fun awaitReady(): Boolean =
        withTimeoutOrNull(15_000) { initDeferred.await() } ?: false

    private fun notReadyMessage(): String =
        _state.value.initError
            ?: "TTS 引擎未就绪（初始化超时）。请确认设备已安装语音引擎，然后点『重新初始化 TTS』"

    /**
     * 朗读文本并挂起直到播完。
     *
     * @param queue false=打断当前朗读立即开始（默认），true=排队追加到当前朗读之后
     * @param language BCP-47 语言标签，如 zh-CN
     * @param voice 语音名称（见 [listVoices]），与 language 二选一或同时给
     * @param rate 语速 0.1..4.0
     * @param pitch 音调 0.1..2.0
     */
    suspend fun speak(
        text: String,
        queue: Boolean = false,
        language: String? = null,
        voice: String? = null,
        rate: Float? = null,
        pitch: Float? = null,
        timeoutMs: Long = 180_000
    ): TtsResult {
        if (text.isBlank()) return TtsResult(false, "文本为空，未朗读")
        if (!awaitReady()) return TtsResult(false, notReadyMessage())

        language?.let { setLanguage(it) }
        voice?.let { setVoice(it) }
        rate?.let { setRate(it) }
        pitch?.let { setPitch(it) }

        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred

        val started = withContext(Dispatchers.Main) {
            val engine = tts ?: return@withContext false
            if (!queue) {
                // 打断模式：先把未完成的朗读标记为"被中断"，防止调用方一直等下去
                val iterator = pending.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key != id) {
                        iterator.remove()
                        entry.value.complete(false)
                    }
                }
                engine.stop()
            }
            engine.speak(
                text,
                if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                null,
                id
            ) == TextToSpeech.SUCCESS
        }

        if (!started) {
            pending.remove(id)
            return TtsResult(false, "朗读请求被引擎拒绝")
        }
        _state.update { it.copy(speaking = true) }

        val ok = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (ok == null) {
            pending.remove(id)
            withContext(Dispatchers.Main) { tts?.stop() }
            if (pending.isEmpty()) _state.update { it.copy(speaking = false) }
            return TtsResult(false, "朗读超时（${timeoutMs / 1000}s）")
        }
        if (pending.isEmpty()) _state.update { it.copy(speaking = false) }
        return if (ok) TtsResult(true, "朗读完成") else TtsResult(false, "朗读被中断或出错")
    }

    /** 立即停止所有朗读。 */
    fun stop() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
        mainHandler.post { tts?.stop() }
        _state.update { it.copy(speaking = false) }
    }

    suspend fun setLanguage(tag: String): TtsResult {
        if (!awaitReady()) return TtsResult(false, notReadyMessage())
        val code = withContext(Dispatchers.Main) {
            tts?.setLanguage(Locale.forLanguageTag(tag)) ?: TextToSpeech.ERROR
        }
        return when (code) {
            TextToSpeech.LANG_MISSING_DATA -> TtsResult(false, "缺少语言数据：$tag（请到语音设置下载）")
            TextToSpeech.LANG_NOT_SUPPORTED -> TtsResult(false, "引擎不支持语言：$tag")
            else -> {
                languageTag = tag
                voiceName = withContext(Dispatchers.Main) { tts?.voice?.name ?: "" }
                _state.update { it.copy(languageTag = tag, voiceName = voiceName) }
                TtsResult(true, "语言已切换为 $tag")
            }
        }
    }

    suspend fun setVoice(name: String): TtsResult {
        if (!awaitReady()) return TtsResult(false, notReadyMessage())
        val target = withContext(Dispatchers.Main) {
            tts?.voices?.firstOrNull { it.name == name }
        } ?: return TtsResult(false, "找不到语音：$name（用 list_voices 查看可用语音）")
        withContext(Dispatchers.Main) { tts?.voice = target }
        voiceName = name
        languageTag = target.locale.toLanguageTag()
        _state.update { it.copy(voiceName = name, languageTag = languageTag) }
        return TtsResult(true, "语音已切换为 $name（${target.locale.toLanguageTag()}）")
    }

    suspend fun setRate(value: Float): TtsResult {
        if (!awaitReady()) return TtsResult(false, notReadyMessage())
        val v = value.coerceIn(0.1f, 4.0f)
        withContext(Dispatchers.Main) { tts?.setSpeechRate(v) }
        rate = v
        _state.update { it.copy(rate = v) }
        return TtsResult(true, "语速已设置为 ${"%.2f".format(v)}")
    }

    suspend fun setPitch(value: Float): TtsResult {
        if (!awaitReady()) return TtsResult(false, notReadyMessage())
        val v = value.coerceIn(0.1f, 2.0f)
        withContext(Dispatchers.Main) { tts?.setPitch(v) }
        pitch = v
        _state.update { it.copy(pitch = v) }
        return TtsResult(true, "音调已设置为 ${"%.2f".format(v)}")
    }

    suspend fun listVoices(language: String? = null): String {
        if (!awaitReady()) return notReadyMessage()
        val all = withContext(Dispatchers.Main) {
            (tts?.voices ?: emptySet()).sortedWith(compareBy({ it.locale.toLanguageTag() }, { -it.quality }))
        }
        val filtered = all.filter {
            language.isNullOrBlank() || it.locale.toLanguageTag().startsWith(language, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            return "没有匹配的语音" + (language?.let { "（过滤条件：$it）" } ?: "")
        }
        return buildString {
            appendLine("共 ${filtered.size} 个语音：")
            filtered.forEach { v ->
                append("- ${v.name} | ${v.locale.toLanguageTag()} | quality=${v.quality} | latency=${v.latency}")
                if (v.features?.isNotEmpty() == true) append(" | ${v.features.joinToString(",")}")
                appendLine()
            }
        }
    }

    fun statusText(): String {
        val s = _state.value
        return buildString {
            appendLine("ready: ${s.ready}")
            s.initError?.let { appendLine("initError: $it") }
            appendLine("engine: ${s.engineName.ifEmpty { "未知" }}")
            appendLine("speaking: ${s.speaking}")
            appendLine("language: ${s.languageTag}")
            appendLine("voice: ${s.voiceName.ifEmpty { "默认" }}")
            appendLine("rate: ${s.rate}")
            appendLine("pitch: ${s.pitch}")
        }
    }
}
