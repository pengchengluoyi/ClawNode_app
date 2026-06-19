package com.clawnode.agent.core

import android.util.Log

/**
 * 统一断点日志。过滤 tag 用 `adb logcat -s ClawNode` 即可看到全链路。
 *
 * 格式：`[BP:checkpoint] detail`
 */
object ClawLog {

    private const val ROOT = "ClawNode"

    /** 正常断点（连接、指令、截图路径等） */
    fun bp(tag: String, checkpoint: String, detail: String = "") {
        Log.d("$ROOT/$tag", format(checkpoint, detail))
    }

    /** 异常/降级路径 */
    fun w(tag: String, checkpoint: String, detail: String = "") {
        Log.w("$ROOT/$tag", format(checkpoint, detail))
    }

    /** 错误 */
    fun e(tag: String, checkpoint: String, detail: String = "", t: Throwable? = null) {
        if (t != null) {
            Log.e("$ROOT/$tag", format(checkpoint, detail), t)
        } else {
            Log.e("$ROOT/$tag", format(checkpoint, detail))
        }
    }

    private fun format(checkpoint: String, detail: String): String =
        if (detail.isEmpty()) "[BP:$checkpoint]" else "[BP:$checkpoint] $detail"
}
