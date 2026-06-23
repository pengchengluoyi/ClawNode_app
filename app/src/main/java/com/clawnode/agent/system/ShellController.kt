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

        return runCatching {
            when {
                cmd == "getprop" -> execShell("getprop")
                cmd.startsWith("getprop ") -> getProp(cmd.removePrefix("getprop ").trim())
                cmd.startsWith("pm path ") -> pmPath(cmd.removePrefix("pm path ").trim())
                cmd.startsWith("pm clear ") -> pmClear(cmd.removePrefix("pm clear ").trim())
                isAllowedExec(cmd) -> execShell(cmd)
                else -> Result(false, "", "command not allowed: $cmd")
            }
        }.getOrElse { e ->
            ClawLog.e(TAG, "shell_fail", cmd, e)
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

    private fun isAllowedExec(cmd: String): Boolean {
        val lower = cmd.lowercase()
        return lower.startsWith("content query") ||
            lower.startsWith("dumpsys iphonesubinfo") ||
            lower.startsWith("dumpsys telephony") ||
            lower.startsWith("ls ") ||
            lower.startsWith("getprop")
    }

    private fun execShell(command: String): Result {
        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        proc.waitFor(15, TimeUnit.SECONDS)
        val ok = proc.exitValue() == 0 || stdout.isNotBlank()
        return Result(ok, stdout.trim(), stderr.trim())
    }

    companion object {
        private const val TAG = "ShellController"
    }
}
