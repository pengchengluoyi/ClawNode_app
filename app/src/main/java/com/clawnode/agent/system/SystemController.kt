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

    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    fun isAccessibilityEnabled(context: Context): Boolean {
        return runCatching {
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
            false
        }.getOrElse { e ->
            ClawLog.e(TAG, "a11y_check_fail", e.message ?: "", e)
            false
        }
    }

    /**
     * 受限设置是否已允许。检测失败时返回 true，避免启动闪退。
     */
    fun isRestrictedSettingsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java)
                ?: return true
            val op = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                AppOpsManager.OPSTR_ACCESS_RESTRICTED_SETTINGS
            } else {
                OP_ACCESS_RESTRICTED_SETTINGS
            }
            val mode = appOps.checkOpNoThrow(op, Process.myUid(), context.packageName)
            val allowed = mode == AppOpsManager.MODE_ALLOWED
            ClawLog.bp(TAG, "restricted_check", "mode=$mode allowed=$allowed api=${Build.VERSION.SDK_INT}")
            allowed
        }.getOrElse { e ->
            // 部分 ROM（如 MIUI）对未知 op 会抛异常，导致 MainActivity 闪退
            ClawLog.e(TAG, "restricted_check_fail", "fallback=true", e)
            true
        }
    }

    fun openAppDetailsSettings(context: Context) {
        ClawLog.bp(TAG, "open_app_details", context.packageName)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAccessibilitySettings(context: Context) {
        ClawLog.bp(TAG, "open_a11y_settings", "")
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openNotificationSettings(context: Context) {
        ClawLog.bp(TAG, "open_notif_settings", "")
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun wakeUpScreen(context: Context) {
        ClawLog.bp(TAG, "wake_up_screen", "")
        context.startActivity(
            Intent(context, WakeUpActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return false
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrElse {
            ClawLog.e(TAG, "battery_check_fail", "", it)
            false
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private const val TAG = "SystemController"
}
