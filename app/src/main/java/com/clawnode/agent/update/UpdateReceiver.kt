package com.clawnode.agent.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.service.NodeForegroundService

/**
 * 监听应用更新完成（MY_PACKAGE_REPLACED）与开机完成（BOOT_COMPLETED），
 * 自动启动前台服务和连接逻辑，让 ClawNode 在更新后 / 手机重启后都无需用户
 * 手动打开 App 就能重新在线。
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ClawLog.bp("UpdateReceiver", "package_replaced", "auto-starting after update/replace")
                startNode(context)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                ClawLog.bp("UpdateReceiver", "boot_completed", "auto-starting after device boot")
                startNode(context)
            }
        }
    }

    private fun startNode(context: Context) {
        try {
            NodeForegroundService.start(context.applicationContext)
        } catch (e: Exception) {
            ClawLog.w("UpdateReceiver", "start_fg_failed", e.message ?: "")
        }
        // 不在此处自动弹屏幕捕获授权框，避免更新/开机后突兀的系统弹窗。
        // 前台服务启动后节点即可自行重连。
    }
}