package com.clawnode.agent.system

import android.accessibilityservice.AccessibilityService

/**
 * 读取当前前台包名：优先无障碍窗口树，失败时用 dumpsys 兜底。
 */
object ForegroundProbe {

    fun read(accessibilityService: AccessibilityService?, shell: ShellController?): String {
        val fromA11y = runCatching {
            accessibilityService?.rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
        }.getOrDefault("")
        if (fromA11y.isNotBlank()) return fromA11y
        return readFromDumpsys(shell)
    }

    fun readFromDumpsys(shell: ShellController?): String {
        if (shell == null) return ""
        val cmds = listOf(
            "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity'",
            "dumpsys activity activities | grep mResumedActivity",
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|focusedApp'",
            "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'",
        )
        for (cmd in cmds) {
            val out = shell.runRaw(cmd).stdout
            parsePackage(out)?.let { return it }
        }
        return ""
    }

    private fun parsePackage(text: String): String? {
        if (text.isBlank()) return null
        for (line in text.lineSequence()) {
            parsePackageFromLine(line)?.let { return it }
        }
        return null
    }

    private fun parsePackageFromLine(line: String): String? {
        // u0 com.foo/.MainActivity 或 u0 com.foo/com.foo.Main
        Regex("""u\d+\s+([a-zA-Z0-9_.]+)/""").find(line)?.groupValues?.getOrNull(1)?.let {
            if (it.isNotBlank()) return it.trim()
        }
        // ActivityRecord{... com.foo/.Bar ...}
        Regex("""\s([a-zA-Z][a-zA-Z0-9_.]*)/[a-zA-Z0-9_.$]+""").find(line)?.groupValues?.getOrNull(1)?.let {
            if (it.isNotBlank() && it != "android") return it.trim()
        }
        Regex("""package=([a-zA-Z0-9_.]+)""").find(line)?.groupValues?.getOrNull(1)?.let {
            if (it.isNotBlank()) return it.trim()
        }
        return null
    }

    fun isLauncherShell(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.isBlank()) return true
        if (p in LAUNCHER_SHELL) return true
        return p.contains("launcher") || p.endsWith(".home")
    }

    fun isSettingsLike(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.isBlank()) return false
        return p.contains("settings") || p.contains("securitycenter") || p.contains("permcenter")
    }

    private val LAUNCHER_SHELL = setOf(
        "com.android.systemui",
        "android",
        "com.miui.home",
        "com.mi.android.launcher",
    )
}
