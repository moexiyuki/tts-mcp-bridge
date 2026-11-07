package com.example.ttsbridge

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 唤醒锁管理：实际持锁 = 用户开关（持久化）&& 前台服务在运行。
 *
 * - 开关默认开启：即使 Agent 走本地回环，深度睡眠时 CPU 挂起也无法响应；
 * - 开关状态存 SharedPreferences，App / 服务重启后依然生效；
 * - 服务未运行时只记录偏好、不持锁，避免后台空耗电。
 */
class WakeLockManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_WAKE_LOCK_ENABLED = "wake_lock_enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceActive = false

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_WAKE_LOCK_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 用户开关（持久化；服务未运行时仅记录偏好）。 */
    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean(KEY_WAKE_LOCK_ENABLED, value).apply()
        refresh()
    }

    /** 前台服务生命周期回调：true=服务开始运行，false=服务已销毁。 */
    fun setServiceActive(active: Boolean) {
        serviceActive = active
        refresh()
    }

    private fun refresh() {
        val shouldHold = _enabled.value && serviceActive
        if (shouldHold) {
            if (wakeLock == null) {
                wakeLock = powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ttsbridge:server")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        } else {
            runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            wakeLock = null
        }
    }
}
