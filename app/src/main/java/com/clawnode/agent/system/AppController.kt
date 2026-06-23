package com.clawnode.agent.system

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.clawnode.agent.core.ClawLog

/**
 * 应用启动 / 关闭控制器。
 *
 * OPEN_APP：通过 Launcher Intent 或显式 Activity 启动目标应用。
 * CLOSE_APP / STOP_APP：先 HOME 退出前台，再 killBackgroundProcesses（无 root 下的最佳实践）。
 */
class AppController(
    private val context: Context,
    private val accessibilityService: AccessibilityService? = null
) {

    data class Result(val success: Boolean, val message: String)

    /** 启动目标应用。params.package 必填，params.activity 可选（完整类名）。 */
    fun launchApp(packageName: String, activityClass: String? = null): Result {
        if (packageName.isBlank()) {
            return Result(false, "OPEN_APP requires package")
        }

        return try {
            val intent = buildLaunchIntent(packageName, activityClass)
                ?: return Result(false, "no launch intent for $packageName (not installed or no launcher)")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ClawLog.bp(TAG, "open_app_ok", "pkg=$packageName activity=${activityClass.orEmpty()}")
            Result(true, "launched $packageName")
        } catch (e: Exception) {
            ClawLog.e(TAG, "open_app_fail", "pkg=$packageName", e)
            Result(false, "launch failed: ${e.message}")
        }
    }

    /**
     * 切到后台（不杀进程）：仅按 Home。
     */
    fun closeApp(packageName: String): Result {
        if (packageName.isBlank()) {
            return Result(false, "CLOSE_APP requires package")
        }
        if (!isPackageInstalled(packageName)) {
            return Result(false, "package not installed: $packageName")
        }
        val homeOk = accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
        ClawLog.bp(TAG, "close_app_home", "pkg=$packageName home=$homeOk")
        return Result(homeOk, if (homeOk) "moved $packageName to background" else "HOME action failed")
    }

    /**
     * 从最近任务清除：打开 Recents 后按 Home（完整卡片滑动需 OEM 手势，此处尽力而为）。
     */
    fun killApp(packageName: String): Result {
        if (packageName.isBlank()) return Result(false, "KILL_APP requires package")
        closeApp(packageName)
        val recentsOk = accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
        ClawLog.bp(TAG, "kill_app_recents", "pkg=$packageName recents=$recentsOk")
        accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        val am = context.getSystemService(ActivityManager::class.java)
        am?.killBackgroundProcesses(packageName)
        return Result(true, "recents+killBackgroundProcesses for $packageName")
    }

    fun clearAppCache(packageName: String): Result {
        if (packageName.isBlank()) return Result(false, "CLEAR_APP_CACHE requires package")
        killApp(packageName)
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result(true, "opened app settings for $packageName (manual clear cache if needed)")
        } catch (e: Exception) {
            Result(false, e.message ?: "clear cache failed")
        }
    }

    private fun buildLaunchIntent(packageName: String, activityClass: String?): Intent? {
        if (!activityClass.isNullOrBlank()) {
            return Intent().setClassName(packageName, activityClass)
        }
        return context.packageManager.getLaunchIntentForPackage(packageName)
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun isProcessRunning(packageName: String): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return am.runningAppProcesses?.any { it.processName == packageName } == true
    }

    companion object {
        private const val TAG = "AppController"
    }
}
