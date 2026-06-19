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

    /** 启动目标应用。payload.package 必填，payload.activity 可选（完整类名）。 */
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
     * 关闭目标应用（无 root）：
     * 1. GLOBAL_ACTION_HOME — 若目标在前台则切走
     * 2. killBackgroundProcesses — 清理后台进程
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

        val am = context.getSystemService(ActivityManager::class.java)
        am?.killBackgroundProcesses(packageName)
        ClawLog.bp(TAG, "close_app_kill", "pkg=$packageName killBackgroundProcesses called")

        val stillRunning = isProcessRunning(packageName)
        return if (stillRunning) {
            ClawLog.w(TAG, "close_app_partial", "pkg=$packageName still in process list")
            Result(
                success = false,
                message = "sent HOME + killBackgroundProcesses but $packageName may still be active " +
                    "(foreground force-stop requires root/device-owner)"
            )
        } else {
            Result(true, "closed $packageName")
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
