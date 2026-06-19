package com.clawnode.agent.system

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import com.clawnode.agent.action.ActionExecutorService
import com.clawnode.agent.core.ClawLog

/**
 * 权限与系统控制模块。
 */
object SystemController {

    /** Android 13+ sideload 应用需先解除「受限设置」才能开无障碍 */
    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

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

    /**
     * 受限设置是否已允许。Android 13 以下 sideload 无此限制，恒 true。
     * 若为 false，无障碍列表会显示「由受限设置控制」且无法开启。
     */
    fun isRestrictedSettingsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = appOps.checkOpNoThrow(
            OP_ACCESS_RESTRICTED_SETTINGS,
            Process.myUid(),
            context.packageName
        )
        val allowed = mode == AppOpsManager.MODE_ALLOWED
        ClawLog.bp(TAG, "restricted_check", "mode=$mode allowed=$allowed")
        return allowed
    }

    /** 打开本应用详情页（用户需在 ⋮ 菜单中点「允许受限设置」） */
    fun openAppDetailsSettings(context: Context) {
        ClawLog.bp(TAG, "open_app_details", context.packageName)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAccessibilitySettings(context: Context) {
        ClawLog.bp(TAG, "open_a11y_settings", "")
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openNotificationSettings(context: Context) {
        ClawLog.bp(TAG, "open_notif_settings", "")
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun wakeUpScreen(context: Context) {
        ClawLog.bp(TAG, "wake_up_screen", "")
        val intent = Intent(context, WakeUpActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        val ignored = pm.isIgnoringBatteryOptimizations(context.packageName)
        ClawLog.bp(TAG, "battery_check", "ignored=$ignored")
        return ignored
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        ClawLog.bp(TAG, "battery_request", "pkg=${context.packageName}")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private const val TAG = "SystemController"
}
