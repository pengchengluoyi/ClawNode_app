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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import com.clawnode.agent.R
import com.clawnode.agent.core.ClawLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单帧 MediaProjection 截图前台服务。
 *
 * 后台场景下 takeScreenshot 不可靠时，用已授权的 MediaProjection 抓一帧后立即释放。
 */
class ScreenshotForegroundService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var traceId: String = ""
    private var width = 0
    private var height = 0
    private val frameDelivered = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            ClawLog.w(TAG, "projection_stopped", "trace=$traceId system stopped projection")
            // The shared projection is no longer valid; clear it so future uses will require re-auth.
            MediaProjectionHolder.clearProjection()
            failAndStop("media projection stopped by system")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        traceId = intent?.getStringExtra(EXTRA_TRACE_ID).orEmpty()
        ClawLog.bp(TAG, "onStartCommand", "trace=$traceId")
        startForegroundCompat()
        mainHandler.post { startCapture() }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.screenshot_notification_title))
            .setContentText(getString(R.string.screenshot_notification_text))
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
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startCapture() {
        try {
            // Ensure this service instance starts clean (defensive against rapid successive requests).
            releaseCapture()

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            // Prefer a previously obtained live MediaProjection instance.
            // Only fall back to consuming the one-time resultData to create it (and store for reuse).
            var mp = MediaProjectionHolder.projection
            if (mp == null) {
                val data = MediaProjectionHolder.resultData
                val code = MediaProjectionHolder.resultCode
                if (data == null || code == 0) {
                    failAndStop("no media projection authorization")
                    return
                }
                mp = mpm.getMediaProjection(code, data)
                if (mp == null) {
                    failAndStop("getMediaProjection returned null")
                    return
                }
                MediaProjectionHolder.projection = mp
                MediaProjectionHolder.resultCode = 0
                MediaProjectionHolder.resultData = null
                ClawLog.bp(TAG, "projection_created_from_result", "trace=$traceId")
            }

            projection = mp
            mp.registerCallback(projectionCallback, mainHandler)

            val metrics = resources.displayMetrics
            val (w, h) = scaledSize(metrics)
            width = w
            height = h

            ClawLog.bp(TAG, "capture_start", "trace=$traceId size=${w}x$h usingLiveProj=${MediaProjectionHolder.projection === mp}")

            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            virtualDisplay = mp.createVirtualDisplay(
                "ClawNodeScreenshot",
                w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )

            reader.setOnImageAvailableListener({ r -> onFrame(r) }, mainHandler)

            // 兜底：VirtualDisplay 迟迟无帧
            mainHandler.postDelayed({
                if (!frameDelivered.get()) {
                    ClawLog.w(TAG, "capture_frame_timeout", "trace=$traceId")
                    failAndStop("screenshot frame timeout")
                }
            }, FRAME_TIMEOUT_MS)
        } catch (t: Throwable) {
            // Never let a MediaProjection setup error (e.g. SecurityException on token reuse)
            // become an uncaught exception that crashes the process.
            ClawLog.e(TAG, "capture_start_error", "trace=$traceId", t)
            failAndStop("screenshot capture start failed: ${t.message}")
        }
    }

    private fun onFrame(reader: ImageReader) {
        if (!frameDelivered.compareAndSet(false, true)) return

        val image = reader.acquireLatestImage()
        if (image == null) {
            failAndStop("acquireLatestImage returned null")
            return
        }

        try {
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

            val cropped = if (rowPadding == 0) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }

            ClawLog.bp(TAG, "capture_frame_ok", "trace=$traceId ${width}x$height")
            ScreenshotCaptureBridge.complete(traceId, Result.success(cropped))
        } catch (t: Throwable) {
            ClawLog.e(TAG, "capture_frame_error", "trace=$traceId", t)
            ScreenshotCaptureBridge.complete(traceId, Result.failure(t))
        } finally {
            image.close()
            releaseCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun failAndStop(reason: String) {
        if (frameDelivered.compareAndSet(false, true)) {
            ScreenshotCaptureBridge.complete(traceId, Result.failure(IllegalStateException(reason)))
        }
        releaseCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCapture() {
        // For single-shot screenshots we only own the temporary VirtualDisplay + ImageReader.
        // We must NOT stop() the MediaProjection itself — it is shared for future captures.
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        // Unregister our callback but leave the projection alive in the holder.
        runCatching {
            projection?.unregisterCallback(projectionCallback)
        }
        // Do not null or stop the shared projection here.
        projection = null
    }

    override fun onDestroy() {
        ClawLog.bp(TAG, "onDestroy", "trace=$traceId")
        releaseCapture()
        super.onDestroy()
    }

    private fun scaledSize(metrics: DisplayMetrics): Pair<Int, Int> {
        val maxW = 720
        if (metrics.widthPixels <= maxW) return metrics.widthPixels to metrics.heightPixels
        val ratio = maxW.toFloat() / metrics.widthPixels
        val w = (metrics.widthPixels * ratio).toInt() and 1.inv()
        val h = (metrics.heightPixels * ratio).toInt() and 1.inv()
        return w to h
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.screenshot_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return CHANNEL_ID
    }

    companion object {
        private const val TAG = "ScreenshotFgService"
        private const val CHANNEL_ID = "clawnode_screenshot"
        private const val NOTIF_ID = 0xC1A6
        private const val FRAME_TIMEOUT_MS = 8_000L
        const val EXTRA_TRACE_ID = "trace_id"

        fun start(context: Context, traceId: String) {
            val intent = Intent(context, ScreenshotForegroundService::class.java)
                .putExtra(EXTRA_TRACE_ID, traceId)
            context.startForegroundService(intent)
        }
    }
}
