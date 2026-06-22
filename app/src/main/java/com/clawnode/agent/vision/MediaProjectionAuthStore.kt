package com.clawnode.agent.vision

import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.util.Base64
import com.clawnode.agent.core.ClawLog

/**
 * 持久化 MediaProjection 授权，避免进程重启后后台截图权限「丢失」。
 * 注意：系统仍可能在重启后要求重新授权，但日常进程回收可恢复。
 */
object MediaProjectionAuthStore {
    private const val TAG = "MediaProjAuthStore"
    private const val PREFS = "media_projection_auth"
    private const val KEY_CODE = "result_code"
    private const val KEY_DATA = "result_data_b64"

    fun save(context: Context, resultCode: Int, data: Intent) {
        runCatching {
            val bytes = parcelIntent(data)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_CODE, resultCode)
                .putString(KEY_DATA, Base64.encodeToString(bytes, Base64.NO_WRAP))
                .apply()
            ClawLog.bp(TAG, "saved", "resultCode=$resultCode bytes=${bytes.size}")
        }.onFailure { e ->
            ClawLog.e(TAG, "save_failed", e.message ?: "save error", e)
        }
    }

    fun load(context: Context): Pair<Int, Intent>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DATA)) return null
        return runCatching {
            val code = prefs.getInt(KEY_CODE, 0)
            val b64 = prefs.getString(KEY_DATA, null) ?: return null
            val data = unparcelIntent(Base64.decode(b64, Base64.NO_WRAP))
            if (code == 0 || data == null) null else code to data
        }.getOrElse {
            ClawLog.w(TAG, "load_failed", it.message ?: "load error")
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun parcelIntent(intent: Intent): ByteArray {
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun unparcelIntent(bytes: ByteArray): Intent? {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
