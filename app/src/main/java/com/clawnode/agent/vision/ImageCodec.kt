package com.clawnode.agent.vision

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** 位图编码工具：Bitmap → JPEG → Base64 字符串 */
object ImageCodec {

    fun bitmapToJpegBase64(bitmap: Bitmap, quality: Int): String {
        val q = quality.coerceIn(1, 100)
        ByteArrayOutputStream().use { bos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, q, bos)
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }
    }
}
