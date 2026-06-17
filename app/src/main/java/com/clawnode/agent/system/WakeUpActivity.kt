package com.clawnode.agent.system

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager

/**
 * 透明唤醒 Activity。
 *
 * 收到 WAKE_UP 指令时由 AccessibilityService 拉起。通过
 * setShowWhenLocked(true) + setTurnScreenOn(true) 点亮并越过锁屏展示，
 * 再申请短时 WakeLock 维持屏幕常亮一小段时间，随后自我销毁，避免常驻。
 */
class WakeUpActivity : Activity() {

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
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // 兜底唤醒：部分设备需要 WakeLock 才能真正点亮
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "ClawNode:wake"
        )
        runCatching { wakeLock.acquire(WAKE_HOLD_MS) }

        // 点亮后短暂保留再退出
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            finish()
        }, WAKE_HOLD_MS)
    }

    companion object {
        private const val WAKE_HOLD_MS = 3_000L
    }
}
