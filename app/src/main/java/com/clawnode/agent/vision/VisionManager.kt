package com.clawnode.agent.vision

import android.content.Context
import android.graphics.Bitmap
import com.clawnode.agent.action.ActionExecutorService
import com.clawnode.agent.core.AppForeground
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val captureMutex = Mutex()

    @Volatile
    private var lastCaptureAtMs: Long = 0L

    @Volatile
    private var lastCaptureBase64: String? = null

    @Volatile
    private var lastCaptureQuality: Int = DEFAULT_QUALITY

    suspend fun captureSingleShot(cmd: Command) {
        val quality = (cmd.params?.quality ?: DEFAULT_QUALITY).coerceIn(1, 100)
        val traceId = cmd.safeTraceId
        val inBg = !AppForeground.isInForeground(context)

        ClawLog.bp(TAG, "screenshot_start", "trace=$traceId bg=$inBg quality=$quality")

        captureMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = lastCaptureBase64
            if (
                cached != null &&
                lastCaptureQuality == quality &&
                now - lastCaptureAtMs < MIN_CAPTURE_INTERVAL_MS
            ) {
                ClawLog.bp(
                    TAG, "screenshot_cache_hit",
                    "trace=$traceId ageMs=${now - lastCaptureAtMs}"
                )
                ws.sendChecked(NodeResponse.screenshot(traceId, cached))
                return
            }

            val sinceLast = now - lastCaptureAtMs
            if (lastCaptureAtMs > 0L && sinceLast < MIN_CAPTURE_INTERVAL_MS) {
                delay(MIN_CAPTURE_INTERVAL_MS - sinceLast)
            }

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

            lastCaptureBase64 = base64
            lastCaptureAtMs = System.currentTimeMillis()
            lastCaptureQuality = quality

            val sent = ws.sendChecked(NodeResponse.screenshot(traceId, base64))
            ClawLog.bp(TAG, "screenshot_done", "trace=$traceId sent=$sent base64Len=${base64.length}")
        }
    }

    /**
     * 前台与后台均优先 takeScreenshot；仅失败时再降级 MediaProjection。
     * INTERVAL_TOO_SHORT / SECURE_WINDOW 不降级投影，避免 MIUI 上连发触发 token 复用崩溃。
     */
    private suspend fun resolveBitmap(traceId: String, inBg: Boolean): Result<Bitmap> {
        val a11y = captureWithA11yRetry(traceId)
        if (a11y.isSuccess) {
            ClawLog.bp(TAG, "screenshot_path", "trace=$traceId mode=takeScreenshot bg=$inBg")
            return a11y
        }

        val errMsg = a11y.exceptionOrNull()?.message.orEmpty()
        if (isIntervalTooShort(errMsg) || isSecureWindow(errMsg)) {
            ClawLog.w(TAG, "screenshot_a11y_no_fallback", "trace=$traceId err=$errMsg")
            return a11y
        }

        ClawLog.w(
            TAG, "screenshot_a11y_fail",
            "trace=$traceId bg=$inBg err=$errMsg → fallback projection"
        )

        if (!MediaProjectionHolder.hasAuthorization()) {
            return Result.failure(
                IllegalStateException(
                    "background screenshot requires screen capture authorization; open ClawNode app and tap 屏幕捕获授权"
                )
            )
        }
        ClawLog.bp(TAG, "screenshot_path", "trace=$traceId mode=mediaProjection bg=$inBg")
        return ScreenshotCaptureBridge.captureOnce(context, traceId)
    }

    private suspend fun captureWithA11yRetry(traceId: String): Result<Bitmap> {
        var last = accessibilityService.captureScreenshotBitmap()
        repeat(INTERVAL_MAX_RETRIES) { attempt ->
            if (last.isSuccess) return last
            val msg = last.exceptionOrNull()?.message.orEmpty()
            if (!isIntervalTooShort(msg)) return last
            ClawLog.bp(TAG, "screenshot_interval_retry", "trace=$traceId attempt=${attempt + 1}")
            delay(INTERVAL_RETRY_DELAY_MS)
            last = accessibilityService.captureScreenshotBitmap()
        }
        return last
    }

    private fun isIntervalTooShort(message: String): Boolean =
        message.contains("INTERVAL_TOO_SHORT", ignoreCase = true) ||
            (message.contains("interval", ignoreCase = true) && message.contains("short", ignoreCase = true))

    private fun isSecureWindow(message: String): Boolean =
        message.contains("SECURE_WINDOW", ignoreCase = true) ||
            message.contains("secure window", ignoreCase = true)

    fun startStream(cmd: Command) {
        val fps = (cmd.params?.fps ?: DEFAULT_FPS).coerceIn(1, 30)
        ClawLog.bp(TAG, "stream_start", "trace=${cmd.safeTraceId} fps=$fps")

        if (!MediaProjectionHolder.hasAuthorization()) {
            ws.sendChecked(NodeResponse.streamStatus(cmd.safeTraceId, false, "stream requires screen capture authorization; authorize in ClawNode app"))
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
        private const val MIN_CAPTURE_INTERVAL_MS = 1_000L
        private const val INTERVAL_RETRY_DELAY_MS = 900L
        private const val INTERVAL_MAX_RETRIES = 2
    }

}
