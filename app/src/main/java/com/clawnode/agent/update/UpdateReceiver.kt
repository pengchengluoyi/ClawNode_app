package com.clawnode.agent.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.service.NodeForegroundService

/**
 * 监听应用更新完成（MY_PACKAGE_REPLACED），自动启动前台服务和连接逻辑，
 * 让 ClawNode 更新后无需用户手动打开 App 就能重新在线。
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ClawLog.bp("UpdateReceiver", "package_replaced", "auto-starting after update/replace")
            try {
                NodeForegroundService.start(context.applicationContext)
            } catch (e: Exception) {
                ClawLog.w("UpdateReceiver", "start_fg_failed", e.message ?: "")
            }

            // We no longer auto-launch the screen capture authorization dialog from the update receiver.
            // This avoids the system prompt appearing "out of nowhere" after an app update while
            // the agent may be executing instructions. The user can re-authorize from the app UI.
            // NodeForegroundService is still started so the node can reconnect.
        }
    }
}