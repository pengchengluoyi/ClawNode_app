package com.clawnode.agent.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.clawnode.agent.BuildConfig
import com.clawnode.agent.core.ClawLog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 通过 GitHub Releases 检查并安装 APK 更新。
 *
 * 数据源：GET /repos/{owner}/{repo}/releases/latest
 * 比较 tag_name（如 v1.4.0）与 [BuildConfig.VERSION_NAME]。
 */
object AppUpdateManager {

    private const val TAG = "AppUpdate"
    private const val GITHUB_OWNER = "pengchengluoyi"
    private const val GITHUB_REPO = "ClawNode_app"
    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String?,
        val assetName: String?
    )

    private data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("assets") val assets: List<GitHubAsset>?
    )

    private data class GitHubAsset(
        @SerializedName("name") val name: String?,
        @SerializedName("browser_download_url") val browserDownloadUrl: String?
    )

    suspend fun checkForUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        ClawLog.bp(TAG, "check_start", "current=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        val current = BuildConfig.VERSION_NAME

        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ClawNode-Android/${BuildConfig.VERSION_NAME}")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            ClawLog.w(TAG, "check_fail", "http=${response.code}")
            throw IllegalStateException("GitHub API HTTP ${response.code}")
        }

        val release = gson.fromJson(response.body?.string(), GitHubRelease::class.java)
        val tag = release.tagName?.removePrefix("v").orEmpty()
        if (tag.isBlank()) throw IllegalStateException("release tag_name empty")

        val apkAsset = release.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
        val hasUpdate = compareVersions(tag, current) > 0

        ClawLog.bp(
            TAG, "check_result",
            "latest=$tag hasUpdate=$hasUpdate asset=${apkAsset?.name}"
        )

        UpdateInfo(
            hasUpdate = hasUpdate,
            latestVersion = tag,
            currentVersion = current,
            releaseNotes = release.body.orEmpty(),
            downloadUrl = apkAsset?.browserDownloadUrl,
            assetName = apkAsset?.name
        )
    }

    /** 下载 APK 到 cache/updates/，返回本地文件 */
    suspend fun downloadApk(context: Context, url: String, fileName: String): File =
        withContext(Dispatchers.IO) {
            ClawLog.bp(TAG, "download_start", "url=$url")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, fileName)
            if (outFile.exists()) outFile.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ClawNode-Android/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("download HTTP ${response.code}")
                }
                response.body?.byteStream()?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("empty response body")
            }

            ClawLog.bp(TAG, "download_ok", "path=${outFile.absolutePath} size=${outFile.length()}")
            outFile
        }

    fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installApk(context: Context, apkFile: File) {
        ClawLog.bp(TAG, "install_start", "file=${apkFile.name}")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 语义化版本比较：1.4.0 vs 1.3.0 → 正数表示 a 更新 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
