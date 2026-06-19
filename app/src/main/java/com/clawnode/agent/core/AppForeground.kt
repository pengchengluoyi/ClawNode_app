package com.clawnode.agent.core

import android.app.ActivityManager
import android.content.Context

/** 判断本应用进程是否处于前台/importance 可见档。 */
object AppForeground {

    fun isInForeground(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val procs = am.runningAppProcesses ?: return false
        val pkg = context.packageName
        for (proc in procs) {
            if (proc.processName == pkg) {
                val fg = proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                ClawLog.bp("AppForeground", "check", "importance=${proc.importance} fg=$fg")
                return fg
            }
        }
        ClawLog.w("AppForeground", "check", "process not found pkg=$pkg")
        return false
    }
}
