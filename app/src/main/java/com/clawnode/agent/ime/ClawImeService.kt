package com.clawnode.agent.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.InputConnection
import com.clawnode.agent.core.ClawLog

/**
 * ClawNode 自研输入法。
 *
 * 目的：
 * 1. 输入视图不设 FLAG_SECURE，避免像小米键盘那样导致 takeScreenshot 黑屏。
 * 2. 通过广播接收远程下发的文本，直接 commitText 到当前焦点输入框，
 *    不依赖无障碍读节点树，中文 / emoji 原生支持，也不受锁屏安全窗口影响。
 *
 * 由 ShizukuManager 自动 enable + 设为默认输入法（免手动）。
 */
class ClawImeService : InputMethodService() {

    private var commitReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerCommitReceiver()
        ClawLog.bp(TAG, "ime_created", "ClawNode IME service started")
    }

    /** 返回 1px 占位视图：不显示真实键盘 UI，也不设 FLAG_SECURE。 */
    override fun onCreateInputView(): View {
        return View(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
        }
    }

    private fun registerCommitReceiver() {
        if (commitReceiver != null) return
        commitReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra(EXTRA_OP) ?: OP_COMMIT) {
                    OP_DELETE -> {
                        val n = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
                        val ic: InputConnection? = currentInputConnection
                        val ok = ic?.deleteSurroundingText(n, 0) ?: false
                        ClawLog.bp(TAG, "ime_delete", "n=$n ok=$ok")
                    }
                    OP_CLEAR -> {
                        val ic: InputConnection? = currentInputConnection
                        // 选中全部再删除
                        ic?.performContextMenuAction(android.R.id.selectAll)
                        val ok = ic?.commitText("", 1) ?: false
                        ClawLog.bp(TAG, "ime_clear", "ok=$ok")
                    }
                    else -> {
                        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
                        val ic: InputConnection? = currentInputConnection
                        val ok = ic?.commitText(text, 1) ?: false
                        ClawLog.bp(TAG, "ime_commit", "len=${text.length} ok=$ok ic=${ic != null}")
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_COMMIT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commitReceiver, filter)
        }
    }

    override fun onDestroy() {
        commitReceiver?.let { runCatching { unregisterReceiver(it) } }
        commitReceiver = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ClawIme"
        const val ACTION_COMMIT = "com.clawnode.agent.IME_COMMIT"
        const val EXTRA_OP = "op"
        const val EXTRA_TEXT = "text"
        const val EXTRA_COUNT = "count"
        const val OP_COMMIT = "commit"
        const val OP_DELETE = "delete"
        const val OP_CLEAR = "clear"

        /** 当前进程内是否已有 ClawIME 实例在运行（即被系统绑定为输入法）。 */
        @Volatile
        var isBound: Boolean = false
            private set
    }

    override fun onBindInput() {
        super.onBindInput()
        isBound = true
    }
}
