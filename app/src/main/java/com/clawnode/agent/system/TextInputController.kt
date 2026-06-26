package com.clawnode.agent.system

import android.accessibilityservice.AccessibilityService
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
