package com.clawnode.agent.system

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.clawnode.agent.core.ClawLog

/**
 * 无障碍文本输入：聚焦输入框后 SET_TEXT 或剪贴板粘贴。
 */
class TextInputController(
    private val accessibilityService: AccessibilityService,
    private val clipboardController: ClipboardController,
) {

    data class Result(val success: Boolean, val message: String)

    fun inputText(text: String, tapX: Int? = null, tapY: Int? = null): Result {
        if (text.isEmpty()) {
            return Result(false, "INPUT_TEXT requires text")
        }

        // 路径一：Shizuku 可用 → 切到 ClawNode IME 并经 commitText 注入。
        // 优势：不依赖无障碍读节点树、原生支持中文/emoji、规避小米键盘截屏黑屏，
        // 且 ime set 后焦点输入框会重绑到我们的 IME。
        if (ShizukuManager.isAvailable()) {
            val viaIme = tryInputViaIme(text, tapX, tapY)
            if (viaIme.success) return viaIme
            ClawLog.w(TAG, "ime_path_fail", "fallback to a11y: ${viaIme.message}")
        }

        // 路径二：回退无障碍 ACTION_SET_TEXT / 粘贴（无 Shizuku 或 IME 注入失败时）。
        return inputViaAccessibility(text, tapX, tapY)
    }

    /** 经 ClawNode IME 注入文本（Shizuku 已确保其为默认输入法）。 */
    private fun tryInputViaIme(text: String, tapX: Int?, tapY: Int?): Result {
        val ctx = accessibilityService
        val pkg = ctx.packageName
        // 确保 ClawIME 启用并设为默认（幂等）。
        ShizukuManager.ensureClawImeActive(pkg)
        // 若提供坐标，先点一下让目标输入框获得焦点（触发 IME 绑定）。
        // 焦点定位仍可借助无障碍（不依赖其注入文本）。
        Thread.sleep(400)
        // 发送 commit 广播给 IME。
        repeat(3) { attempt ->
            ctx.sendBroadcast(
                Intent(com.clawnode.agent.ime.ClawImeService.ACTION_COMMIT)
                    .setPackage(pkg)
                    .putExtra(com.clawnode.agent.ime.ClawImeService.EXTRA_OP, com.clawnode.agent.ime.ClawImeService.OP_COMMIT)
                    .putExtra(com.clawnode.agent.ime.ClawImeService.EXTRA_TEXT, text),
            )
            Thread.sleep(250)
            if (com.clawnode.agent.ime.ClawImeService.isBound) {
                ClawLog.bp(TAG, "input_text_ime", "len=${text.length} attempt=${attempt + 1}")
                return Result(true, "text committed via ClawIME (${text.length} chars)")
            }
        }
        // IME 尚未绑定到输入框（可能当前无焦点输入框）。
        return Result(false, "ClawIME not bound to a focused input field")
    }

    private fun inputViaAccessibility(text: String, tapX: Int?, tapY: Int?): Result {
        var lastErr = "no active window"
        repeat(4) { attempt ->
            val root = accessibilityService.rootInActiveWindow ?: firstContentWindowRoot()
            if (root == null) {
                lastErr = "no active window (wake screen and open app with input field first)"
                if (attempt < 3) {
                    Thread.sleep(350)
                    return@repeat
                }
                return Result(false, lastErr)
            }

            var target = findFocusedEditable(root)
            if (target == null && tapX != null && tapY != null) {
                target = findEditableNear(root, tapX, tapY)
            }
            // active window 没有输入框时（如焦点在 IME 窗口），跨所有窗口再找一次
            if (target == null) {
                target = findEditableAcrossWindows(tapX, tapY)
            }
            if (target == null) {
                root.recycle()
                lastErr = "no editable input field (tap input box first or provide x,y)"
                if (attempt < 3) {
                    Thread.sleep(300)
                    return@repeat
                }
                return Result(false, lastErr)
            }

            return try {
                val setOk = performSetText(target, text)
                if (setOk) {
                    ClawLog.bp(TAG, "input_text_set", "len=${text.length}")
                    Result(true, "text set (${text.length} chars)")
                } else {
                    clipboardController.setText(text)
                    val pasteOk = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    if (pasteOk) {
                        ClawLog.bp(TAG, "input_text_paste", "len=${text.length}")
                        Result(true, "text pasted (${text.length} chars)")
                    } else {
                        Result(false, "set text and paste both failed (focus input field first)")
                    }
                }
            } finally {
                target.recycle()
                root.recycle()
            }
        }
        return Result(false, lastErr)
    }

    fun pasteFocused(): Result {
        val root = accessibilityService.rootInActiveWindow
            ?: return Result(false, "no active window")
        val target = findFocusedEditable(root)
        if (target == null) {
            root.recycle()
            return Result(false, "no focused editable field")
        }
        return try {
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            ClawLog.bp(TAG, "paste_focused", "ok=$ok")
            Result(ok, if (ok) "paste ok" else "paste failed")
        } finally {
            target.recycle()
            root.recycle()
        }
    }

    private fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && isEditable(focused)) return focused
        focused?.recycle()
        return findEditableDfs(root)
    }

    private fun findEditableDfs(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditable(node)) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableDfs(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findEditableNear(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
    ): AccessibilityNodeInfo? = findEditableContaining(root, x, y)

    private fun findEditableContaining(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
    ): AccessibilityNodeInfo? {
        if (isEditable(node)) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty && rect.contains(x, y)) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableContaining(child, x, y)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        if (node.isEditable) return true
        val cls = node.className?.toString().orEmpty().lowercase()
        return cls.contains("edittext") || cls.contains("editing")
    }

    /** rootInActiveWindow 为 null 时，取第一个能拿到内容的窗口根节点。 */
    private fun firstContentWindowRoot(): AccessibilityNodeInfo? {
        return runCatching {
            accessibilityService.windows
                ?.asSequence()
                ?.mapNotNull { it.root }
                ?.firstOrNull()
        }.getOrNull()
    }

    /** 跨所有可交互窗口查找输入框（焦点落在 IME/overlay 窗口时的兜底）。 */
    private fun findEditableAcrossWindows(x: Int?, y: Int?): AccessibilityNodeInfo? {
        val windows = runCatching { accessibilityService.windows }.getOrNull() ?: return null
        for (win in windows) {
            val root = win.root ?: continue
            val found = if (x != null && y != null) {
                findEditableContaining(root, x, y) ?: findFocusedEditable(root)
            } else {
                findFocusedEditable(root)
            }
            root.recycle()
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val TAG = "TextInput"
    }
}
