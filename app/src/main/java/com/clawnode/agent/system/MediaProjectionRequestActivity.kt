package com.clawnode.agent.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.vision.MediaProjectionHolder
import com.clawnode.agent.vision.StreamForegroundService

/**
 * 透明 Activity，拉起系统录屏授权弹窗。
 */
class MediaProjectionRequestActivity : Activity() {

    private var traceId: String = ""
    private var fps: Int = 15
    private var mode: String = MODE_STREAM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        traceId = intent.getStringExtra(EXTRA_TRACE_ID).orEmpty()
        fps = intent.getIntExtra(EXTRA_FPS, 15)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_STREAM
        ClawLog.bp(TAG, "onCreate", "mode=$mode trace=$traceId fps=$fps")

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CODE) return

        if (resultCode == RESULT_OK && data != null) {
            MediaProjectionHolder.saveAuthorization(this, resultCode, data)
            ClawLog.bp(TAG, "auth_ok", "mode=$mode trace=$traceId")

            // For background screenshot support (MODE_AUTHORIZE), eagerly consume the fresh
            // authorization here to obtain a live MediaProjection instance. This avoids later
            // services attempting to call getMediaProjection with a stale resultData.
            // The first real capture (or stream) will start the required FG service with the
            // proper mediaProjection type.
            if (mode == MODE_AUTHORIZE) {
                try {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mp = mpm.getMediaProjection(resultCode, data)
                    if (mp != null) {
                        MediaProjectionHolder.projection = mp
                        MediaProjectionHolder.resultCode = 0
                        MediaProjectionHolder.resultData = null
                        ClawLog.bp(TAG, "auth_saved", "authorization stored for background capture (live projection ready)")
                    } else {
                        ClawLog.w(TAG, "auth_live_failed", "getMediaProjection returned null for AUTHORIZE")
                    }
                } catch (t: Throwable) {
                    ClawLog.w(TAG, "auth_consume_failed", t.message ?: "consume error")
                    // Live instance not created; first fallback use will try inside its FG service.
                }
            }

            if (mode == MODE_STREAM) {
                StreamForegroundService.start(this, traceId, fps)
            }
        } else {
            ClawLog.w(TAG, "auth_denied", "mode=$mode resultCode=$resultCode")
        }
        finish()
    }

    companion object {
        private const val TAG = "MediaProjectionReq"
        private const val REQ_CODE = 0xCA57
        const val EXTRA_TRACE_ID = "trace_id"
        const val EXTRA_FPS = "fps"
        const val EXTRA_MODE = "mode"
        const val MODE_STREAM = "stream"
        const val MODE_AUTHORIZE = "authorize"
    }
}
