package com.clawnode.agent.vision

import android.content.Context
import android.graphics.Bitmap
import com.clawnode.agent.core.ClawLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/**
 * 单帧 MediaProjection 截图的挂起桥接。
 * [ScreenshotForegroundService] 取到首帧后通过 [complete] 唤醒等待方。
 */
object ScreenshotCaptureBridge {

    private val lock = Any()

    @Volatile
    private var pending: CompletableDeferred<Result<Bitmap>>? = null

    @Volatile
    private var pendingTraceId: String = ""

    suspend fun captureOnce(context: Context, traceId: String): Result<Bitmap> {
        if (!MediaProjectionHolder.hasAuthorization()) {
            ClawLog.w(TAG, "capture_denied", "trace=$traceId no media projection auth")
            return Result.failure(
                IllegalStateException("background capture needs screen record authorization")
            )
        }

        synchronized(lock) {
            if (pending != null && !pending!!.isCompleted) {
                ClawLog.w(TAG, "capture_busy", "trace=$traceId another projection capture in flight")
                return Result.failure(
                    IllegalStateException("screenshot projection capture already in progress")
                )
            }
        }

        val deferred = CompletableDeferred<Result<Bitmap>>()
        synchronized(lock) {
            pending?.cancel(CancellationException("superseded by trace=$traceId"))
            pending = deferred
            pendingTraceId = traceId
        }

        ClawLog.bp(TAG, "capture_request", "trace=$traceId starting ScreenshotForegroundService")
        ScreenshotForegroundService.start(context, traceId)

        return try {
            withTimeout(CAPTURE_TIMEOUT_MS) { deferred.await() }
        } catch (e: Exception) {
            ClawLog.e(TAG, "capture_timeout", "trace=$traceId", e)
            Result.failure(e)
        } finally {
            synchronized(lock) {
                if (pending === deferred) {
                    pending = null
                    pendingTraceId = ""
                }
            }
        }
    }

    fun complete(traceId: String, result: Result<Bitmap>) {
        synchronized(lock) {
            ClawLog.bp(
                TAG, "capture_complete",
                "trace=$traceId ok=${result.isSuccess} err=${result.exceptionOrNull()?.message.orEmpty()}"
            )
            pending?.complete(result)
            pending = null
            pendingTraceId = ""
        }
    }

    fun currentTraceId(): String = pendingTraceId

    private const val TAG = "ScreenshotBridge"
    private const val CAPTURE_TIMEOUT_MS = 15_000L
}
