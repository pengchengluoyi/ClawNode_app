package com.clawnode.agent.script

import com.clawnode.agent.action.GestureController
import com.clawnode.agent.system.AppController
import com.clawnode.agent.system.ClipboardController
import com.clawnode.agent.system.ShellController
import com.clawnode.agent.system.TextInputController
import kotlinx.coroutines.runBlocking

/**
 * 暴露给 EXEC_SCRIPT（Rhino JS / DSL）的能力面。
 * shell 走 runRaw（无白名单）；JS 还可通过 context / service 直接调 Android API。
 */
class ClawScriptApi(
    private val gesture: GestureController,
    private val appController: AppController,
    private val shellController: ShellController,
    private val clipboardController: ClipboardController,
    private val textInputController: TextInputController,
    private val appContext: android.content.Context,
    private val accessibilityHost: Any?,
    private val foregroundPackage: () -> String,
    private val keyEvent: (String) -> Boolean,
    private val wakeUp: () -> Unit,
    private val onUiMutation: () -> Unit,
) {

    fun getContext(): android.content.Context = appContext

    fun getService(): Any? = accessibilityHost

    fun tap(x: Double, y: Double, durationMs: Double = 80.0): Boolean = runBlocking {
        val ok = gesture.tap(x.toFloat(), y.toFloat(), durationMs.toLong().coerceAtLeast(1L)).success
        if (ok) onUiMutation()
        ok
    }

    fun swipe(
        x1: Double, y1: Double, x2: Double, y2: Double,
        durationMs: Double = 300.0,
    ): Boolean = runBlocking {
        val ok = gesture.swipe(
            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(),
            durationMs.toLong().coerceAtLeast(1L),
        ).success
        if (ok) onUiMutation()
        ok
    }

    fun key(name: String): Boolean {
        val ok = keyEvent(name.trim())
        if (ok) onUiMutation()
        return ok
    }

    fun openApp(packageName: String, activity: String? = null): Boolean {
        val r = appController.launchApp(packageName.trim(), activity?.trim()?.ifBlank { null })
        if (r.success) onUiMutation()
        return r.success
    }

    fun openSystemSettings(): Boolean {
        val r = appController.openSystemSettings()
        if (r.success) onUiMutation()
        return r.success
    }

    fun openAppDetails(packageName: String): Boolean {
        val r = appController.openAppDetails(packageName.trim())
        if (r.success) onUiMutation()
        return r.success
    }

    fun closeApp(packageName: String): Boolean {
        val r = appController.closeApp(packageName.trim())
        if (r.success) onUiMutation()
        return r.success
    }

    fun shell(command: String): String {
        val r = shellController.runRaw(command)
        return if (r.success) {
            r.stdout.ifBlank { "ok" }
        } else {
            "FAIL:${r.stderr.ifBlank { "shell failed" }}"
        }
    }

    fun shellOk(command: String): Boolean = !shell(command).startsWith("FAIL:")

    fun setClipboard(text: String): Boolean {
        val r = clipboardController.setText(text)
        return r.success
    }

    fun inputText(text: String, x: Double? = null, y: Double? = null): Boolean {
        val r = textInputController.inputText(
            text,
            x?.toInt(),
            y?.toInt(),
        )
        if (r.success) onUiMutation()
        return r.success
    }

    fun foreground(): String = foregroundPackage().trim()

    fun wake(): Boolean {
        wakeUp()
        onUiMutation()
        return true
    }

    fun sleep(ms: Double) {
        Thread.sleep(ms.toLong().coerceAtLeast(0L))
    }

    fun log(message: String) {
        com.clawnode.agent.core.ClawLog.bp(TAG, "script_log", message.take(500))
    }

    companion object {
        private const val TAG = "ClawScriptApi"
    }
}
