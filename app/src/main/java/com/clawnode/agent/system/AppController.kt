package com.clawnode.agent.system

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.clawnode.agent.core.ClawLog

/**
 * 应用启动 / 关闭控制器。
 */
class AppController(
    private val context: Context,
    private val accessibilityService: AccessibilityService? = null,
    private val shellController: ShellController? = null,
) {

    data class Result(val success: Boolean, val message: String)

    /** 启动前点亮屏幕（由 ActionExecutorService 注入）。 */
    var onWakeUp: (() -> Unit)? = null

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

            prepareWake()
            val launchIntent = Intent(intent).apply { addLaunchFlags() }

            var fg = tryLaunchSequence(resolvedPackage, launchIntent, activityClass)
            ClawLog.bp(TAG, "open_app_result", "pkg=$resolvedPackage fg=${fg.ifBlank { "?" }}")
            if (isLaunchSuccess(resolvedPackage, fg)) {
                Result(true, fg)
            } else {
                Result(
                    false,
                    "launch failed: foreground=${fg.ifBlank { "unknown" }} (MIUI may block background start; try wake + unlock first)",
                )
            }
        } catch (e: Exception) {
            ClawLog.e(TAG, "open_app_fail", "pkg=$packageName", e)
            Result(false, "launch failed: ${e.message}")
        }
    }

    fun openAppDetails(packageName: String): Result {
        if (packageName.isBlank()) return Result(false, "package required")
        if (!isPackageInstalled(packageName)) {
            return Result(false, "package not installed: $packageName")
        }
        return try {
            prepareWake()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addLaunchFlags()
            }
            var fg = tryLaunchSequence("com.android.settings", intent, null)
            ClawLog.bp(TAG, "open_app_details", "pkg=$packageName fg=${fg.ifBlank { "?" }}")
            val ok = isLaunchSuccess("com.android.settings", fg)
            Result(ok, fg.ifBlank { "foreground unavailable after open details" })
        } catch (e: Exception) {
            ClawLog.e(TAG, "open_app_details_fail", "pkg=$packageName", e)
            Result(false, e.message ?: "open app details failed")
        }
    }

    private fun prepareWake() {
        onWakeUp?.invoke()
        Thread.sleep(650L)
    }

    /** 与 EXEC_SCRIPT JS 的 context.startActivity 相同路径，再跳板 / shell 兜底。 */
    private fun tryLaunchSequence(
        targetPackage: String,
        launchIntent: Intent,
        activityClass: String?,
    ): String {
        startViaAppContext(launchIntent, "primary")
        var fg = waitForForegroundPackage(targetPackage, timeoutMs = 4_500L)
        if (isLaunchSuccess(targetPackage, fg)) return fg

        accessibilityService?.let { svc ->
            runCatching {
                svc.startActivity(Intent(launchIntent).apply { addLaunchFlags() })
                ClawLog.bp(TAG, "open_app_a11y", "pkg=$targetPackage")
            }.onFailure { e ->
                ClawLog.w(TAG, "open_app_a11y_fail", e.message ?: "")
            }
            fg = waitForForegroundPackage(targetPackage, timeoutMs = 3_500L)
            if (isLaunchSuccess(targetPackage, fg)) return fg
        }

        startViaTrampoline(launchIntent)
        fg = waitForForegroundPackage(targetPackage, timeoutMs = 4_500L)
        if (isLaunchSuccess(targetPackage, fg)) return fg

        tryShellLaunch(targetPackage, activityClass)
        return waitForForegroundPackage(targetPackage, timeoutMs = 5_000L)
    }

    private fun startViaAppContext(launchIntent: Intent, stage: String) {
        runCatching {
            context.startActivity(Intent(launchIntent).apply { addLaunchFlags() })
            ClawLog.bp(TAG, "open_app_ctx", "stage=$stage action=${launchIntent.action}")
        }.onFailure { e ->
            ClawLog.w(TAG, "open_app_ctx_fail", "stage=$stage ${e.message}")
        }
    }

    private fun startViaTrampoline(launchIntent: Intent) {
        runCatching {
            context.startActivity(LaunchTrampolineActivity.build(context, launchIntent))
            ClawLog.bp(TAG, "open_app_trampoline", "action=${launchIntent.action}")
        }.onFailure { e ->
            ClawLog.w(TAG, "open_app_trampoline_fail", e.message ?: "")
        }
    }

    private fun Intent.addLaunchFlags() {
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }

    private fun waitForForegroundPackage(targetPackage: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val fg = readForegroundPackage()
            if (fg.isNotBlank()) {
                last = fg
                if (matchesTarget(targetPackage, fg)) return fg
                if (!ForegroundProbe.isLauncherShell(fg)) return fg
            }
            Thread.sleep(250L)
        }
        return last.ifBlank { readForegroundPackage() }
    }

    private fun matchesTarget(targetPackage: String, fg: String): Boolean {
        if (fg.equals(targetPackage, ignoreCase = true)) return true
        if (targetPackage.equals("com.android.settings", ignoreCase = true) &&
            ForegroundProbe.isSettingsLike(fg)
        ) {
            return true
        }
        return false
    }

    private fun isLaunchSuccess(targetPackage: String, fg: String): Boolean {
        if (fg.isBlank()) return false
        if (matchesTarget(targetPackage, fg)) return true
        return !ForegroundProbe.isLauncherShell(fg)
    }

    private fun tryShellLaunch(packageName: String, activityClass: String?): Boolean {
        val shell = shellController ?: return false
        for (cmd in buildShellLaunchCommands(packageName, activityClass)) {
            val r = shell.runRaw(cmd)
            ClawLog.bp(
                TAG,
                "open_app_shell_fallback",
                "cmd=$cmd ok=${r.success} err=${r.stderr.take(100)}",
            )
            if (r.success) return true
        }
        return false
    }

    private fun buildShellLaunchCommands(packageName: String, activityClass: String?): List<String> {
        val cmds = mutableListOf<String>()
        when {
            packageName.equals("com.android.settings", ignoreCase = true) -> {
                cmds += "am start -a android.settings.SETTINGS"
                cmds += "cmd activity start-activity -a android.settings.SETTINGS"
            }
            else -> {
                val comp = resolveComponent(packageName, activityClass) ?: return listOf(
                    "monkey -p $packageName -c android.intent.category.LAUNCHER 1",
                )
                val flat = "${comp.first}/${comp.second}"
                cmds += "am start -n $flat"
                cmds += "cmd activity start-activity -n $flat"
                cmds += "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
            }
        }
        return cmds
    }

    private fun resolveComponent(packageName: String, activityClass: String?): Pair<String, String>? {
        if (!activityClass.isNullOrBlank()) return packageName to activityClass
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        val comp = launch.component ?: return null
        return comp.packageName to comp.className
    }

    private fun readForegroundPackage(): String =
        ForegroundProbe.read(accessibilityService, shellController)

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
        return openAppDetails(packageName)
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

    data class InstalledApp(val packageName: String, val label: String)

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
    }
}
