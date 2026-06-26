package com.clawnode.agent.action

import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.vision.VisionManager
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.CoroutineScope
import com.clawnode.agent.log.LogUploadManager
import com.clawnode.agent.script.ScriptRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.clawnode.agent.system.AppController.InstalledApp as AppInfo

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
    private val onInstallApk: suspend (traceId: String, url: String, fileName: String?) -> Pair<Boolean, String>,
    private val onSetClipboard: (String) -> Pair<Boolean, String>,
    private val onInputText: (String, Int?, Int?) -> Pair<Boolean, String>,
    private val onExecScript: (String, String, Long) -> Triple<Boolean, String, String>,
    private val onGetInstalledApps: () -> List<com.clawnode.agent.system.AppController.InstalledApp>,
) {

    fun dispatch(cmd: Command) {
        ClawLog.bp(TAG, "dispatch", "trace=${cmd.safeTraceId} command=${cmd.command}")
        scope.launch {
            try {
                handle(cmd)
            } catch (e: Throwable) {
                ClawLog.e(TAG, "dispatch_error", "trace=${cmd.safeTraceId} command=${cmd.command}", e)
                ws.sendChecked(
                    NodeResponse.actionResult(cmd.safeTraceId, false, e.message ?: "dispatch error")
                )
            }
        }
    }

    private suspend fun handle(cmd: Command) {
        // 与 server 统一：优先用 command；如果是 "control" 包装，则用 params.action 作为子动作
        val raw = (cmd.command ?: "").trim()
        val sub = cmd.params?.action?.trim()?.uppercase()
        val key = when {
            raw.equals("control", ignoreCase = true) && !sub.isNullOrBlank() -> sub
            else -> raw.uppercase()
        }

        when (key) {
            Command.TAP, "TAP", "CLICK" -> handleTap(cmd)
            Command.SWIPE, "SWIPE" -> handleSwipe(cmd)
            Command.WAKE_UP, "WAKE_UP", "WAKE" -> handleWakeUp(cmd)
            Command.GET_SCREENSHOT, "GET_SCREENSHOT", "SCREENSHOT" -> vision.captureSingleShot(cmd)
            Command.START_STREAM, "START_STREAM" -> vision.startStream(cmd)
            Command.STOP_STREAM, "STOP_STREAM" -> vision.stopStream(cmd)
            Command.GET_FOREGROUND_APP, "GET_FOREGROUND_APP", "FOREGROUND_APP" -> handleGetForegroundApp(cmd)
            Command.KEY_EVENT, "KEY_EVENT", "KEY", "KEYEVENT" -> handleKeyEvent(cmd)
            Command.OPEN_APP, Command.START_APP, "OPEN_APP", "START_APP", "LAUNCH_APP" -> handleOpenApp(cmd)
            Command.CLOSE_APP, Command.STOP_APP, "CLOSE_APP", "STOP_APP" -> handleCloseApp(cmd)
            Command.KILL_APP, "KILL_APP" -> handleKillApp(cmd)
            Command.CLEAR_APP_CACHE, "CLEAR_APP_CACHE", "CLEAR_CACHE" -> handleClearCache(cmd)
            Command.EXPORT_LOGS, "EXPORT_LOGS", "EXPORTLOG", "UPLOAD_LOGS" -> handleExportLogs(cmd)
            Command.RUN_SHELL, "RUN_SHELL", "SHELL" -> handleRunShell(cmd)
            Command.INSTALL_APK, "INSTALL_APK", "INSTALLAPK" -> handleInstallApk(cmd)
            Command.SET_CLIPBOARD, "SET_CLIPBOARD", "CLIPBOARD" -> handleSetClipboard(cmd)
            Command.INPUT_TEXT, "INPUT_TEXT" -> handleInputText(cmd)
            Command.EXEC_SCRIPT, "EXEC_SCRIPT", "RUN_SCRIPT", "EXEC_CODE" -> handleExecScript(cmd)
            Command.GET_INSTALLED_APPS, "GET_INSTALLED_APPS" -> handleGetInstalledApps(cmd)
            else -> ws.sendChecked(
                NodeResponse.actionResult(cmd.safeTraceId, false, "unknown command=${cmd.command} (effective=$key)")
            )
        }
    }

    private fun handleOpenApp(cmd: Command) {
        val pkg = cmd.params?.packageName
        if (pkg.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "OPEN_APP requires package"))
            return
        }
        // 不在此处单独唤起 WakeUpActivity：AppController 启动链路里的跳板 Activity
        // 已负责点亮+解锁+启动，重复唤起只会让 ClawNode 先于目标 app 出现在前台。
        val (ok, msg) = runCatching { onLaunchApp(pkg, cmd.params?.activity) }.getOrElse {
            false to (it.message ?: "launch error")
        }
        val fg = actionExecutorForegroundPackage()
        ClawLog.bp(TAG, "open_app_result", "trace=${cmd.safeTraceId} pkg=$pkg ok=$ok fg=$fg")
        if (ok) vision.invalidateScreenshotCache()
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, msg))
    }

    private fun handleCloseApp(cmd: Command) {
        val pkg = cmd.params?.packageName
        if (pkg.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "CLOSE_APP requires package"))
            return
        }
        val (ok, msg) = runCatching { onCloseApp(pkg) }.getOrElse {
            false to (it.message ?: "close error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, msg))
    }

    private fun handleKillApp(cmd: Command) {
        val pkg = cmd.params?.packageName
        if (pkg.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "KILL_APP requires package"))
            return
        }
        val (ok, msg) = runCatching { onKillApp(pkg) }.getOrElse { false to (it.message ?: "kill error") }
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, msg))
    }

    private fun handleClearCache(cmd: Command) {
        val pkg = cmd.params?.packageName
        if (pkg.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "CLEAR_APP_CACHE requires package"))
            return
        }
        val (ok, msg) = runCatching { onClearAppCache(pkg) }.getOrElse { false to (it.message ?: "clear error") }
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, msg))
    }

    private suspend fun handleExportLogs(cmd: Command) {
        val minutes = cmd.params?.minutes?.coerceIn(1, 24 * 60)
            ?: LogUploadManager.DEFAULT_WINDOW_MINUTES
        val (ok, msg) = try {
            onExportLogs(minutes)
        } catch (e: Throwable) {
            false to (e.message ?: "export error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, msg))
    }

    private fun handleRunShell(cmd: Command) {
        val command = cmd.params?.command
        if (command.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "RUN_SHELL requires command"))
            return
        }
        val (ok, stdout, stderr) = runCatching { onRunShell(command) }.getOrElse {
            Triple(false, "", it.message ?: "shell error")
        }
        ws.sendChecked(NodeResponse.shellResult(cmd.safeTraceId, ok, stdout, stderr))
    }

    private suspend fun handleInstallApk(cmd: Command) {
        val url = cmd.params?.url
        if (url.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "INSTALL_APK requires url"))
            return
        }
        val fileName = cmd.params?.fileName?.takeIf { it.isNotBlank() }
        // Report initial stage for progress bar
        ws.sendChecked(NodeResponse.installProgress(cmd.safeTraceId, NodeResponse.STAGE_DETECTED, message = "server push received"))
        val (ok, msg) = try {
            onInstallApk(cmd.safeTraceId, url, fileName)
        } catch (e: Throwable) {
            ws.sendChecked(NodeResponse.installProgress(cmd.safeTraceId, NodeResponse.STAGE_FAILED, message = e.message))
            false to (e.message ?: "install error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, msg))
    }

    private suspend fun handleInputText(cmd: Command) {
        val text = cmd.params?.text
        if (text.isNullOrEmpty()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "INPUT_TEXT requires text"))
            return
        }
        // 锁屏（带 PIN/图案/密码）是系统安全窗口，无障碍无法读取内容或注入文本——
        // 这是 Android 安全限制，任何第三方都绕不过。这里先尝试唤醒解锁，给系统留出时间；
        // 若设备设了密码锁屏、无人解锁，则只能点亮但无法真正进入，输入仍会失败并回明确提示。
        if (isKeyguardLocked()) {
            ClawLog.bp(TAG, "input_text_locked", "trace=${cmd.safeTraceId} try wake+unlock first")
            runCatching { onWakeUp() }
            kotlinx.coroutines.delay(1500)
            if (isKeyguardLocked()) {
                ws.sendChecked(
                    NodeResponse.actionResult(
                        cmd.safeTraceId,
                        false,
                        "device is locked; INPUT_TEXT cannot work on the secure keyguard. Unlock the device (remove PIN or unlock first) then retry.",
                    )
                )
                return
            }
        }
        // ⚠️ 解锁后不再唤起 WakeUpActivity：它是 ClawNode 的透明窗，会盖到目标 app 上，
        // 导致无障碍读到的 rootInActiveWindow 变成 ClawNode 自己、找不到输入框。
        // 输入直接作用于当前前台 app —— 调用方需先用 OPEN_APP 打开带输入框的界面、必要时先 TAP 聚焦。
        val x = cmd.params?.x
        val y = cmd.params?.y
        if (x != null && y != null) {
            gesture.tap(x.toFloat(), y.toFloat(), cmd.params?.durationMs ?: GestureController.DEFAULT_TAP_MS)
            kotlinx.coroutines.delay(350)
        }
        val (ok, msg) = runCatching { onInputText(text, x, y) }.getOrElse {
            false to (it.message ?: "input text error")
        }
        ClawLog.bp(TAG, "input_text_done", "trace=${cmd.safeTraceId} ok=$ok len=${text.length}")
        if (ok) vision.invalidateScreenshotCache()
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, msg))
    }

    private fun isKeyguardLocked(): Boolean {
        return try {
            val km = ActionExecutorService.instance
                ?.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            km?.isKeyguardLocked == true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun handleExecScript(cmd: Command) {
        val script = cmd.params?.script
        if (script.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "EXEC_SCRIPT requires script"))
            return
        }
        val language = cmd.params?.language.orEmpty()
        val timeout = cmd.params?.scriptTimeoutMs ?: ScriptRuntime.DEFAULT_TIMEOUT_MS
        val (ok, msg, output) = withContext(Dispatchers.IO) {
            runCatching { onExecScript(script, language, timeout) }.getOrElse {
                Triple(false, it.message ?: "exec script error", "")
            }
        }
        ClawLog.bp(
            TAG, "exec_script_done",
            "trace=${cmd.safeTraceId} ok=$ok lang=${language.ifBlank { "dsl" }} outLen=${output.length}",
        )
        if (ok) vision.invalidateScreenshotCache()
        ws.sendChecked(NodeResponse.shellResult(cmd.safeTraceId, ok, output.ifBlank { msg }, if (ok) "" else msg))
    }

    private fun handleSetClipboard(cmd: Command) {
        val text = cmd.params?.text
        if (text.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "SET_CLIPBOARD requires text"))
            return
        }
        val (ok, msg) = runCatching { onSetClipboard(text) }.getOrElse {
            false to (it.message ?: "set clipboard error")
        }
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, msg))
    }

    private fun handleKeyEvent(cmd: Command) {
        val key = cmd.params?.keyevent
        if (key.isNullOrBlank()) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "KEY_EVENT requires keyevent"))
            return
        }
        val ok = runCatching { onKeyEvent(key) }.getOrDefault(false)
        if (ok) vision.invalidateScreenshotCache()
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, ok, if (ok) "key $key dispatched" else "key $key unsupported"))
    }

    private suspend fun handleTap(cmd: Command) {
        val p = cmd.params
        val x = p?.x; val y = p?.y
        if (x == null || y == null) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "TAP requires x,y"))
            return
        }
        val r = gesture.tap(
            x.toFloat(), y.toFloat(),
            p.durationMs ?: GestureController.DEFAULT_TAP_MS
        )
        ClawLog.bp(TAG, "tap_done", "trace=${cmd.safeTraceId} ok=${r.success}")
        if (r.success) vision.invalidateScreenshotCache()
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, r.success, r.message))
    }

    private suspend fun handleSwipe(cmd: Command) {
        val p = cmd.params
        val x1 = p?.x; val y1 = p?.y; val x2 = p?.x2; val y2 = p?.y2
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, "SWIPE requires x,y,x2,y2"))
            return
        }
        val r = gesture.swipe(
            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(),
            p.durationMs ?: GestureController.DEFAULT_SWIPE_MS
        )
        ClawLog.bp(TAG, "swipe_done", "trace=${cmd.safeTraceId} ok=${r.success}")
        if (r.success) vision.invalidateScreenshotCache()
        ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, r.success, r.message))
    }

    private fun handleWakeUp(cmd: Command) {
        runCatching { onWakeUp() }
            .onSuccess {
                ClawLog.bp(TAG, "wake_up_done", "trace=${cmd.safeTraceId}")
                ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, true, "wake-up activity launched"))
            }
            .onFailure { e ->
                ClawLog.e(TAG, "wake_up_fail", "trace=${cmd.safeTraceId}", e)
                ws.sendChecked(NodeResponse.actionResult(cmd.safeTraceId, false, e.message ?: "wake-up failed"))
            }
    }

    private fun handleGetForegroundApp(cmd: Command) {
        val pkg = actionExecutorForegroundPackage()
        ClawLog.bp(TAG, "get_foreground", "trace=${cmd.safeTraceId} pkg=${pkg.ifBlank { "?" }}")
        ws.sendChecked(
            NodeResponse.actionResult(
                cmd.safeTraceId,
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

    private fun handleGetInstalledApps(cmd: Command) {
        val apps = try {
            onGetInstalledApps()
        } catch (e: Throwable) {
            emptyList()
        }
        // Send structured result
        ws.sendChecked(NodeResponse.installedApps(cmd.safeTraceId, apps.map {
            NodeResponse.InstalledApp(it.packageName, it.label)
        }))
    }

    companion object {
        private const val TAG = "CommandDispatcher"
    }
}
