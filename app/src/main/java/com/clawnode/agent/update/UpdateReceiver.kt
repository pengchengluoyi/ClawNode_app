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

            // If screen capture was previously authorized, auto re-launch the request so capture works
            // immediately after update without user manually opening the app and clicking buttons.
            try {
                if (com.clawnode.agent.vision.MediaProjectionHolder.hasPriorGrant(context.applicationContext) &&
                    !com.clawnode.agent.vision.MediaProjectionHolder.hasAuthorization()) {
                    val i = Intent(context.applicationContext, com.clawnode.agent.system.MediaProjectionRequestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(com.clawnode.agent.system.MediaProjectionRequestActivity.EXTRA_MODE,
                            com.clawnode.agent.system.MediaProjectionRequestActivity.MODE_AUTHORIZE)
                    context.applicationContext.startActivity(i)
                }
            } catch (_: Exception) {}
        }
    }
}