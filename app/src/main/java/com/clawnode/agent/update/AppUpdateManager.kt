package com.clawnode.agent.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
 * GitHub Releases 检查、下载与安装。
 *
 * 优先读取仓库 [latest.json]（无 API 限流），其次解析 releases/latest 重定向，
 * 最后才回退 GitHub REST API。
 */
object AppUpdateManager {

    private const val TAG = "AppUpdate"
    private const val GITHUB_OWNER = "pengchengluoyi"
    private const val GITHUB_REPO = "ClawNode_app"
    private const val API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/latest.json"
    private const val RELEASES_LATEST =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    const val ACTION_INSTALL_RESULT = "com.clawnode.agent.INSTALL_RESULT"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
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

    private data class LatestManifest(
        @SerializedName("version") val version: String?,
        @SerializedName("tag") val tag: String?,
        @SerializedName("apk") val apk: String?,
        @SerializedName("download_url") val downloadUrl: String?
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

        val resolved = resolveLatestRelease()

        val hasUpdate = compareVersions(resolved.version, current) > 0
        ClawLog.bp(
            TAG,
            "check_result",
            "latest=${resolved.version} hasUpdate=$hasUpdate source=${resolved.source} asset=${resolved.assetName}"
        )

        UpdateInfo(
            hasUpdate = hasUpdate,
            latestVersion = resolved.version,
            currentVersion = current,
            releaseNotes = resolved.notes,
            downloadUrl = resolved.downloadUrl,
            assetName = resolved.assetName
        )
    }

    private data class ResolvedRelease(
        val version: String,
        val downloadUrl: String?,
        val assetName: String?,
        val notes: String,
        val source: String
    )

    /** 取 manifest / releases 重定向 / API 中版本号最高者，避免 main 上 latest.json 滞后 */
    private fun resolveLatestRelease(): ResolvedRelease {
        val candidates = buildList {
            fetchFromManifest()?.let { add(it) }
            fetchFromLatestRedirect()?.let { add(it) }
            runCatching { fetchFromGitHubApi() }.onSuccess { add(it) }
                .onFailure { e -> ClawLog.w(TAG, "api_miss", e.message ?: "") }
        }
        if (candidates.isEmpty()) {
            throw IllegalStateException("无法获取最新版本信息")
        }
        return candidates.maxBy { compareVersions(it.version, "0") }
    }

    private fun fetchFromManifest(): ResolvedRelease? {
        return runCatching {
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .header("User-Agent", userAgent())
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val manifest = gson.fromJson(response.body?.string(), LatestManifest::class.java)
                val version = manifest.version?.trim().orEmpty()
                if (version.isBlank()) return null
                val tag = manifest.tag?.trim().takeUnless { it.isNullOrBlank() } ?: "v$version"
                val apk = manifest.apk?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "ClawNode-$version.apk"
                val url = manifest.downloadUrl?.trim().takeUnless { it.isNullOrBlank() }
                    ?: buildDownloadUrl(tag, apk)
                ResolvedRelease(version, url, apk, "", "manifest")
            }
        }.getOrElse { e ->
            ClawLog.w(TAG, "manifest_miss", e.message ?: "")
            null
        }
    }

    private fun fetchFromLatestRedirect(): ResolvedRelease? {
        return runCatching {
            val request = Request.Builder()
                .url(RELEASES_LATEST)
                .head()
                .header("User-Agent", userAgent())
                .build()
            noRedirectClient.newCall(request).execute().use { response ->
                if (response.code !in 300..399) {
                    throw IllegalStateException("releases/latest HTTP ${response.code}")
                }
                val location = response.header("Location").orEmpty()
                val tag = location.substringAfterLast("/").trim()
                if (!tag.startsWith("v")) {
                    throw IllegalStateException("unexpected redirect: $location")
                }
                val version = tag.removePrefix("v")
                val apk = "ClawNode-$version.apk"
                ResolvedRelease(
                    version = version,
                    downloadUrl = buildDownloadUrl(tag, apk),
                    assetName = apk,
                    notes = "",
                    source = "redirect"
                )
            }
        }.getOrElse { e ->
            ClawLog.w(TAG, "redirect_miss", e.message ?: "")
            null
        }
    }

    private fun fetchFromGitHubApi(): ResolvedRelease {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent())
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 403) {
                throw IllegalStateException("GitHub API 访问频率受限(403)，请稍后再试或使用浏览器下载")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub API HTTP ${response.code}")
            }
            val release = gson.fromJson(response.body?.string(), GitHubRelease::class.java)
            val tag = release.tagName?.trim().orEmpty()
            val version = tag.removePrefix("v")
            if (version.isBlank()) throw IllegalStateException("release tag_name empty")
            val apkAsset = release.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
            return ResolvedRelease(
                version = version,
                downloadUrl = apkAsset?.browserDownloadUrl,
                assetName = apkAsset?.name,
                notes = release.body.orEmpty(),
                source = "api"
            )
        }
    }

    private fun buildDownloadUrl(tag: String, apkName: String): String =
        "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tag/$apkName"

    private fun userAgent(): String = "ClawNode-Android/${BuildConfig.VERSION_NAME}"

    /** 启动时静默检查：有更新且已有安装权限则后台下载并拉起系统安装 */
    suspend fun autoUpdateIfNeeded(context: Context): Boolean {
        return runCatching {
            if (!canInstallPackages(context)) {
                ClawLog.bp(TAG, "auto_skip", "no install permission")
                return false
            }
            val info = checkForUpdate()
            if (!info.hasUpdate || info.downloadUrl.isNullOrBlank()) return false

            ClawLog.bp(TAG, "auto_download", "v${info.latestVersion}")
            val file = downloadApk(context, info.downloadUrl, info.assetName ?: "ClawNode.apk")
            installApk(context, file)
            true
        }.getOrElse { e ->
            ClawLog.w(TAG, "auto_update_fail", e.message ?: "")
            false
        }
    }

    suspend fun downloadApk(context: Context, url: String, fileName: String): File =
        withContext(Dispatchers.IO) {
            ClawLog.bp(TAG, "download_start", "url=$url")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, fileName)
            if (outFile.exists()) outFile.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 403) {
                    throw IllegalStateException("下载被拒绝(403)，请稍后重试")
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("download HTTP ${response.code}")
                }
                response.body?.byteStream()?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("empty response body")
            }

            ClawLog.bp(TAG, "download_ok", "size=${outFile.length()}")
            outFile
        }

    fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 使用 PackageInstaller Session 提交安装（系统自动弹出安装确认） */
    fun installApk(context: Context, apkFile: File) {
        ClawLog.bp(TAG, "install_start", "file=${apkFile.name} size=${apkFile.length()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { installWithPackageInstaller(context, apkFile) }
                .onFailure { e ->
                    ClawLog.e(TAG, "install_session_fail", "fallback ACTION_VIEW", e)
                    installWithViewIntent(context, apkFile)
                }
        } else {
            installWithViewIntent(context, apkFile)
        }
    }

    private fun installWithPackageInstaller(context: Context, apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = ACTION_INSTALL_RESULT
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        ClawLog.bp(TAG, "install_session_committed", apkFile.name)
    }

    private fun installWithViewIntent(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}

/** 安装结果回调 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                ClawLog.bp("InstallReceiver", "success", "update installed")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                ClawLog.bp("InstallReceiver", "pending_user", "launch confirm UI")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { context.startActivity(it) }
            }
            else -> ClawLog.w("InstallReceiver", "fail", "status=$status msg=$msg")
        }
    }
}
