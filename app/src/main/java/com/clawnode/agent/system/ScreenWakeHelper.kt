package com.clawnode.agent.system

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import com.clawnode.agent.core.ClawLog

/** 点亮屏幕、越过锁屏，供 WakeUp / LaunchTrampoline 共用。 */
object ScreenWakeHelper {

    private const val TAG = "ScreenWake"

    /** keyguard dismiss 回调不触发时的兜底延时（HyperOS 上回调常缺失）。 */
    private const val KEYGUARD_FALLBACK_MS = 2_600L

    /** dismiss 被取消时的重试间隔与最大次数（HyperOS 首次常因无焦点被取消）。 */
    private const val KEYGUARD_RETRY_MS = 450L
    private const val KEYGUARD_MAX_RETRY = 4

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

    /**
     * 解锁后执行 [action]；无锁屏或 API 不足时立即执行。
     *
     * ⚠️ HyperOS / 部分国产 ROM 上，requestDismissKeyguard 的回调
     * （onDismissSucceeded / onDismissError / onDismissCancelled）可能根本不触发，
     * 导致 action 永远不执行（表现为打开 app / 设置页失败，前台停在原处）。
     * 因此这里：① 三种回调都触发 action；② 用 once 守卫防重复；
     * ③ 加延时兜底——回调不来也照样执行（屏幕已被 setShowWhenLocked+setTurnScreenOn 点亮）。
     */
    fun whenUnlocked(activity: Activity, action: () -> Unit) {
        val done = AtomicBoolean(false)
        val run = {
            if (done.compareAndSet(false, true)) {
                runCatching { action() }
                    .onFailure { ClawLog.w(TAG, "wake_action_fail", it.message ?: "") }
            }
        }
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            km != null &&
            km.isKeyguardLocked
        ) {
            val handler = Handler(Looper.getMainLooper())
            // HyperOS 在 Activity 尚未获得窗口焦点时会取消 dismiss 请求（日志 keyguard_dismiss_cancel），
            // 表现为第一次只亮屏不解锁、需再唤醒一次。这里在取消时自动重试，直到解锁或重试用尽。
            fun requestDismiss(attempt: Int) {
                runCatching {
                    km.requestDismissKeyguard(
                        activity,
                        object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() {
                                ClawLog.bp(TAG, "keyguard_dismissed", "ok attempt=$attempt")
                                run()
                            }

                            override fun onDismissError() {
                                ClawLog.w(TAG, "keyguard_dismiss_fail", "proceed anyway")
                                run()
                            }

                            override fun onDismissCancelled() {
                                if (attempt < KEYGUARD_MAX_RETRY && km.isKeyguardLocked) {
                                    ClawLog.w(TAG, "keyguard_dismiss_cancel", "retry attempt=${attempt + 1}")
                                    handler.postDelayed({ requestDismiss(attempt + 1) }, KEYGUARD_RETRY_MS)
                                } else {
                                    ClawLog.w(TAG, "keyguard_dismiss_cancel", "give up, proceed anyway")
                                    run()
                                }
                            }
                        },
                    )
                }.onFailure {
                    ClawLog.w(TAG, "keyguard_request_fail", it.message ?: "")
                    run()
                }
            }
            requestDismiss(1)
            // 兜底：所有回调都不触发时（HyperOS 偶见）仍执行 action，避免动作永久卡死
            handler.postDelayed({ run() }, KEYGUARD_FALLBACK_MS)
        } else {
            run()
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
