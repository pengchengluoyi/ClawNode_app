package com.clawnode.agent.ws

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.clawnode.agent.core.ClawLog

/**
 * 使用 AlarmManager 周期性唤醒 WS 重连，弥补后台 coroutine 被 OEM 冻结的问题。
 */
object ConnectionWatchdog {

    const val ACTION = "com.clawnode.agent.CONNECTION_WATCHDOG"
    private const val TAG = "ConnWatchdog"
    private const val REQUEST_CODE = 0xC1A6
    /** 略小于服务端 180s 离线宽限，便于后台断线后自愈 */
    private const val INTERVAL_MS = 45_000L

    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(app, ConnectionWatchdogReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(app, REQUEST_CODE, intent, flags)
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            ClawLog.bp(TAG, "schedule", "nextMs=$INTERVAL_MS")
        } catch (e: SecurityException) {
            ClawLog.w(TAG, "schedule_fail", e.message ?: "")
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(app, ConnectionWatchdogReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(app, REQUEST_CODE, intent, flags)
        am.cancel(pi)
        ClawLog.bp(TAG, "cancel", "alarm cleared")
    }
}
