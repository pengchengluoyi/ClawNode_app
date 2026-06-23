package com.clawnode.agent.vision

import android.content.Context
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

    fun hasPriorGrant(context: Context): Boolean = MediaProjectionAuthStore.hasEverGranted(context)

    fun restoreFromDisk(context: Context) {
        if (resultData != null) return
        val loaded = MediaProjectionAuthStore.load(context) ?: return
        resultCode = loaded.first
        resultData = loaded.second
    }

    fun saveAuthorization(context: Context, code: Int, data: Intent) {
        resultCode = code
        resultData = data
        MediaProjectionAuthStore.save(context, code, data)
        MediaProjectionAuthStore.setEverGranted(context, true)
    }

    fun clearAuthorization(context: Context? = null) {
        resultCode = 0
        resultData = null
        context?.let { MediaProjectionAuthStore.clear(it) }
    }
}
