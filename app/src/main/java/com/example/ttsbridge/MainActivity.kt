package com.example.ttsbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ttsbridge.ui.MainActions
import com.example.ttsbridge.ui.MainScreen
import com.example.ttsbridge.ui.theme.TtsBridgeTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            TtsBridgeTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.ui.collectAsState()
                MainScreen(
                    state = state,
                    actions = MainActions(
                        toggleServer = viewModel::toggleServer,
                        setTestText = viewModel::setTestText,
                        speakTest = viewModel::speakTest,
                        stopSpeaking = viewModel::stopSpeaking,
                        setRate = viewModel::setRate,
                        setPitch = viewModel::setPitch,
                        setLanguage = viewModel::setLanguage,
                        retryInit = viewModel::retryInit,
                        setWakeLock = viewModel::setWakeLock
                    )
                )
            }
        }
    }

    /** Android 13+ 需要通知权限才能显示前台服务通知（服务本身仍可运行）。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
