package com.clawnode.agent.core

import android.content.Context
import com.clawnode.agent.BuildConfig
import kotlin.system.exitProcess

/** 捕获未处理异常并写入本地日志，避免闪退后无迹可寻。 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private lateinit var appContext: Context
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        ClawLog.e(
            "CrashHandler", "uncaught",
            "thread=${thread.name} v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
            throwable
        )
        defaultHandler?.uncaughtException(thread, throwable) ?: exitProcess(1)
    }
}
