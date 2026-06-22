package com.clawnode.agent.log

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.clawnode.agent.BuildConfig
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.ConfigManager
import com.clawnode.agent.core.NodeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 日志导出与上传。
 *
 * 上传地址默认由 WebSocket URL 推导：
 *   ws://host:port/ws → http://host:port/api/clawnode/logs
 */
object LogUploadManager {

    private const val TAG = "LogUpload"
    const val DEFAULT_WINDOW_MINUTES = 5
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class UploadResult(val success: Boolean, val message: String)

    /** 打包最近 [windowMinutes] 分钟日志 + 设备信息为单个 txt 文件 */
    suspend fun prepareExportFile(context: Context, windowMinutes: Int = DEFAULT_WINDOW_MINUTES): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "log_export").apply { mkdirs() }
        val out = File(dir, "clawnode-log-${System.currentTimeMillis()}.txt")

        val header = buildString {
            appendLine("=== ClawNode Log Export ===")
            appendLine("version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine("model=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
            appendLine("sn=${ConfigManager.get(context).defaultNodeSn}")
            appendLine("window_minutes=$windowMinutes")
            appendLine("time=${System.currentTimeMillis()}")
            appendLine("===========================")
            appendLine()
        }
        out.writeText(header)
        out.appendText(ClawLog.collectRecentMinutes(windowMinutes))
        ClawLog.bp(TAG, "export_ready", "path=${out.absolutePath} size=${out.length()} windowMin=$windowMinutes")
        out
    }

    /** 上传到网关；失败时可改用 [shareLogFile] */
    suspend fun upload(context: Context, settings: NodeSettings, windowMinutes: Int = DEFAULT_WINDOW_MINUTES): UploadResult =
        withContext(Dispatchers.IO) {
            val wsUrl = settings.wsUrl
            if (!settings.isConnectable) {
                return@withContext UploadResult(false, "请先配置有效的 WebSocket URL")
            }
            val uploadUrl = deriveUploadUrl(wsUrl)
            val file = prepareExportFile(context, windowMinutes)

            ClawLog.bp(TAG, "upload_start", "url=$uploadUrl")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("sn", settings.nodeSn)
                .addFormDataPart("version", BuildConfig.VERSION_NAME)
                .addFormDataPart(
                    "log",
                    file.name,
                    file.asRequestBody("text/plain".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .post(body)
                .apply {
                    if (settings.authToken.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${settings.authToken}")
                    }
                }
                .build()

            return@withContext try {
                client.newCall(request).execute().use { resp ->
                    val msg = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        ClawLog.bp(TAG, "upload_ok", "http=${resp.code}")
                        UploadResult(true, "上传成功 (${resp.code})")
                    } else {
                        ClawLog.w(TAG, "upload_fail", "http=${resp.code} body=$msg")
                        UploadResult(false, "上传失败 HTTP ${resp.code}: $msg")
                    }
                }
            } catch (e: Exception) {
                ClawLog.e(TAG, "upload_error", uploadUrl, e)
                UploadResult(false, e.message ?: "upload error")
            }
        }

    suspend fun uploadWithCurrentSettings(context: Context, windowMinutes: Int = DEFAULT_WINDOW_MINUTES): UploadResult {
        val settings = ConfigManager.get(context).settings.first()
        return upload(context, settings, windowMinutes)
    }

    fun shareLogFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ClawNode Log ${BuildConfig.VERSION_NAME}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** ws://host:port/path → http://host:port/api/clawnode/logs */
    fun deriveUploadUrl(wsUrl: String): String {
        val httpUrl = wsUrl
            .replace("wss://", "https://", ignoreCase = true)
            .replace("ws://", "http://", ignoreCase = true)
        val uri = URI(httpUrl)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: "localhost"
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        val portPart = if (
            (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        ) "" else ":$port"
        return "$scheme://$host$portPart/api/clawnode/logs"
    }
}
