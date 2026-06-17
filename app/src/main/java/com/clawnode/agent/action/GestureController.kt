package com.clawnode.agent.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 手势注入器：把网关下发的绝对像素坐标，封装为 [GestureDescription]
 * 经由 [AccessibilityService.dispatchGesture] 注入。
 *
 * 不关心 UI 节点树（VLA 模型基于像素决策），仅做坐标 → 手势的转换。
 */
class GestureController(private val service: AccessibilityService) {

    /** 单点点击。duration 为按压时长，默认 [DEFAULT_TAP_MS]。 */
    suspend fun tap(x: Float, y: Float, durationMs: Long = DEFAULT_TAP_MS): GestureResult {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** 直线滑动：从 (x1,y1) 滑到 (x2,y2)。duration 为滑动总时长。 */
    suspend fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = DEFAULT_SWIPE_MS
    ): GestureResult {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** 回调式 dispatchGesture → 挂起式封装 */
    private suspend fun dispatch(gesture: GestureDescription): GestureResult =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (cont.isActive) cont.resume(GestureResult(true, "completed"))
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (cont.isActive) cont.resume(GestureResult(false, "cancelled by system"))
                }
            }
            val accepted = service.dispatchGesture(gesture, callback, null)
            if (!accepted && cont.isActive) {
                cont.resume(GestureResult(false, "dispatchGesture rejected (service not ready?)"))
            }
        }

    data class GestureResult(val success: Boolean, val message: String)

    companion object {
        const val DEFAULT_TAP_MS = 80L
        const val DEFAULT_SWIPE_MS = 300L
    }
}
