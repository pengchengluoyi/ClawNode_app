package com.clawnode.agent.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.clawnode.agent.core.ClawLog

/**
 * 剪贴板控制器：支持服务端下发 SET_CLIPBOARD 修改设备剪贴板。
 */
class ClipboardController(private val context: Context) {

    data class Result(val success: Boolean, val message: String)

    fun setText(text: String): Result {
        if (text.isBlank()) {
            return Result(false, "SET_CLIPBOARD requires non-empty text")
        }
        return try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            if (cm == null) {
                return Result(false, "ClipboardManager unavailable")
            }
            cm.setPrimaryClip(ClipData.newPlainText("ClawNode", text))
            ClawLog.bp(TAG, "set_clipboard_ok", "len=${text.length}")
            Result(true, "clipboard set (${text.length} chars)")
        } catch (e: Exception) {
            ClawLog.e(TAG, "set_clipboard_fail", e.message ?: "", e)
            Result(false, e.message ?: "set clipboard failed")
        }
    }

    companion object {
        private const val TAG = "ClipboardController"
    }
}
