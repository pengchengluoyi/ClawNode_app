package com.clawnode.agent.ws

import android.content.Context
import android.os.PowerManager
import com.clawnode.agent.core.ClawLog

/**
 * WS 连接期间持有 PARTIAL_WAKE_LOCK，降低锁屏/Doze 后 TCP 被系统挂起概率。
 * 最长 15 分钟，连接存活期间由 [WsManager] 在 onOpen 续期。
 */
object ConnectionKeepAlive {

    private const val TAG = "ConnKeepAlive"
    private const val MAX_HOLD_MS = 15 * 60 * 1000L

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        val app = context.applicationContext
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.acquire(MAX_HOLD_MS) }
            return
        }
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawNode:WsKeepAlive").apply {
            setReferenceCounted(false)
            acquire(MAX_HOLD_MS)
        }
        ClawLog.bp(TAG, "acquire", "partial wake lock held")
    }

    @Synchronized
    fun release() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wakeLock = null
        ClawLog.bp(TAG, "release", "partial wake lock released")
    }
}
