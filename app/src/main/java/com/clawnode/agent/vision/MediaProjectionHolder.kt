package com.clawnode.agent.vision

import android.content.Intent
import android.media.projection.MediaProjection

/**
 * MediaProjection 授权结果的中转。
 *
 * MediaProjectionRequestActivity 拿到 (resultCode, data) 后存入此处，
 * StreamForegroundService 在前台化之后再用它创建 MediaProjection。
 * 用进程内单例传递，避免把不可序列化的 MediaProjection 塞进 Intent。
 */
object MediaProjectionHolder {
    @Volatile
    var resultCode: Int = 0

    @Volatile
    var resultData: Intent? = null

    /** 当前活跃的投影实例（由 StreamForegroundService 创建并持有） */
    @Volatile
    var projection: MediaProjection? = null

    fun hasAuthorization(): Boolean = resultData != null

    fun clearAuthorization() {
        resultCode = 0
        resultData = null
    }
}
