package com.clawnode.agent.ws

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.clawnode.agent.core.ClawLog

/**
 * 监听系统默认网络切换（WiFi↔蜂窝、断网恢复），触发 WS 立即重连。
 *
 * 弥补 [ConnectionWatchdog] 的 45s 轮询盲区：网络切换瞬间旧 socket 已失效，
 * 等看门狗会让节点离线最长 45s。这里在 onAvailable/onLost 立刻回调自愈。
 */
class NetworkMonitor(
    private val context: Context,
    private val onChanged: (hasNetwork: Boolean) -> Unit,
) {
    private val cm: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var lastNetworkId: Long? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val id = network.networkHandle
            if (lastNetworkId == id) {
                // 同一网络的能力变化不算切换
                return
            }
            val first = lastNetworkId == null
            lastNetworkId = id
            ClawLog.bp(TAG, "net_available", "handle=$id first=$first")
            onChanged(true)
        }

        override fun onLost(network: Network) {
            if (lastNetworkId == network.networkHandle) {
                lastNetworkId = null
            }
            ClawLog.bp(TAG, "net_lost", "handle=${network.networkHandle}")
            onChanged(false)
        }
    }

    fun start() {
        val manager = cm ?: run {
            ClawLog.w(TAG, "start_fail", "no ConnectivityManager")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                @Suppress("DEPRECATION")
                manager.registerNetworkCallback(request, callback)
            }
            ClawLog.bp(TAG, "start", "default network callback registered")
        }.onFailure { e ->
            ClawLog.w(TAG, "start_fail", e.message ?: "")
        }
    }

    fun stop() {
        val manager = cm ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        lastNetworkId = null
        ClawLog.bp(TAG, "stop", "network callback unregistered")
    }

    companion object {
        private const val TAG = "NetworkMonitor"
    }
}
