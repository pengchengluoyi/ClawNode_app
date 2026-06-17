package com.clawnode.agent.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.clawnode.agent.action.ActionExecutorService

/**
 * 权限与系统控制模块。
 *
 * 集中处理：无障碍服务开启状态检测与跳转、通知权限引导、唤醒入口。
 * MediaProjection 授权由 [MediaProjectionRequestActivity] 按需处理，不在此预申请。
 */
object SystemController {

    /** 无障碍服务是否已开启 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ActionExecutorService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** 跳转到系统“无障碍”设置页，引导用户手动开启服务 */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 跳转到本应用的通知设置（用于推流前台通知权限） */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 主动点亮并解锁屏幕（也可由 WAKE_UP 指令触发） */
    fun wakeUpScreen(context: Context) {
        val intent = Intent(context, WakeUpActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }
}
