package com.clawnode.agent.action

import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.vision.VisionManager
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 指令分发器：消费 [WsManager.incomingCommands]，按 action_type 路由到
 * 手势 / 视觉 / 系统模块，并把结果经 WsManager 回传。
 *
 * 单一职责：只做路由与结果回传，不持有具体执行逻辑。
 */
class CommandDispatcher(
    private val scope: CoroutineScope,
    private val gesture: GestureController,
    private val vision: VisionManager,
    private val ws: WsManager,
    private val onWakeUp: () -> Unit,
    // KEY_EVENT：返回是否成功（由 service 用 performGlobalAction 实现）
    private val onKeyEvent: (String) -> Boolean,
    // STOP_APP：返回是否成功（无 root 多为 no-op）
    private val onStopApp: (String) -> Boolean
) {

    fun dispatch(cmd: Command) {
        // 每条指令独立协程，互不阻塞（手势期间也能并行处理截图）
        scope.launch { handle(cmd) }
    }

    private suspend fun handle(cmd: Command) {
        when (cmd.actionType) {
            Command.TAP -> handleTap(cmd)
            Command.SWIPE -> handleSwipe(cmd)
            Command.WAKE_UP -> handleWakeUp(cmd)
            Command.GET_SCREENSHOT -> vision.captureSingleShot(cmd)
            Command.START_STREAM -> vision.startStream(cmd)
            Command.STOP_STREAM -> vision.stopStream(cmd)
            Command.KEY_EVENT -> handleKeyEvent(cmd)
            Command.STOP_APP -> handleStopApp(cmd)
            else -> ws.send(
                NodeResponse.actionResult(cmd.traceId, false, "unknown action_type=${cmd.actionType}")
            )
        }
    }

    private fun handleKeyEvent(cmd: Command) {
        val key = cmd.payload?.keyevent
        if (key.isNullOrBlank()) {
            ws.send(NodeResponse.actionResult(cmd.traceId, false, "KEY_EVENT requires keyevent"))
            return
        }
        val ok = runCatching { onKeyEvent(key) }.getOrDefault(false)
        ws.send(NodeResponse.actionResult(cmd.traceId, ok, if (ok) "key $key dispatched" else "key $key unsupported"))
    }

    private fun handleStopApp(cmd: Command) {
        val pkg = cmd.payload?.packageName.orEmpty()
        val ok = runCatching { onStopApp(pkg) }.getOrDefault(false)
        ws.send(NodeResponse.actionResult(cmd.traceId, ok, if (ok) "stop_app $pkg" else "stop_app unsupported (no root)"))
    }

    private suspend fun handleTap(cmd: Command) {
        val p = cmd.payload
        val x = p?.x; val y = p?.y
        if (x == null || y == null) {
            ws.send(NodeResponse.actionResult(cmd.traceId, false, "TAP requires x,y"))
            return
        }
        val r = gesture.tap(
            x.toFloat(), y.toFloat(),
            p.durationMs ?: GestureController.DEFAULT_TAP_MS
        )
        ws.send(NodeResponse.actionResult(cmd.traceId, r.success, r.message))
    }

    private suspend fun handleSwipe(cmd: Command) {
        val p = cmd.payload
        val x1 = p?.x; val y1 = p?.y; val x2 = p?.x2; val y2 = p?.y2
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            ws.send(NodeResponse.actionResult(cmd.traceId, false, "SWIPE requires x,y,x2,y2"))
            return
        }
        val r = gesture.swipe(
            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(),
            p.durationMs ?: GestureController.DEFAULT_SWIPE_MS
        )
        ws.send(NodeResponse.actionResult(cmd.traceId, r.success, r.message))
    }

    private fun handleWakeUp(cmd: Command) {
        runCatching { onWakeUp() }
            .onSuccess { ws.send(NodeResponse.actionResult(cmd.traceId, true, "wake-up activity launched")) }
            .onFailure { ws.send(NodeResponse.actionResult(cmd.traceId, false, it.message ?: "wake-up failed")) }
    }
}
