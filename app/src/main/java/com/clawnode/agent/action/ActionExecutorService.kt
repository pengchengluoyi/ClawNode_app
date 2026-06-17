package com.clawnode.agent.action

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.clawnode.agent.system.WakeUpActivity
import com.clawnode.agent.vision.StreamBridge
import com.clawnode.agent.vision.VisionManager
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers

/**
 * 节点核心载体。
 *
 * 选择 AccessibilityService 作为常驻枢纽的原因：
 *  - 其实例由系统创建并保活，生命周期在本应用中最长；
 *  - 它本身就是手势注入与 takeScreenshot 的能力提供者。
 *
 * 这里完成依赖装配（WsManager / Vision / Dispatcher）与生命周期管理，
 * 业务逻辑全部下放到各模块，本类只做编排。
 */
class ActionExecutorService : AccessibilityService() {

    // 服务级协程作用域；SupervisorJob 保证单条指令失败不拖垮整体
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var wsManager: WsManager
    private lateinit var gestureController: GestureController
    private lateinit var visionManager: VisionManager
    private lateinit var dispatcher: CommandDispatcher

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        gestureController = GestureController(this)
        wsManager = WsManager(scope)
        // 暴露给推流前台服务用于回传帧
        StreamBridge.wsRef = wsManager
        visionManager = VisionManager(
            context = applicationContext,
            accessibilityService = this,
            scope = scope,
            ws = wsManager
        )
        dispatcher = CommandDispatcher(
            scope = scope,
            gesture = gestureController,
            vision = visionManager,
            ws = wsManager,
            onWakeUp = ::launchWakeUp
        )

        // 把上行指令流接到分发器
        wsManager.incomingCommands
            .onEach { dispatcher.dispatch(it) }
            .launchIn(scope)

        wsManager.start()
    }

    private fun launchWakeUp() {
        val intent = Intent(this, WakeUpActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    // VLA 基于像素决策，不消费节点事件；保留空实现
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }

    override fun onInterrupt() { /* no-op */ }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        StreamBridge.wsRef = null
        runCatching { visionManager.stopStream(null) }
        runCatching { wsManager.stop() }
        scope.cancel()
    }

    companion object {
        /** 供 MediaProjection 回调线程等外部拿到 service 引用以调用 takeScreenshot */
        @Volatile
        var instance: ActionExecutorService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
