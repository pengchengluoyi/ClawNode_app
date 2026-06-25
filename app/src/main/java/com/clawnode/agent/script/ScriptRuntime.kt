package com.clawnode.agent.script

import android.content.Context
import com.clawnode.agent.core.ClawLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 执行 Server 下发的脚本（DSL JSON 或 JavaScript）。
 * JS 模式开放 Rhino + Java 互操作（importClass / Packages），无沙箱。
 */
class ScriptRuntime(
    private val api: ClawScriptApi,
    private val appContext: Context,
    private val hostService: Any?,
) {

    data class Result(val success: Boolean, val message: String, val output: String = "")

    private val gson = Gson()
    private val jsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "claw-script-js").apply { isDaemon = true }
    }

    fun execute(script: String, language: String, timeoutMs: Long): Result {
        val lang = language.trim().lowercase().ifBlank { LANG_DSL }
        val source = script.trim()
        if (source.isEmpty()) {
            return Result(false, "EXEC_SCRIPT requires non-empty script")
        }
        if (source.length > MAX_SCRIPT_CHARS) {
            return Result(false, "script too large (max $MAX_SCRIPT_CHARS chars)")
        }
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        return try {
            when (lang) {
                LANG_JS, "javascript" -> executeJs(source, deadline)
                LANG_DSL, "json" -> executeDsl(source, deadline)
                else -> Result(false, "unsupported language=$language (use dsl|js)")
            }
        } catch (t: Throwable) {
            ClawLog.e(TAG, "script_fail", "lang=$lang", t)
            Result(false, t.message ?: "script execution failed")
        }
    }

    private fun executeJs(source: String, deadlineMs: Long): Result {
        val remainingMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(500L)
        val future = jsExecutor.submit<Result> {
            val ctx = RhinoContext.enter()
            try {
                ctx.optimizationLevel = -1
                ctx.languageVersion = RhinoContext.VERSION_ES6
                val scope = ImporterTopLevel(ctx)
                ctx.initStandardObjects(scope, true)
                ScriptableObject.putProperty(scope, "claw", RhinoContext.javaToJS(api, scope))
                ScriptableObject.putProperty(scope, "context", RhinoContext.javaToJS(appContext, scope))
                hostService?.let {
                    ScriptableObject.putProperty(scope, "service", RhinoContext.javaToJS(it, scope))
                }
                val value = ctx.evaluateString(scope, source, "claw-exec.js", 1, null)
                val out = value?.let { RhinoContext.toString(it) }?.trim().orEmpty()
                Result(true, "js ok", out)
            } catch (e: Exception) {
                Result(false, "js error: ${e.message}", "")
            } finally {
                RhinoContext.exit()
            }
        }
        return try {
            future.get(remainingMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            Result(false, "js timeout (${remainingMs}ms)")
        } catch (e: Exception) {
            Result(false, "js error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun executeDsl(source: String, deadlineMs: Long): Result {
        val root = gson.fromJson(source, JsonElement::class.java)
        val steps: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("steps") -> root.asJsonObject.getAsJsonArray("steps")
            else -> return Result(false, "dsl must be a JSON array or {\"steps\":[...]}")
        }
        val logs = StringBuilder()
        for (i in 0 until steps.size()) {
            if (System.currentTimeMillis() > deadlineMs) {
                return Result(false, "dsl timeout at step $i", logs.toString())
            }
            val step = steps[i].asJsonObject
            val op = step.get("op")?.asString?.lowercase().orEmpty()
            if (op.isBlank()) return Result(false, "dsl step $i missing op")
            val ok = runStep(op, step)
            logs.appendLine("step$i $op -> $ok")
            if (!ok) return Result(false, "dsl step $i op=$op failed", logs.toString())
        }
        return Result(true, "dsl ok (${steps.size()} steps)", logs.toString().trim())
    }

    private fun runStep(op: String, step: JsonObject): Boolean = when (op) {
        "tap", "click" -> api.tap(
            num(step, "x"), num(step, "y"),
            num(step, "duration_ms", 80.0),
        )
        "swipe" -> api.swipe(
            num(step, "x1", num(step, "x")),
            num(step, "y1", num(step, "y")),
            num(step, "x2"), num(step, "y2"),
            num(step, "duration_ms", 300.0),
        )
        "key", "keyevent", "press_key" -> api.key(str(step, "key", str(step, "keyevent", "")))
        "open_app", "launch_app" -> api.openApp(
            str(step, "package", str(step, "pkg", "")),
            step.get("activity")?.asString,
        )
        "close_app" -> api.closeApp(str(step, "package", str(step, "pkg", "")))
        "shell", "run_shell" -> api.shellOk(str(step, "command", str(step, "cmd", "")))
        "clipboard", "set_clipboard" -> api.setClipboard(str(step, "text", ""))
        "input_text" -> api.inputText(
            str(step, "text", ""),
            optionalNum(step, "x"),
            optionalNum(step, "y"),
        )
        "foreground", "get_foreground" -> true.also { api.foreground() }
        "wake", "wake_up" -> api.wake()
        "sleep", "wait" -> true.also { api.sleep(num(step, "ms", num(step, "duration_ms", 500.0))) }
        "log" -> true.also { api.log(str(step, "message", str(step, "text", ""))) }
        else -> false
    }

    private fun str(obj: JsonObject, key: String, default: String = ""): String =
        obj.get(key)?.takeIf { !it.isJsonNull }?.asString?.trim() ?: default

    private fun num(obj: JsonObject, key: String, default: Double = 0.0): Double =
        obj.get(key)?.takeIf { !it.isJsonNull }?.asDouble ?: default

    private fun optionalNum(obj: JsonObject, key: String): Double? =
        obj.get(key)?.takeIf { !it.isJsonNull }?.asDouble

    companion object {
        private const val TAG = "ScriptRuntime"
        const val LANG_DSL = "dsl"
        const val LANG_JS = "js"
        const val MAX_SCRIPT_CHARS = 256 * 1024
        const val MAX_TIMEOUT_MS = 300_000L
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
