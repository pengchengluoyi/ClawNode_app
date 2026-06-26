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
import com.clawnode.agent.model.NodeResponse
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

    /** 取 manifest / releases 重定向 / API 中版本号最高且 APK 可下载的版本 */
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
        val sorted = candidates.sortedByDescending { compareVersions(it.version, "0") }
        for (candidate in sorted) {
            val url = candidate.downloadUrl
            if (url.isNullOrBlank()) continue
            if (isDownloadAvailable(url)) {
                ClawLog.bp(TAG, "resolve_pick", "v${candidate.version} source=${candidate.source}")
                return candidate
            }
            ClawLog.w(TAG, "resolve_skip", "v${candidate.version} url unavailable source=${candidate.source}")
        }
        throw IllegalStateException("无法获取可下载的最新版本（Release 可能正在发布中）")
    }

    /** HEAD 探测：manifest 可能先于 GitHub Release 资产就绪，避免 404 */
    private fun isDownloadAvailable(url: String): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", userAgent())
                .build()
            client.newCall(request).execute().use { response ->
                response.code in 200..399
            }
        }.getOrElse { e ->
            ClawLog.w(TAG, "url_probe_fail", "${e.message} url=$url")
            false
        }
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
            reportSelf(NodeResponse.STAGE_DETECTED, 0, "self-update check started", BuildConfig.VERSION_NAME)

            // Shizuku 可静默安装，无需「安装未知应用」权限；二者皆无才跳过。
            if (!com.clawnode.agent.system.ShizukuManager.isAvailable() && !canInstallPackages(context)) {
                ClawLog.bp(TAG, "auto_skip", "no install permission and no shizuku")
                reportSelf(NodeResponse.STAGE_FAILED, message = "no install permission")
                return false
            }
            // 简单节流：如果刚刚触发过一次自动更新，短时间内不再重复弹系统安装确认
            val now = System.currentTimeMillis()
            if (now - lastAutoUpdateAttempt < 90_000) {
                ClawLog.bp(TAG, "auto_skip", "recent attempt, skip to avoid repeated dialogs")
                return false
            }

            val info = checkForUpdate()
            if (!info.hasUpdate || info.downloadUrl.isNullOrBlank()) {
                reportSelf(NodeResponse.STAGE_SUCCESS, 100, "already latest")
                return false
            }

            lastAutoUpdateAttempt = now
            reportSelf(NodeResponse.STAGE_DOWNLOADING, 5, "start download", info.latestVersion)
            ClawLog.bp(TAG, "auto_download", "v${info.latestVersion}")
            val file = downloadApk(context, info.downloadUrl, info.assetName ?: "ClawNode.apk")
            reportSelf(NodeResponse.STAGE_DOWNLOADED, 100, "download complete", info.latestVersion)

            reportSelf(NodeResponse.STAGE_INSTALLING, message = "installing", version = info.latestVersion)
            installApk(context, file)
            reportSelf(NodeResponse.STAGE_AWAITING_CONFIRM, message = "awaiting user confirmation", version = info.latestVersion)
            true
        }.getOrElse { e ->
            ClawLog.w(TAG, "auto_update_fail", e.message ?: "")
            reportSelf(NodeResponse.STAGE_FAILED, message = e.message)
            false
        }
    }

    @Volatile
    private var lastAutoUpdateAttempt: Long = 0

    /** Optional hook for self-update to report INSTALL_PROGRESS (used by ClawNodeApp / MainActivity to forward to WS) */
    private var selfProgressReporter: ((stage: String, percent: Int?, message: String?, version: String?) -> Unit)? = null

    fun setSelfProgressReporter(reporter: (stage: String, percent: Int?, message: String?, version: String?) -> Unit) {
        selfProgressReporter = reporter
    }

    private fun reportSelf(stage: String, percent: Int? = null, message: String? = null, version: String? = null) {
        try {
            selfProgressReporter?.invoke(stage, percent, message, version)
        } catch (_: Exception) {}
        // Also log for visibility
        ClawLog.bp(TAG, "self_progress", "stage=$stage percent=$percent ver=$version msg=$message")
    }

    suspend fun downloadApk(context: Context, url: String, fileName: String): File =
        withContext(Dispatchers.IO) {
            reportSelf(NodeResponse.STAGE_DOWNLOADING, 10, "downloading apk")
            runCatching { downloadApkOnce(context, url, fileName) }
                .getOrElse { first ->
                    if (first !is IllegalStateException || !first.message.orEmpty().contains("404")) throw first
                    ClawLog.w(TAG, "download_retry", "404 on $url, re-resolving release")
                    val fallback = resolveLatestRelease()
                    val retryUrl = fallback.downloadUrl
                        ?: throw IllegalStateException("无可用下载地址")
                    if (retryUrl == url) throw first
                    downloadApkOnce(context, retryUrl, fallback.assetName ?: fileName)
                }
        }

    private fun downloadApkOnce(context: Context, url: String, fileName: String): File {
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
        return outFile
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
        // 优先 Shizuku 静默安装（shell uid，pm install 不弹确认，真正无人值守）。
        if (tryShizukuSilentInstall(context, apkFile)) {
            ClawLog.bp(TAG, "install_silent_ok", "via shizuku pm install")
            reportSelf(NodeResponse.STAGE_SUCCESS, 100, "installed silently via shizuku")
            return
        }
        // 无论来源（server 下发还是自检），都请求在接下来一段时间内用无障碍自动确认安装弹窗
        requestAutoConfirmUntil = System.currentTimeMillis() + 120_000
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

    /**
     * Shizuku 静默安装：把 apk 复制到 shell 可读路径后 `pm install -r -d`。
     * 不可用 / 失败返回 false，调用方回退 PackageInstaller。
     */
    private fun tryShizukuSilentInstall(context: Context, apkFile: File): Boolean {
        if (!com.clawnode.agent.system.ShizukuManager.isAvailable()) return false
        return runCatching {
            // cacheDir 对 shell uid 通常不可读，复制到 /data/local/tmp（shell 可读写）。
            val tmpPath = "/data/local/tmp/clawnode_update.apk"
            val cp = com.clawnode.agent.system.ShizukuManager.exec(
                "cp \"${apkFile.absolutePath}\" $tmpPath && chmod 644 $tmpPath",
            )
            val target = if (cp.success) tmpPath else apkFile.absolutePath
            val r = com.clawnode.agent.system.ShizukuManager.installApk(target)
            com.clawnode.agent.system.ShizukuManager.exec("rm -f $tmpPath")
            val ok = r.success || r.stdout.contains("Success", ignoreCase = true)
            if (!ok) ClawLog.w(TAG, "shizuku_install_fail", "out=${r.stdout} err=${r.stderr}")
            ok
        }.getOrElse {
            ClawLog.w(TAG, "shizuku_install_error", it.message ?: "")
            false
        }
    }

    /** 供无障碍服务读取：最近一次安装请求希望在窗口期内自动点确认按钮 */
    @Volatile
    var requestAutoConfirmUntil: Long = 0
        private set

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
            PackageInstaller.STATUS_SUCCESS -> {
                ClawLog.bp("InstallReceiver", "success", "update installed")
                // For self-update path, report final stage so frontend can show "restarted / success"
                try {
                    // The selfProgressReporter may be set; call with a best-effort
                    // (in practice after restart the new process will set its own reporter on connect)
                } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                ClawLog.bp("InstallReceiver", "pending_user", "launch confirm UI")
                // Report awaiting confirm for progress UI (self-update will use "self-update" trace on next start)
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
