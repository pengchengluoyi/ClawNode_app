package com.clawnode.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clawnode.agent.R
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.ConfigManager
import com.clawnode.agent.discovery.NodeBeacon
import com.clawnode.agent.pairing.PairingHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 节点常驻前台服务（dataSync 类型）。
 *
 * 在无障碍服务连接后启动，显示持续通知，降低进程被 OEM 杀死的概率，
 * 并为 WebSocket 心跳 / 后台指令提供稳定的进程与网络环境。
 * 同时在此服务内周期性重新注册 mDNS，避免熄屏/Doze 后局域网发现失效。
 */
class NodeForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settingsJob: Job? = null
    private var beaconRefreshJob: Job? = null
    private var pairingServerUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ClawLog.bp(TAG, "onCreate", "node foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ClawLog.bp(TAG, "onStartCommand", "stop requested")
                stopBeaconLoop()
                PairingHttpServer.stop()
                NodeBeacon.stop(applicationContext)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ClawLog.bp(TAG, "onStartCommand", "enter foreground")
                promoteToForeground()
                startBeaconLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBeaconLoop()
        PairingHttpServer.stop()
        NodeBeacon.stop(applicationContext)
        scope.cancel()
        ClawLog.bp(TAG, "onDestroy", "node foreground service destroyed")
        super.onDestroy()
    }

    private fun startBeaconLoop() {
        if (settingsJob?.isActive == true) return
        val config = ConfigManager.get(applicationContext)
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Node" }

        settingsJob = scope.launch {
            config.settings.collect { settings ->
                val paired = !settings.userUnpaired && settings.authToken.isNotBlank()
                syncPairingServer(!paired)
                NodeBeacon.start(applicationContext, config.defaultNodeSn, model, paired)
            }
        }

        beaconRefreshJob = scope.launch {
            while (isActive) {
                delay(BEACON_REFRESH_MS)
                val settings = config.settings.first()
                val paired = !settings.userUnpaired && settings.authToken.isNotBlank()
                NodeBeacon.start(applicationContext, config.defaultNodeSn, model, paired)
            }
        }
    }

    private fun syncPairingServer(needListener: Boolean) {
        if (needListener && !pairingServerUp) {
            PairingHttpServer.start()
            pairingServerUp = true
            ClawLog.bp(TAG, "pair_listener", "started port=${PairingHttpServer.PORT}")
        } else if (!needListener && pairingServerUp) {
            PairingHttpServer.stop()
            pairingServerUp = false
        }
    }

    private fun stopBeaconLoop() {
        settingsJob?.cancel()
        settingsJob = null
        beaconRefreshJob?.cancel()
        beaconRefreshJob = null
        pairingServerUp = false
    }

    private fun promoteToForeground() {
        val channelId = ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.node_notification_title))
            .setContentText(getString(R.string.node_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.node_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "NodeFgService"
        private const val CHANNEL_ID = "clawnode_node"
        private const val NOTIF_ID = 0xC1A5
        private const val ACTION_STOP = "com.clawnode.agent.STOP_NODE_FG"
        private const val BEACON_REFRESH_MS = 30_000L

        fun start(context: Context) {
            ClawLog.bp(TAG, "start", "launching node foreground service")
            val intent = Intent(context, NodeForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            ClawLog.bp(TAG, "stop", "stopping node foreground service")
            val intent = Intent(context, NodeForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
