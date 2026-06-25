package com.clawnode.agent.system

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
            var resolvedPackage = packageName
            var intent = buildLaunchIntent(packageName, activityClass)

            if (intent == null) {
                val lower = packageName.lowercase()
                val match = listLaunchableApps().firstOrNull {
                    it.packageName.equals(packageName, true) ||
                        it.label.lowercase().contains(lower)
                }
                if (match != null) {
                    resolvedPackage = match.packageName
                    intent = buildLaunchIntent(match.packageName, null)
                    ClawLog.bp(TAG, "open_app_fallback_by_label", "input=$packageName matched=${match.packageName}")
                }
            }

            if (intent == null) {
                return Result(false, "no launch intent for $packageName (not installed or no launcher)")
            }

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            context.startActivity(intent)
            ClawLog.bp(TAG, "open_app_intent_sent", "pkg=$resolvedPackage activity=${activityClass.orEmpty()}")

            val fg = waitForForegroundPackage(resolvedPackage, timeoutMs = 6_000L)
            ClawLog.bp(TAG, "open_app_ok", "pkg=$resolvedPackage fg=${fg.ifBlank { "?" }}")
            if (fg.isNotBlank() && fg.equals(resolvedPackage, ignoreCase = true)) {
                Result(true, fg)
            } else if (fg.isNotBlank() && !isShellPackage(fg)) {
                // startActivity 已执行，前台是别的非壳层应用（可能分包名/闪屏）
                Result(true, fg)
            } else {
                // Intent 已发出；MIUI 等可能延迟切前台，把目标包名回给服务端继续轮询
                Result(true, resolvedPackage)
            }
        } catch (e: Exception) {
            ClawLog.e(TAG, "open_app_fail", "pkg=$packageName", e)
            Result(false, "launch failed: ${e.message}")
        }
    }

    /** 轮询 [rootInActiveWindow] 直到目标包到前台或超时。 */
    private fun waitForForegroundPackage(targetPackage: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val fg = readForegroundPackage()
            if (fg.isNotBlank()) {
                last = fg
                if (fg.equals(targetPackage, ignoreCase = true)) return fg
                if (!isShellPackage(fg)) return fg
            }
            Thread.sleep(200L)
        }
        return last.ifBlank { readForegroundPackage() }
    }

    private fun readForegroundPackage(): String =
        runCatching {
            accessibilityService?.rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
        }.getOrDefault("")

    private fun isShellPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.isBlank()) return true
        if (p in SHELL_PACKAGES) return true
        return p.contains("launcher") || p.endsWith(".home")
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
        if (packageName.equals("com.android.settings", ignoreCase = true)) {
            return Intent(Settings.ACTION_SETTINGS)
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

    data class InstalledApp(val packageName: String, val label: String)

    /** 返回设备上所有有 Launcher Intent 的应用（可被 open_app 直接启动的）。 */
    fun listLaunchableApps(): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                    val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                    InstalledApp(pkg, label)
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            ClawLog.w(TAG, "list_apps_fail", e.message ?: "")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "AppController"
        private val SHELL_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.miui.home",
            "com.mi.android.launcher",
            "com.android.settings",
            "com.miui.securitycenter",
        )
    }
}
