package com.clawnode.agent.system

import android.app.Activity
import android.os.Bundle
import com.clawnode.agent.core.ClawLog

/**
 * 透明唤醒 Activity：点亮屏幕并尝试解除锁屏。
 */
class WakeUpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenWakeHelper.prepareWindow(this)
        ScreenWakeHelper.whenUnlocked(this) {
            ClawLog.bp(TAG, "wake_ready", "screen on")
        }
        ScreenWakeHelper.scheduleFinish(this, WAKE_HOLD_MS)
    }

    companion object {
        private const val TAG = "WakeUpActivity"
        private const val WAKE_HOLD_MS = 3_500L
    }
}
