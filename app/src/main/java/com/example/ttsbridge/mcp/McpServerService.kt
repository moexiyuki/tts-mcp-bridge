package com.example.ttsbridge.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.ttsbridge.MainActivity
import com.example.ttsbridge.R
import com.example.ttsbridge.TtsBridgeApp

/**
 * 前台服务：持有 MCP 服务器，保证 App 退到后台后 Agent 仍可连接。
 *
 * 后台可连接的两个保障：
 * 1. 前台服务 —— 保进程优先级，不被低内存查杀，也不会被"缓存进程冻结"冻住；
 * 2. 唤醒锁（见 [com.example.ttsbridge.WakeLockManager]）—— 息屏/休眠后 CPU 仍工作，
 *    Ktor 事件循环能继续响应；是否持锁由用户在顶栏菜单的开关控制。
 */
class McpServerService : Service() {

    companion object {
        const val CHANNEL_ID = "mcp_server"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, McpServerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpServerService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        val app = application as TtsBridgeApp
        app.wakeLockManager.setServiceActive(true)
        app.serverController.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统拉起重建时兜底：确保服务器真的在跑
        val app = application as TtsBridgeApp
        if (!app.serverController.running.value) {
            app.serverController.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as TtsBridgeApp
        app.serverController.stop()
        app.wakeLockManager.setServiceActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "TTS Bridge MCP 服务运行状态"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, McpServerController.DEFAULT_PORT))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
