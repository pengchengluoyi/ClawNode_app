package com.clawnode.agent.action

import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.vision.VisionManager
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.CoroutineScope
import com.clawnode.agent.log.LogUploadManager
import kotlinx.coroutines.launch

/**
 * 指令分发器。
 */
class CommandDispatcher(
    private val scope: CoroutineScope,
    private val gesture: GestureController,
    private val vision: VisionManager,
    private val ws: WsManager,
    private val onWakeUp: () -> Unit,
    private val onKeyEvent: (String) -> Boolean,
    private val onLaunchApp: (String, String?) -> Pair<Boolean, String>,
    private val onCloseApp: (String) -> Pair<Boolean, String>,
    private val onKillApp: (String) -> Pair<Boolean, String>,
    private val onClearAppCache: (String) -> Pair<Boolean, String>,
    private val onExportLogs: suspend (Int) -> Pair<Boolean, String>,
    private val onRunShell: (String) -> Triple<Boolean, String, String>,
    private val onInstallApk: suspend (String, String?) -> Pair<Boolean, String>,
    private val onSetClipboard: (String) -> Pair<Boolean, String>,
) {

    fun dispatch(cmd: Command) {
        ClawLog.bp(TAG, "dispatch", "trace=${cmd.traceId} action=${cmd.actionType}")
        scope.launch {
            try {
                handle(cmd)
            } catch (e: Throwable) {
                ClawLog.e(TAG, "dispatch_error", "trace=${cmd.traceId} action=${cmd.actionType}", e)
                ws.sendChecked(
                    NodeResponse.actionResult(cmd.traceId, false, e.message ?: "dispatch error")
                )
            }
        }
    }

    private suspend fun handle(cmd: Command) {
        when (cmd.actionType) {
            Command.TAP -> handleTap(cmd)
            Command.SWIPE -> handleSwipe(cmd)
            Command.WAKE_UP -> handleWakeUp(cmd)
            Command.GET_SCREENSHOT -> vision.captureSingleShot(cmd)
            Command.START_STREAM -> vision.startStream(cmd)
            Command.STOP_STREAM -> vision.stopStream(cmd)
            Command.GET_FOREGROUND_APP -> handleGetForegroundApp(cmd)
            Command.KEY_EVENT -> handleKeyEvent(cmd)
            Command.OPEN_APP, Command.START_APP -> handleOpenApp(cmd)
            Command.CLOSE_APP, Command.STOP_APP -> handleCloseApp(cmd)
            Command.KILL_APP -> handleKillApp(cmd)
            Command.CLEAR_APP_CACHE -> handleClearCache(cmd)
            Command.EXPORT_LOGS -> handleExportLogs(cmd)
            Command.RUN_SHELL -> handleRunShell(cmd)
            Command.INSTALL_APK -> handleInstallApk(cmd)
            Command.SET_CLIPBOARD -> handleSetClipboard(cmd)
            else -> ws.sendChecked(
                NodeResponse.actionResult(cmd.traceId, false, "unknown action_type=${cmd.actionType}")
            )
        }
    }

    private fun handleOpenApp(cmd: Command) {
        val pkg = cmd.payload?.packageName
        if (pkg.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "OPEN_APP requires package"))
            return
        }
        val (ok, msg) = runCatching { onLaunchApp(pkg, cmd.payload?.activity) }.getOrElse {
            false to (it.message ?: "launch error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, ok, msg))
    }

    private fun handleCloseApp(cmd: Command) {
        val pkg = cmd.payload?.packageName
        if (pkg.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "CLOSE_APP requires package"))
            return
        }
        val (ok, msg) = runCatching { onCloseApp(pkg) }.getOrElse {
            false to (it.message ?: "close error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, ok, msg))
    }

    private fun handleKillApp(cmd: Command) {
        val pkg = cmd.payload?.packageName
        if (pkg.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "KILL_APP requires package"))
            return
        }
        val (ok, msg) = runCatching { onKillApp(pkg) }.getOrElse { false to (it.message ?: "kill error") }
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, ok, msg))
    }

    private fun handleClearCache(cmd: Command) {
        val pkg = cmd.payload?.packageName
        if (pkg.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "CLEAR_APP_CACHE requires package"))
            return
        }
        val (ok, msg) = runCatching { onClearAppCache(pkg) }.getOrElse { false to (it.message ?: "clear error") }
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, ok, msg))
    }

    private suspend fun handleExportLogs(cmd: Command) {
        val minutes = cmd.payload?.minutes?.coerceIn(1, 24 * 60)
            ?: LogUploadManager.DEFAULT_WINDOW_MINUTES
        val (ok, msg) = try {
            onExportLogs(minutes)
        } catch (e: Throwable) {
            false to (e.message ?: "export error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, ok, msg))
    }

    private fun handleRunShell(cmd: Command) {
        val command = cmd.payload?.command
        if (command.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "RUN_SHELL requires command"))
            return
        }
        val (ok, stdout, stderr) = runCatching { onRunShell(command) }.getOrElse {
            Triple(false, "", it.message ?: "shell error")
        }
        ws.sendChecked(NodeResponse.shellResult(cmd.traceId, ok, stdout, stderr))
    }

    private suspend fun handleInstallApk(cmd: Command) {
        val url = cmd.payload?.url
        if (url.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "INSTALL_APK requires url"))
            return
        }
        val fileName = cmd.payload?.fileName?.takeIf { it.isNotBlank() }
        val (ok, msg) = try {
            onInstallApk(url, fileName)
        } catch (e: Throwable) {
            false to (e.message ?: "install error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, ok, msg))
    }

    private fun handleSetClipboard(cmd: Command) {
        val text = cmd.payload?.text
        if (text.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "SET_CLIPBOARD requires text"))
            return
        }
        val (ok, msg) = runCatching { onSetClipboard(text) }.getOrElse {
            false to (it.message ?: "set clipboard error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, ok, msg))
    }

    private fun handleKeyEvent(cmd: Command) {
        val key = cmd.payload?.keyevent
        if (key.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "KEY_EVENT requires keyevent"))
            return
        }
        val ok = runCatching { onKeyEvent(key) }.getOrDefault(false)
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, ok, if (ok) "key $key dispatched" else "key $key unsupported"))
    }

    private suspend fun handleTap(cmd: Command) {
        val p = cmd.payload
        val x = p?.x; val y = p?.y
        if (x == null || y == null) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "TAP requires x,y"))
            return
        }
        val r = gesture.tap(
            x.toFloat(), y.toFloat(),
            p.durationMs ?: GestureController.DEFAULT_TAP_MS
        )
        ClawLog.bp(TAG, "tap_done", "trace=${cmd.traceId} ok=${r.success}")
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, r.success, r.message))
    }

    private suspend fun handleSwipe(cmd: Command) {
        val p = cmd.payload
        val x1 = p?.x; val y1 = p?.y; val x2 = p?.x2; val y2 = p?.y2
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, "SWIPE requires x,y,x2,y2"))
            return
        }
        val r = gesture.swipe(
            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(),
            p.durationMs ?: GestureController.DEFAULT_SWIPE_MS
        )
        ClawLog.bp(TAG, "swipe_done", "trace=${cmd.traceId} ok=${r.success}")
        ws.sendChecked(NodeResponse.actionResult(cmd.traceId, r.success, r.message))
    }

    private fun handleWakeUp(cmd: Command) {
        runCatching { onWakeUp() }
            .onSuccess {
                ClawLog.bp(TAG, "wake_up_done", "trace=${cmd.traceId}")
                ws.sendChecked(NodeResponse.actionResult(cmd.traceId, true, "wake-up activity launched"))
            }
            .onFailure { e ->
                ClawLog.e(TAG, "wake_up_fail", "trace=${cmd.traceId}", e)
                ws.sendChecked(NodeResponse.actionResult(cmd.traceId, false, e.message ?: "wake-up failed"))
            }
    }

    private fun handleGetForegroundApp(cmd: Command) {
        val pkg = actionExecutorForegroundPackage()
        ws.sendChecked(
            NodeResponse.actionResult(
                cmd.traceId,
                pkg.isNotBlank(),
                pkg.ifBlank { "foreground package unavailable" }
            )
        )
    }

    private fun actionExecutorForegroundPackage(): String {
        return try {
            ActionExecutorService.instance?.currentForegroundPackage().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "CommandDispatcher"
    }
}
