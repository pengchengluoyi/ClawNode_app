package com.clawnode.agent.vision

import android.content.Context
import android.graphics.Bitmap
import com.clawnode.agent.action.ActionExecutorService
import com.clawnode.agent.core.AppForeground
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.system.MediaProjectionRequestActivity
import com.clawnode.agent.ws.WsManager
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视觉与屏幕捕获模块。统一两种采集模式的入口：
 *
 *  - 模式 A（单帧，常态）：前台优先 [takeScreenshot]；后台或失败时降级 MediaProjection 单帧。
 *  - 模式 B（连续推流，按需）：[startStream] 拉起 MediaProjection 授权并启动 [StreamForegroundService]。
 */
class VisionManager(
    private val context: Context,
    private val accessibilityService: ActionExecutorService,
    private val ws: WsManager
) {

    suspend fun captureSingleShot(cmd: Command) {
        val quality = (cmd.params?.quality ?: DEFAULT_QUALITY).coerceIn(1, 100)
        val traceId = cmd.safeTraceId
        val inBg = !AppForeground.isInForeground(context)

        ClawLog.bp(TAG, "screenshot_start", "trace=$traceId bg=$inBg quality=$quality")

        val bitmapResult = resolveBitmap(traceId, inBg)
        val bitmap = bitmapResult.getOrElse { err ->
            ClawLog.w(TAG, "screenshot_fail", "trace=$traceId ${err.message}")
            ws.sendChecked(
                NodeResponse.actionResult(traceId, false, err.message ?: "screenshot failed")
            )
            return
        }

        val base64 = withContext(Dispatchers.IO) {
            try {
                ImageCodec.bitmapToJpegBase64(bitmap, quality)
            } finally {
                bitmap.recycle()
            }
        }

        val sent = ws.sendChecked(NodeResponse.screenshot(traceId, base64))
        ClawLog.bp(TAG, "screenshot_done", "trace=$traceId sent=$sent base64Len=${base64.length}")
    }

    /**
     * 前台与后台均优先 takeScreenshot；仅失败时再降级 MediaProjection。
     * 原先后台直接跳过 a11y 会导致未授权 MediaProjection 时无法截图。
     */
    private suspend fun resolveBitmap(traceId: String, inBg: Boolean): Result<Bitmap> {
        val a11y = accessibilityService.captureScreenshotBitmap()
        if (a11y.isSuccess) {
            ClawLog.bp(TAG, "screenshot_path", "trace=$traceId mode=takeScreenshot bg=$inBg")
            return a11y
        }
        ClawLog.w(
            TAG, "screenshot_a11y_fail",
            "trace=$traceId bg=$inBg err=${a11y.exceptionOrNull()?.message} → fallback projection"
        )

        if (!MediaProjectionHolder.hasAuthorization()) {
            return Result.failure(
                IllegalStateException(
                    "background screenshot requires screen capture authorization; open ClawNode app → 授权屏幕捕获"
                )
            )
        }
        ClawLog.bp(TAG, "screenshot_path", "trace=$traceId mode=mediaProjection bg=$inBg")
        return ScreenshotCaptureBridge.captureOnce(context, traceId)
    }

    fun startStream(cmd: Command) {
        val fps = (cmd.params?.fps ?: DEFAULT_FPS).coerceIn(1, 30)
        ClawLog.bp(TAG, "stream_start", "trace=${cmd.safeTraceId} fps=$fps")

        if (!MediaProjectionHolder.hasAuthorization()) {
            val intent = Intent(context, MediaProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MediaProjectionRequestActivity.EXTRA_TRACE_ID, cmd.safeTraceId)
                .putExtra(MediaProjectionRequestActivity.EXTRA_FPS, fps)
                .putExtra(MediaProjectionRequestActivity.EXTRA_MODE, MediaProjectionRequestActivity.MODE_STREAM)
            context.startActivity(intent)
            return
        }

        StreamForegroundService.start(context, cmd.safeTraceId, fps)
    }

    fun stopStream(cmd: Command?) {
        ClawLog.bp(TAG, "stream_stop", "trace=${cmd?.safeTraceId}")
        StreamForegroundService.stop(context)
        cmd?.let { ws.sendChecked(NodeResponse.streamStatus(it.safeTraceId, true, "stream stopped")) }
    }

    companion object {
        private const val TAG = "VisionManager"
        const val DEFAULT_QUALITY = 80
        const val DEFAULT_FPS = 15
    }
}
