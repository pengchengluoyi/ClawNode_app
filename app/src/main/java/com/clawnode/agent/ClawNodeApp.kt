package com.clawnode.agent

import android.app.Application
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.CrashHandler
import com.clawnode.agent.service.NodeForegroundService
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

        // 进程一旦启动（更新拉起 / 开机 / 系统唤醒 / 用户手动打开）就拉起常驻前台服务，
        // 不再单纯依赖 MY_PACKAGE_REPLACED 广播（HyperOS 上进程被冻结时可能收不到，
        // 导致更新后节点不上线、需手动打开 App）。无障碍服务连上后会再次幂等启动，无副作用。
        runCatching { NodeForegroundService.start(this) }
            .onFailure { ClawLog.w("ClawNodeApp", "start_fg_failed", it.message ?: "") }

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
