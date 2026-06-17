package com.clawnode.agent.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import com.clawnode.agent.R
import com.clawnode.agent.model.NodeResponse
import java.util.concurrent.atomic.AtomicLong

/**
 * 推流前台服务（mediaProjection 类型）。
 *
 * 生命周期：start() → 前台化 → 用授权创建 MediaProjection →
 *   建 ImageReader + VirtualDisplay → 按 fps 节流取帧 → 编码回传。
 * stop() → 注销 VirtualDisplay / ImageReader / MediaProjection，彻底释放。
 *
 * 回传通道复用常驻的 WsManager（经由 AccessibilityService 实例拿到）。
 */
class StreamForegroundService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var traceId: String = ""
    private var frameIntervalMs: Long = 1000L / VisionManager.DEFAULT_FPS
    private val lastFrameAt = AtomicLong(0L)

    @Volatile
    private var width = 0
    @Volatile
    private var height = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户在系统层停止投影时也要彻底清理
            releaseCapture()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                traceId = intent?.getStringExtra(EXTRA_TRACE_ID).orEmpty()
                val fps = (intent?.getIntExtra(EXTRA_FPS, VisionManager.DEFAULT_FPS)
                    ?: VisionManager.DEFAULT_FPS).coerceIn(1, 30)
                frameIntervalMs = 1000L / fps
                startForegroundCompat()
                startCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.stream_notification_title))
            .setContentText(getString(R.string.stream_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startCapture() {
        val data = MediaProjectionHolder.resultData
        val code = MediaProjectionHolder.resultCode
        if (data == null) {
            sendStreamStatus(false, "no media projection authorization")
            stopSelf()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        if (mp == null) {
            sendStreamStatus(false, "getMediaProjection returned null")
            stopSelf()
            return
        }
        projection = mp
        MediaProjectionHolder.projection = mp
        mp.registerCallback(projectionCallback, null)

        val metrics = resources.displayMetrics
        // 为节省带宽，按比例缩放到不超过 720 宽（保持纵横比）
        val (w, h) = scaledSize(metrics)
        width = w
        height = h

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "ClawNodeStream",
            w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        reader.setOnImageAvailableListener({ r -> onFrame(r) }, null)
        sendStreamStatus(true, "stream started ${w}x${h} @ ${1000L / frameIntervalMs}fps")
    }

    private fun onFrame(reader: ImageReader) {
        // acquireLatestImage 自动丢弃积压帧，天然限流到“最新帧”
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = System.nanoTime() / 1_000_000L
            if (now - lastFrameAt.get() < frameIntervalMs) return // fps 节流
            lastFrameAt.set(now)

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁掉行填充
            val cropped = if (rowPadding == 0) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }

            val base64 = ImageCodec.bitmapToJpegBase64(cropped, STREAM_QUALITY)
            cropped.recycle()
            StreamBridge.wsRef?.send(NodeResponse.streamFrame(traceId, base64, width, height))
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "frame encode error: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun scaledSize(metrics: DisplayMetrics): Pair<Int, Int> {
        val maxW = 720
        if (metrics.widthPixels <= maxW) return metrics.widthPixels to metrics.heightPixels
        val ratio = maxW.toFloat() / metrics.widthPixels
        // 对齐到偶数，部分编码器要求
        val w = (metrics.widthPixels * ratio).toInt() and 1.inv()
        val h = (metrics.heightPixels * ratio).toInt() and 1.inv()
        return w to h
    }

    private fun releaseCapture() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        projection = null
        MediaProjectionHolder.projection = null
    }

    override fun onDestroy() {
        releaseCapture()
        super.onDestroy()
    }

    private fun sendStreamStatus(success: Boolean, message: String) {
        StreamBridge.wsRef?.send(NodeResponse.streamStatus(traceId, success, message))
    }

    private fun ensureChannel(): String {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.stream_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "StreamFgService"
        private const val CHANNEL_ID = "clawnode_stream"
        private const val NOTIF_ID = 0xC1A4
        private const val MAX_IMAGES = 2
        private const val STREAM_QUALITY = 60

        const val EXTRA_TRACE_ID = "trace_id"
        const val EXTRA_FPS = "fps"
        const val ACTION_STOP = "com.clawnode.agent.STOP_STREAM"

        fun start(context: Context, traceId: String, fps: Int) {
            val intent = Intent(context, StreamForegroundService::class.java)
                .putExtra(EXTRA_TRACE_ID, traceId)
                .putExtra(EXTRA_FPS, fps)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
