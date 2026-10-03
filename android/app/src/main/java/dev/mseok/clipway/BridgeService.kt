package dev.mseok.clipway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.mseok.clipway.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and tells [Bridge] when the screen turns on or off and
 * when the network changes. The notification shows the current state.
 */
class BridgeService : Service() {
    private val bridge get() = (application as BridgeApp).bridge
    private var statusJob: Job? = null
    private var lastNetwork: Network? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            bridge.setInteractive(intent.action != Intent.ACTION_SCREEN_OFF)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // The first callback only reports the network that is already in use.
            if (lastNetwork != null && lastNetwork != network) bridge.reconnectAll()
            lastNetwork = network
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "연결 상태", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(
            NOTIFICATION_ID, buildNotification("시작하는 중"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)

        bridge.watcher.start()
        bridge.setInteractive(getSystemService(PowerManager::class.java).isInteractive)

        statusJob = bridge.scope.launch {
            combine(bridge.macs, bridge.connected, bridge.watcher.state) { macs, connected, watcher ->
                val link = when {
                    macs.isEmpty() -> "페어링된 Mac 없음"
                    connected.isEmpty() -> "Mac 연결 대기 중"
                    else -> macs.filter { it.id in connected }.joinToString { it.name } + " 연결됨"
                }
                val detect = if (watcher == ClipboardWatcher.State.WATCHING) "자동 감지 켜짐" else "자동 감지 꺼짐 · 타일로 수동 전송"
                "$link · $detect"
            }.collect { text ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        statusJob?.cancel()
        unregisterReceiver(screenReceiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        bridge.setInteractive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bridge)
            .setContentTitle("Clipway")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "status"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }
    }
}
