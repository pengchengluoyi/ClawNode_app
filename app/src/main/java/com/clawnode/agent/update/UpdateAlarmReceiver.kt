package com.clawnode.agent.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clawnode.agent.core.ClawLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 每日 03:00 定时更新触发器：执行静默自动更新，并重排下一天的闹钟。
 */
class UpdateAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != UpdateScheduler.ACTION) return
        ClawLog.bp(TAG, "alarm", "daily update check fired")
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppUpdateManager.autoUpdateIfNeeded(app)
            } catch (e: Exception) {
                ClawLog.w(TAG, "update_fail", e.message ?: "")
            } finally {
                // 重排下一天（形成 24h 周期）
                UpdateScheduler.schedule(app)
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateAlarmRx"
    }
}
