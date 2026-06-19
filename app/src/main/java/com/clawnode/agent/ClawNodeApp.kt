package com.clawnode.agent

import android.app.Application
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.CrashHandler
import com.clawnode.agent.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClawNodeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        ClawLog.init(this)
        CrashHandler.install(this)
        ClawLog.bp("ClawNodeApp", "onCreate", "process start pid=${android.os.Process.myPid()}")

        // 延迟 5s 后台检查 GitHub 更新（需已授权「安装未知应用」）
        appScope.launch {
            delay(5_000)
            runCatching { AppUpdateManager.autoUpdateIfNeeded(this@ClawNodeApp) }
        }
    }

    companion object {
        lateinit var instance: ClawNodeApp
            private set
    }
}
