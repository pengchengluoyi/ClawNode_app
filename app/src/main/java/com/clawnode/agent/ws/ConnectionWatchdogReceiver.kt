package com.clawnode.agent.ws

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.NodeRuntime
import com.clawnode.agent.service.NodeForegroundService

/**
 * AlarmManager 触发的 WS 看门狗。协程 delay 在荣耀等 OEM 后台会被冻结，
 * 改用 [ConnectionWatchdog] 的 setAndAllowWhileIdle 在 Doze 维护窗口唤醒重连。
 */
class ConnectionWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ConnectionWatchdog.ACTION) return
        ClawLog.bp(TAG, "alarm", "watchdog tick")
        ConnectionKeepAlive.acquire(context.applicationContext)
        // 确保运行时存活（进程可能被冻结后由 alarm 唤醒），再触发重连。
        NodeForegroundService.start(context.applicationContext)
        NodeRuntime.ensureStarted(context.applicationContext)
        NodeRuntime.reconnectIfNeeded()
        ConnectionWatchdog.schedule(context.applicationContext)
    }

    companion object {
        private const val TAG = "ConnWatchdogRx"
    }
}
