package com.clawnode.agent.core

import android.content.Context
import android.os.Build
import com.clawnode.agent.BuildConfig
import com.clawnode.agent.action.ActionExecutorService
import com.clawnode.agent.discovery.NodeBeacon
import com.clawnode.agent.discovery.ServerDiscovery
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.pairing.PairingBridge
import com.clawnode.agent.pairing.PairingHttpServer
import com.clawnode.agent.update.AppUpdateManager
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 节点运行时核心：持有 [WsManager] 并驱动网关连接 / 注册 / 心跳。
 *
 * ⚠️ 关键：连接所有权放在这里（由常驻前台服务 NodeForegroundService 启动），
 * 而非无障碍服务。原先 WsManager 建在 ActionExecutorService.onServiceConnected 里，
 * 一旦无障碍被系统/用户关闭或更新后未恢复，网关连接随之消失、节点显示离线——
 * 但前台服务仍在跑 mDNS，所以能被发现、能上传日志，形成「能传日志却离线」的矛盾。
 *
 * 现在：连接 / 注册 / 心跳只依赖 applicationContext + ConfigManager，与无障碍无关，
 * 节点恒定在线；无障碍服务存活时通过 [attachExecutor] 挂接指令执行能力，
 * 不存活时需要无障碍的指令会明确返回失败（但节点仍在线、可被远程感知）。
 */
object NodeRuntime {

    private const val TAG = "NodeRuntime"

    private var scope: CoroutineScope? = null

    @Volatile
    var wsManager: WsManager? = null
        private set

    /** 指令执行器（由无障碍服务注入）；为 null 表示无障碍未运行，相关指令无法执行。 */
    @Volatile
    private var commandHandler: ((Command) -> Unit)? = null

    /** 幂等启动：进程内首次调用建立 WsManager 与连接驱动，后续调用直接返回。 */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (wsManager != null) return
        val appCtx = context.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s

        val configManager = ConfigManager.get(appCtx)
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Node" }

        val ws = WsManager(
            scope = s,
            discoverServer = { pairedGatewayId ->
                val gateways = ServerDiscovery.findGateways(appCtx)
                val gateway = ServerDiscovery.matchPaired(gateways, pairedGatewayId)
                    ?: gateways.firstOrNull()
                    ?: return@WsManager null
                val settings = configManager.settings.first()
                ServerDiscovery.resolveReachableWsUrl(gateway, settings.authToken, settings.nodeSn)
            },
            onPairConfig = { wsUrl, authToken, gatewayId ->
                configManager.savePairing(wsUrl, authToken, gatewayId)
            },
            onUrlDiscovered = { wsUrl ->
                configManager.saveDiscoveredUrl(wsUrl)
            },
            onUnpair = {
                configManager.clearPairing()
                NodeBeacon.start(appCtx, sn = configManager.defaultNodeSn, model = model, paired = false)
                PairingHttpServer.start()
            },
        )
        ws.setDeviceMeta(buildDeviceMeta(appCtx, model))
        PairingBridge.onPairPush = { payload ->
            ws.applyPairConfigPush(payload.wsUrl, payload.authToken, payload.gatewayId)
        }
        AppUpdateManager.setSelfProgressReporter { stage, percent, message, version ->
            ws.sendChecked(
                NodeResponse.installProgress(
                    traceId = "self-update",
                    stage = stage,
                    percent = percent,
                    message = message,
                    version = version,
                )
            )
        }

        wsManager = ws

        // 指令分发：无障碍在线时交给其 dispatcher；否则回明确失败，但连接/心跳不受影响。
        ws.incomingCommands
            .onEach { cmd ->
                val handler = commandHandler
                if (handler != null) {
                    handler(cmd)
                } else {
                    ClawLog.w(TAG, "no_executor", "trace=${cmd.safeTraceId} command=${cmd.command} (accessibility off)")
                    ws.sendChecked(
                        NodeResponse.actionResult(
                            cmd.safeTraceId,
                            false,
                            "node online but accessibility service not running; enable it to execute commands",
                        )
                    )
                }
            }
            .launchIn(s)

        // 配置变化驱动连接（含首次连接）。
        configManager.settings
            .onEach { ws.applySettings(it) }
            .launchIn(s)

        ClawLog.bp(TAG, "started", "wsManager created, connection owned by foreground service")
    }

    /** 无障碍服务连上后挂接指令执行器。 */
    fun attachExecutor(handler: (Command) -> Unit) {
        commandHandler = handler
        ClawLog.bp(TAG, "executor_attached", "accessibility command dispatch ready")
    }

    fun detachExecutor() {
        commandHandler = null
        ClawLog.bp(TAG, "executor_detached", "accessibility command dispatch gone")
    }

    /** 看门狗 / 亮屏 / 网络变化触发的重连入口。 */
    fun reconnectIfNeeded() {
        wsManager?.reconnectIfNeeded()
    }

    private fun buildDeviceMeta(context: Context, model: String): WsManager.DeviceMeta {
        val osVersion = "Android ${Build.VERSION.RELEASE}"
        val dm = context.resources.displayMetrics
        val resolution = "${dm.widthPixels}x${dm.heightPixels}"
        return WsManager.DeviceMeta(
            model = model,
            osVersion = osVersion,
            resolution = resolution,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}
