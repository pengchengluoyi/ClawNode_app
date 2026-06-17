package com.clawnode.agent.vision

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.view.Display
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.system.MediaProjectionRequestActivity
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 视觉与屏幕捕获模块。统一两种采集模式的入口：
 *
 *  - 模式 A（单帧，常态）：[captureSingleShot] 优先用
 *    AccessibilityService.takeScreenshot()，轻量、无需录屏授权。
 *  - 模式 B（连续推流，按需）：[startStream] 拉起 MediaProjection 授权
 *    并启动 [StreamForegroundService]；[stopStream] 彻底销毁推流资源。
 *
 * 本类只做编排与单帧采集；VirtualDisplay 的生命周期放在前台服务里，
 * 因为 MediaProjection 在 Android 14 强制要求 mediaProjection 类型前台服务。
 */
class VisionManager(
    private val context: Context,
    private val accessibilityService: AccessibilityService,
    private val scope: CoroutineScope,
    private val ws: WsManager
) {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    // ---------------- 模式 A：单次截图 ----------------

    fun captureSingleShot(cmd: Command) {
        val quality = cmd.payload?.quality ?: DEFAULT_QUALITY
        accessibilityService.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    // 在协程里做编码，避免阻塞回调线程
                    scope.launch(Dispatchers.Default) {
                        val bitmap = result.toBitmap()
                        if (bitmap == null) {
                            ws.send(NodeResponse.actionResult(cmd.traceId, false, "decode screenshot failed"))
                            return@launch
                        }
                        val base64 = ImageCodec.bitmapToJpegBase64(bitmap, quality)
                        bitmap.recycle()
                        ws.send(NodeResponse.screenshot(cmd.traceId, base64))
                    }
                }

                override fun onFailure(errorCode: Int) {
                    ws.send(
                        NodeResponse.actionResult(
                            cmd.traceId, false, "takeScreenshot failed code=$errorCode"
                        )
                    )
                }
            }
        )
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

    private fun AccessibilityService.ScreenshotResult.toBitmap(): Bitmap? {
        val buffer: HardwareBuffer = hardwareBuffer
        return try {
            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                // 转成软件位图以便 JPEG 压缩（硬件位图不能直接 compress 到部分通路）
                ?.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            buffer.close()
        }
    }

    companion object {
        const val DEFAULT_QUALITY = 80
        const val DEFAULT_FPS = 15
    }
}
