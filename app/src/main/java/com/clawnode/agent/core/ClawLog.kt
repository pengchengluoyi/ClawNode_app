package com.clawnode.agent.core

import android.content.Context
import android.util.Log
import com.clawnode.agent.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 统一断点日志：同时写 logcat 与本地文件（供上传/分享）。
 *
 * 过滤 logcat：`adb logcat -s ClawNode`
 * 本地文件：`files/logs/clawnode.log`
 */
object ClawLog {

    private const val ROOT = "ClawNode"
    private const val MAX_LOG_BYTES = 2 * 1024 * 1024L // 2MB 滚动

    private var logFile: File? = null
    private val fileLock = ReentrantLock()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "clawnode.log")
        bp("ClawLog", "init", "path=${logFile?.absolutePath} v${BuildConfig.VERSION_NAME}")
    }

    fun logFile(): File? = logFile

    /** 读取最近 [windowMinutes] 分钟内的日志行（含滚动备份）。 */
    fun collectRecentMinutes(windowMinutes: Int): String {
        val minutes = windowMinutes.coerceIn(1, 24 * 60)
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        val files = listOfNotNull(
            logFile?.takeIf { it.exists() },
            logFile?.parentFile?.let { File(it, "clawnode.log.bak") }?.takeIf { it.exists() },
        )
        if (files.isEmpty()) return "(no local log file yet)\n"

        val kept = linkedSetOf<String>()
        for (file in files) {
            file.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val ts = parseLineTime(line) ?: continue
                    if (ts >= cutoff) kept.add(line)
                }
            }
        }
        return if (kept.isEmpty()) "(no log lines in last ${minutes}m)\n" else kept.joinToString("\n", postfix = "\n")
    }

    private fun parseLineTime(line: String): Long? {
        if (line.length < 23) return null
        return runCatching { timeFmt.parse(line.substring(0, 23))?.time }.getOrNull()
    }

    fun bp(tag: String, checkpoint: String, detail: String = "") {
        write("D", tag, checkpoint, detail, null)
    }

    fun w(tag: String, checkpoint: String, detail: String = "") {
        write("W", tag, checkpoint, detail, null)
    }

    fun e(tag: String, checkpoint: String, detail: String = "", t: Throwable? = null) {
        write("E", tag, checkpoint, detail, t)
    }

    private fun write(level: String, tag: String, checkpoint: String, detail: String, t: Throwable?) {
        val line = buildString {
            append(timeFmt.format(Date()))
            append(' ')
            append(level)
            append(" [")
            append(tag)
            append("] ")
            append(format(checkpoint, detail))
            if (t != null) {
                append(" | ")
                append(t.javaClass.simpleName)
                append(": ")
                append(t.message)
            }
        }

        when (level) {
            "W" -> Log.w("$ROOT/$tag", format(checkpoint, detail))
            "E" -> {
                if (t != null) Log.e("$ROOT/$tag", format(checkpoint, detail), t)
                else Log.e("$ROOT/$tag", format(checkpoint, detail))
            }
            else -> Log.d("$ROOT/$tag", format(checkpoint, detail))
        }

        appendToFile(line, t)
    }

    private fun appendToFile(line: String, t: Throwable?) {
        val file = logFile ?: return
        fileLock.withLock {
            runCatching {
                rotateIfNeeded(file)
                FileWriter(file, true).use { w ->
                    w.appendLine(line)
                    if (t != null) w.appendLine(Log.getStackTraceString(t))
                }
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val bak = File(file.parent, "clawnode.log.bak")
            if (bak.exists()) bak.delete()
            file.renameTo(bak)
        }
    }

    private fun format(checkpoint: String, detail: String): String =
        if (detail.isEmpty()) "[BP:$checkpoint]" else "[BP:$checkpoint] $detail"
}
