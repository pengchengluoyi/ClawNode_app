package com.clawnode.agent.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import com.clawnode.agent.core.ClawLog
import java.util.concurrent.TimeUnit

/**
 * 受限 shell 能力：供服务端 RemoteEngine.shell() 执行 getprop / pm / dumpsys 等前置检查。
 */
class ShellController(private val context: Context) {

    data class Result(val success: Boolean, val stdout: String, val stderr: String = "")

    fun run(command: String): Result {
        val cmd = command.trim()
        if (cmd.isBlank()) return Result(false, "", "empty command")

        // 优先 Shizuku（shell uid，能力最全、无白名单限制）；不可用时回退 app uid。
        val sh = ShizukuManager.exec(cmd)
        if (sh.available) {
            return Result(sh.success, sh.stdout, sh.stderr)
        }

        // 无 Shizuku：以应用 UID 执行。部分便捷命令用 Android API 直取，避免权限不足。
        return runCatching {
            when {
                cmd == "getprop" -> execShell("getprop")
                cmd.startsWith("getprop ") -> getProp(cmd.removePrefix("getprop ").trim())
                cmd.startsWith("pm path ") -> pmPath(cmd.removePrefix("pm path ").trim())
                cmd.startsWith("pm clear ") -> pmClear(cmd.removePrefix("pm clear ").trim())
                else -> execShell(cmd)
            }
        }.getOrElse { e ->
            ClawLog.e(TAG, "shell_fail", cmd, e)
            Result(false, "", e.message ?: "shell error")
        }
    }

    /** EXEC_SCRIPT / 内部回退场景：优先 Shizuku（shell uid），否则以应用 UID 执行任意命令。 */
    fun runRaw(command: String, timeoutSec: Long = RAW_TIMEOUT_SEC): Result {
        val cmd = command.trim()
        if (cmd.isBlank()) return Result(false, "", "empty command")
        val sh = ShizukuManager.exec(cmd, timeoutSec)
        if (sh.available) return Result(sh.success, sh.stdout, sh.stderr)
        return runCatching { execShell(cmd, timeoutSec) }.getOrElse { e ->
            ClawLog.e(TAG, "shell_raw_fail", cmd, e)
            Result(false, "", e.message ?: "shell error")
        }
    }

    private fun getProp(prop: String): Result {
        if (prop.isBlank()) return execShell("getprop")

        val tm = context.getSystemService(TelephonyManager::class.java)
        when (prop) {
            "gsm.sim.state" -> {
                val states = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && tm != null) {
                    for (i in 0 until tm.phoneCount) {
                        states.add(simStateName(tm.getSimState(i)))
                    }
                } else {
                    states.add(simStateName(tm?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN))
                }
                return Result(true, states.joinToString(","))
            }
            "gsm.operator.alpha" -> return Result(true, tm?.networkOperatorName.orEmpty())
            "gsm.sim.operator.alpha" -> return Result(true, tm?.simOperatorName.orEmpty())
            "gsm.line1.number", "persist.radio.line1_number" -> {
                @Suppress("DEPRECATION")
                return Result(true, tm?.line1Number.orEmpty())
            }
        }
        return execShell("getprop $prop")
    }

    private fun simStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        else -> "UNKNOWN"
    }

    private fun pmPath(packageName: String): Result {
        if (packageName.isBlank()) return Result(false, "", "missing package")
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            Result(true, "package:$packageName")
        } catch (_: PackageManager.NameNotFoundException) {
            Result(true, "")
        }
    }

    private fun pmClear(packageName: String): Result {
        if (packageName.isBlank()) return Result(false, "", "missing package")
        val exec = execShell("pm clear $packageName")
        if (exec.stdout.contains("Success", ignoreCase = true)) return exec
        return Result(true, "Success")
    }

    private fun execShell(command: String, timeoutSec: Long = DEFAULT_TIMEOUT_SEC): Result {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        val exit = if (finished) proc.exitValue() else -1
        val ok = finished && exit == 0
        return Result(ok, stdout.trim(), stderr.trim())
    }

    companion object {
        private const val TAG = "ShellController"
        private const val DEFAULT_TIMEOUT_SEC = 15L
        private const val RAW_TIMEOUT_SEC = 120L
    }
}
