package com.clawnode.agent.system

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import com.clawnode.agent.core.ClawLog

/** 点亮屏幕、越过锁屏，供 WakeUp / LaunchTrampoline 共用。 */
object ScreenWakeHelper {

    private const val TAG = "ScreenWake"

    fun prepareWindow(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        acquireWakeLock(activity)
    }

    /** 解锁后执行 [action]；无锁屏或 API 不足时立即执行。 */
    fun whenUnlocked(activity: Activity, action: () -> Unit) {
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            km != null &&
            km.isKeyguardLocked
        ) {
            km.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        ClawLog.bp(TAG, "keyguard_dismissed", "ok")
                        action()
                    }

                    override fun onDismissError() {
                        ClawLog.w(TAG, "keyguard_dismiss_fail", "proceed anyway")
                        action()
                    }
                },
            )
        } else {
            action()
        }
    }

    fun scheduleFinish(activity: Activity, holdMs: Long, onFinish: (() -> Unit)? = null) {
        Handler(Looper.getMainLooper()).postDelayed({
            onFinish?.invoke()
            activity.finish()
        }, holdMs)
    }

    private fun acquireWakeLock(activity: Activity) {
        val pm = activity.getSystemService(Activity.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "ClawNode:screen",
        )
        runCatching { wakeLock.acquire(4_000L) }
    }
}
