package com.clawnode.agent.system

import android.content.pm.PackageManager
import com.clawnode.agent.core.ClawLog
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 提权通道：以 shell(uid=2000) 权限执行命令，免 root。
 *
 * 用户通过 Shizuku app + 无线调试（或 root）一次性激活后，本管理器即可执行
 * am / pm / input / settings 等普通 app 无权调用的命令，从而：
 * - RUN_SHELL 全开
 * - pm install 静默安装（自动更新无需人工确认）
 * - ime enable/set 自动切换到 ClawNode 输入法
 *
 * 不可用（未安装 / 未授权 / 未启动）时 [isAvailable] 返回 false，调用方自动回退。
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val REQUEST_CODE = 0xC1A7

    data class ExecResult(val available: Boolean, val success: Boolean, val stdout: String, val stderr: String) {
        companion object {
            val UNAVAILABLE = ExecResult(available = false, success = false, stdout = "", stderr = "shizuku unavailable")
        }
    }

    /** binder 是否连上（Shizuku 服务在运行）。 */
    private fun pingOk(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun granted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 完整可用：binder 连上且已授权。 */
    fun isAvailable(): Boolean = pingOk() && granted()

    /** 服务在运行但尚未授权（用于 UI 决定是否弹授权）。 */
    fun isRunningButUngranted(): Boolean = pingOk() && !granted()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        ClawLog.bp(TAG, "perm_result", "code=$requestCode granted=${grantResult == PackageManager.PERMISSION_GRANTED}")
    }

    @Volatile
    private var listenerRegistered = false

    /** 未授权时发起 Shizuku 权限请求（需服务在运行）。 */
    fun requestPermissionIfNeeded() {
        if (!pingOk()) {
            ClawLog.w(TAG, "request_skip", "shizuku binder not alive")
            return
        }
        if (granted()) return
        runCatching {
            if (!listenerRegistered) {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                listenerRegistered = true
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                ClawLog.w(TAG, "request_rationale", "user previously denied")
            }
            Shizuku.requestPermission(REQUEST_CODE)
            ClawLog.bp(TAG, "request_sent", "code=$REQUEST_CODE")
        }.onFailure { ClawLog.w(TAG, "request_fail", it.message ?: "") }
    }

    /**
     * 以 shell 权限执行命令。不可用时返回 [ExecResult.UNAVAILABLE]（available=false），
     * 调用方据此回退到 app uid / 无障碍方案。
     */
    fun exec(command: String, timeoutSec: Long = 30L): ExecResult {
        if (!isAvailable()) return ExecResult.UNAVAILABLE
        return runCatching {
            val process = newProcess(arrayOf("sh", "-c", command))
                ?: return ExecResult.UNAVAILABLE
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = readStreamAsync(process.inputStream, stdout)
            val errThread = readStreamAsync(process.errorStream, stderr)
            val finished = waitForCompat(process, timeoutSec)
            outThread.join(1000)
            errThread.join(1000)
            val exit = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
            if (!finished) runCatching { process.destroy() }
            ExecResult(
                available = true,
                success = finished && exit == 0,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim(),
            )
        }.getOrElse { e ->
            ClawLog.e(TAG, "exec_fail", command.take(120), e)
            ExecResult(available = true, success = false, stdout = "", stderr = e.message ?: "exec error")
        }
    }

    /**
     * Shizuku.newProcess 是 @hide API，rikka api 未公开导出，故反射调用。
     * 返回的 Process 由 Shizuku server（shell uid）派生。
     */
    private fun newProcess(cmd: Array<String>): Process? {
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            m.invoke(null, cmd, null, null) as? Process
        }.getOrElse { e ->
            ClawLog.e(TAG, "new_process_fail", "reflection", e)
            null
        }
    }

    private fun readStreamAsync(stream: java.io.InputStream, sink: StringBuilder): Thread {
        val t = Thread {
            runCatching {
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        sink.append(line).append('\n')
                        line = reader.readLine()
                    }
                }
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun waitForCompat(process: Process, timeoutSec: Long): Boolean {
        return runCatching {
            process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrElse {
            // 某些 ROM 的 RemoteProcess 不支持带超时 waitFor，退化为阻塞等待
            runCatching { process.waitFor(); true }.getOrDefault(false)
        }
    }

    // ---- 便捷能力：静默安装 / 输入法切换 ----

    /** pm install 静默安装（shell uid 有权限，不弹确认）。apkPath 需对 shell 可读。 */
    fun installApk(apkPath: String): ExecResult {
        return exec("pm install -r -d \"$apkPath\"", timeoutSec = 120L)
    }

    private fun imeId(packageName: String): String = "$packageName/.ime.ClawImeService"

    fun enableClawIme(packageName: String): Boolean =
        exec("ime enable ${imeId(packageName)}").success

    fun setClawImeDefault(packageName: String): Boolean =
        exec("ime set ${imeId(packageName)}").success

    /** 读取当前默认输入法 id（用于输入完成后还原）。 */
    fun currentDefaultIme(): String? {
        val r = exec("settings get secure default_input_method")
        return if (r.success) r.stdout.trim().takeIf { it.isNotBlank() && it != "null" } else null
    }

    fun setDefaultIme(imeId: String): Boolean =
        exec("ime set $imeId").success

    /** 确保 ClawNode IME 已启用并设为默认；返回切换前的默认 ime（便于还原），失败返回 null。 */
    fun ensureClawImeActive(packageName: String): String? {
        if (!isAvailable()) return null
        val previous = currentDefaultIme()
        val target = imeId(packageName)
        if (previous == target) return target // 已是默认
        enableClawIme(packageName)
        setClawImeDefault(packageName)
        ClawLog.bp(TAG, "ime_switch", "prev=$previous -> $target")
        return previous
    }
}
