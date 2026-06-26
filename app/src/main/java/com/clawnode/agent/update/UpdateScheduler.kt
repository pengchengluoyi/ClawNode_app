package com.clawnode.agent.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.clawnode.agent.core.ClawLog
import java.util.Calendar

/**
 * 每日定时自动更新调度：每天凌晨 03:00 触发一次检查 + 静默更新。
 *
 * 用 setExactAndAllowWhileIdle / setAndAllowWhileIdle（穿透 Doze）安排到下一个 03:00，
 * 触发后由 [UpdateAlarmReceiver] 执行更新并重排下一天，形成 24h 周期。
 */
object UpdateScheduler {

    const val ACTION = "com.clawnode.agent.DAILY_UPDATE_CHECK"
    private const val TAG = "UpdateScheduler"
    private const val REQUEST_CODE = 0xC1A8
    private const val UPDATE_HOUR = 3   // 凌晨 3 点

    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = nextTriggerMillis()
        val pi = pendingIntent(app)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // setAndAllowWhileIdle 不需精确闹钟权限，且能穿透 Doze；凌晨触发对精度不敏感。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            ClawLog.bp(TAG, "schedule", "next=${triggerAt} (03:00 daily)")
        } catch (e: SecurityException) {
            ClawLog.w(TAG, "schedule_fail", e.message ?: "")
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, UpdateAlarmReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** 下一个 03:00 的绝对时间戳（若已过今天 3 点则取明天）。 */
    private fun nextTriggerMillis(): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, UPDATE_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
