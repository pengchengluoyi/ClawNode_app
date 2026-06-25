package com.clawnode.agent.system

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import com.clawnode.agent.core.ClawLog

/**
 * 前台跳板：在 Activity 上下文里 startActivity，绕过 MIUI 等对后台启动的限制。
 * 同时承担点亮/解锁屏幕（与 WakeUpActivity 相同逻辑）。
 */
class LaunchTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "ClawNode:launch",
        )
        runCatching { wakeLock.acquire(WAKE_HOLD_MS) }

        val launch = readLaunchIntent()
        if (launch != null) {
            runCatching {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
                startActivity(launch)
                ClawLog.bp(TAG, "launch_ok", "action=${launch.action} pkg=${launch.`package`}")
            }.onFailure { e ->
                ClawLog.e(TAG, "launch_fail", launch.toString(), e)
            }
        } else {
            ClawLog.w(TAG, "launch_missing", "no EXTRA_LAUNCH_INTENT")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            finish()
        }, WAKE_HOLD_MS)
    }

    private fun readLaunchIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT)
        }
    }

    companion object {
        const val EXTRA_LAUNCH_INTENT = "launch_intent"
        private const val TAG = "LaunchTrampoline"
        private const val WAKE_HOLD_MS = 2_500L

        fun build(context: android.content.Context, launchIntent: Intent): Intent =
            Intent(context, LaunchTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_LAUNCH_INTENT, launchIntent)
            }
    }
}
