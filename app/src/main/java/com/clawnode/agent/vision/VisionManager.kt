package com.clawnode.agent.vision

import android.content.Context
import android.content.Intent
import com.clawnode.agent.action.ActionExecutorService
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.system.MediaProjectionRequestActivity
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视觉与屏幕捕获模块。统一两种采集模式的入口：
 *
 *  - 模式 A（单帧，常态）：[captureSingleShot] 调用 [ActionExecutorService]
 *    暴露的挂起函数执行 takeScreenshot()，轻量、无需录屏授权。
 *  - 模式 B（连续推流，按需）：[startStream] 拉起 MediaProjection 授权
 *    并启动 [StreamForegroundService]；[stopStream] 彻底销毁推流资源。
 *
 * 本类只做编排与单帧采集；VirtualDisplay 的生命周期放在前台服务里，
 * 因为 MediaProjection 在 Android 14 强制要求 mediaProjection 类型前台服务。
 */
class VisionManager(
    private val context: Context,
    private val accessibilityService: ActionExecutorService,
    private val ws: WsManager
) {

    // ---------------- 模式 A：单次截图 ----------------

    /**
     * 处理 GET_SCREENSHOT：经 service 挂起函数拿到位图，
     * 在 [Dispatchers.IO] 上压缩为 JPEG→Base64，再以 SCREENSHOT_RESULT 回传。
     */
    suspend fun captureSingleShot(cmd: Command) {
        val quality = (cmd.payload?.quality ?: DEFAULT_QUALITY).coerceIn(1, 100)

        val bitmapResult = accessibilityService.captureScreenshotBitmap()
        val bitmap = bitmapResult.getOrElse { err ->
            ws.send(NodeResponse.actionResult(cmd.traceId, false, err.message ?: "screenshot failed"))
            return
        }

        // 编码是 CPU/IO 密集，切到 IO 池，避免占用默认计算调度器
        val base64 = withContext(Dispatchers.IO) {
            try {
                ImageCodec.bitmapToJpegBase64(bitmap, quality)
            } finally {
                bitmap.recycle()
            }
        }
        ws.send(NodeResponse.screenshot(cmd.traceId, base64))
    }

    // ---------------- 模式 B：连续推流 ----------------

    /**
     * 开启推流。若尚未拿到录屏授权，先拉起授权 Activity；
     * 授权 Activity 在成功后会自行拉起 [StreamForegroundService]。
     * 若已有授权，直接启动前台服务。
     */
    fun startStream(cmd: Command) {
        val fps = (cmd.payload?.fps ?: DEFAULT_FPS).coerceIn(1, 30)

        if (!MediaProjectionHolder.hasAuthorization()) {
            val intent = Intent(context, MediaProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MediaProjectionRequestActivity.EXTRA_TRACE_ID, cmd.traceId)
                .putExtra(MediaProjectionRequestActivity.EXTRA_FPS, fps)
            context.startActivity(intent)
            return
        }

        StreamForegroundService.start(context, cmd.traceId, fps)
    }

    /** 关闭推流并彻底销毁 VirtualDisplay 等资源（cmd 可为空，表示内部清理） */
    fun stopStream(cmd: Command?) {
        StreamForegroundService.stop(context)
        cmd?.let { ws.send(NodeResponse.streamStatus(it.traceId, true, "stream stopped")) }
    }

    companion object {
        const val DEFAULT_QUALITY = 80
        const val DEFAULT_FPS = 15
    }
}
