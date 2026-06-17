package com.clawnode.agent.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.clawnode.agent.vision.MediaProjectionHolder
import com.clawnode.agent.vision.StreamForegroundService

/**
 * 透明 Activity，专门用于拉起系统录屏授权弹窗。
 *
 * MediaProjection 授权必须由 Activity 通过 startActivityForResult 发起，
 * 因此从无障碍服务/前台服务无法直接申请，需要这个中转。
 * 授权成功后把结果存入 [MediaProjectionHolder]，并启动推流前台服务。
 */
class MediaProjectionRequestActivity : Activity() {

    private var traceId: String = ""
    private var fps: Int = 15

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        traceId = intent.getStringExtra(EXTRA_TRACE_ID).orEmpty()
        fps = intent.getIntExtra(EXTRA_FPS, 15)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                MediaProjectionHolder.resultCode = resultCode
                MediaProjectionHolder.resultData = data
                StreamForegroundService.start(this, traceId, fps)
            }
            finish()
        }
    }

    companion object {
        private const val REQ_CODE = 0xCA57
        const val EXTRA_TRACE_ID = "trace_id"
        const val EXTRA_FPS = "fps"
    }
}
