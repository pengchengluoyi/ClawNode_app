package com.clawnode.agent.vision

import android.content.Context
import android.content.Intent
import com.clawnode.agent.core.ClawLog

/**
 * Tracks whether the user has ever successfully authorized screen capture ("ever_granted").
 * The actual MediaProjection token cannot be usefully persisted across restarts (it contains
 * one-time binders). After a fresh grant we materialize a live MediaProjection in memory.
 */
object MediaProjectionAuthStore {
    private const val TAG = "MediaProjAuthStore"
    private const val PREFS = "media_projection_auth"
    private const val KEY_CODE = "result_code"
    private const val KEY_DATA = "result_data_b64"

    fun save(context: Context, resultCode: Int, data: Intent) {
        // We no longer persist the authorization Intent (resultData). It contains one-time
        // system tokens/binders that cannot be marshalled or reused after process death or
        // multiple getMediaProjection calls. We only persist the "ever_granted" signal.
        // The live MediaProjection is kept in-memory and (re)created on first use after a fresh grant.
        setEverGranted(context, true)
        ClawLog.bp(TAG, "save_ever_granted_only", "resultCode=$resultCode (data not persisted)")
    }

    fun load(context: Context): Pair<Int, Intent>? {
        // ResultData is intentionally not restored from disk. A MediaProjection authorization
        // token is bound to a specific grant session and cannot be usefully reused across
        // full process restarts. Callers will need a fresh user grant (via the app UI).
        return null
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private const val KEY_GRANTED = "ever_granted"

    /** 记录用户曾经成功授权过（用于重启/更新后自动重新拉起授权流程，减少手动操作） */
    fun setEverGranted(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GRANTED, granted)
            .apply()
    }

    fun hasEverGranted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GRANTED, false)
    }

}
