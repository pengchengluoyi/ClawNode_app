package com.clawnode.agent.system

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
 *
 * 除 Parcelable Intent 外，还用 pkg/cls/action/data 字符串重建，避免 Parcel 丢 component。
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

        val launch = resolveLaunchIntent()
        if (launch != null) {
            runCatching {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
                startActivity(launch)
                ClawLog.bp(
                    TAG,
                    "launch_ok",
                    "action=${launch.action} cmp=${launch.component?.flattenToShortString()}",
                )
            }.onFailure { e ->
                ClawLog.e(TAG, "launch_fail", launch.toString(), e)
            }
        } else {
            ClawLog.w(TAG, "launch_missing", "could not rebuild launch intent")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            finish()
        }, WAKE_HOLD_MS)
    }

    private fun resolveLaunchIntent(): Intent? {
        readParcelableLaunch()?.let { return it }
        val pkg = intent.getStringExtra(EXTRA_PKG)?.trim().orEmpty()
        val cls = intent.getStringExtra(EXTRA_CLS)?.trim().orEmpty()
        val action = intent.getStringExtra(EXTRA_ACTION)?.trim().orEmpty()
        val data = intent.getStringExtra(EXTRA_DATA)?.trim().orEmpty()
        if (pkg.isNotBlank() && cls.isNotBlank()) {
            return Intent().setClassName(pkg, cls)
        }
        if (action.isNotBlank()) {
            return Intent(action).apply {
                if (data.isNotBlank()) this.data = Uri.parse(data)
                if (pkg.isNotBlank()) setPackage(pkg)
            }
        }
        if (pkg.isNotBlank()) {
            return packageManager.getLaunchIntentForPackage(pkg)
        }
        return null
    }

    private fun readParcelableLaunch(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_LAUNCH_INTENT)
        }
    }

    companion object {
        const val EXTRA_LAUNCH_INTENT = "launch_intent"
        const val EXTRA_PKG = "launch_pkg"
        const val EXTRA_CLS = "launch_cls"
        const val EXTRA_ACTION = "launch_action"
        const val EXTRA_DATA = "launch_data"
        private const val TAG = "LaunchTrampoline"
        private const val WAKE_HOLD_MS = 3_500L

        fun build(context: android.content.Context, launchIntent: Intent): Intent =
            Intent(context, LaunchTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_LAUNCH_INTENT, Intent(launchIntent))
                launchIntent.component?.let {
                    putExtra(EXTRA_PKG, it.packageName)
                    putExtra(EXTRA_CLS, it.className)
                }
                launchIntent.action?.let { putExtra(EXTRA_ACTION, it) }
                launchIntent.dataString?.let { putExtra(EXTRA_DATA, it) }
                val pkg = launchIntent.`package`?.trim().orEmpty()
                if (pkg.isNotBlank() && !hasExtra(EXTRA_PKG)) {
                    putExtra(EXTRA_PKG, pkg)
                }
            }
    }
}
