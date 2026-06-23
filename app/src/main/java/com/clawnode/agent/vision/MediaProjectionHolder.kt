package com.clawnode.agent.vision

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection

/**
 * Holds the current MediaProjection authorization state in-process.
 *
 * - resultData/resultCode: one-time token returned by the system grant (consumed once).
 * - projection: the live MediaProjection instance obtained via getMediaProjection (reusable
 *   for multiple VirtualDisplays / captures until the system stops it).
 *
 * We no longer persist resultData across process death; a fresh user grant is required
 * after restarts if background capture is needed.
 */
object MediaProjectionHolder {
    @Volatile
    var resultCode: Int = 0

    @Volatile
    var resultData: Intent? = null

    /** 当前活跃的投影实例（由 StreamForegroundService 创建并持有） */
    @Volatile
    var projection: MediaProjection? = null

    fun hasAuthorization(): Boolean = projection != null || resultData != null

    fun hasPriorGrant(context: Context): Boolean = MediaProjectionAuthStore.hasEverGranted(context)

    fun restoreFromDisk(context: Context) {
        // Result data is no longer loaded from disk (one-time tokens cannot be reused that way).
        // This call is kept for compatibility / future extension. ever_granted is read directly
        // by hasPriorGrant when deciding UX hints.
        if (resultData != null) return
        // load(...) now always returns null by design.
        MediaProjectionAuthStore.load(context)
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
        projection = null
        context?.let { MediaProjectionAuthStore.clear(it) }
    }

    /** Called when the system (or user) stops the active MediaProjection; future captures will need re-auth. */
    fun clearProjection() {
        projection = null
        // We keep resultData/resultCode as-is; they are one-time tokens anyway.
        // hasAuthorization() will be false because we check resultData, but for projection users we prefer the live instance.
        // For safety also clear in-memory result so next use goes through proper re-auth flow.
        resultCode = 0
        resultData = null
    }
}
