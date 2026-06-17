package com.clawnode.agent.action

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.clawnode.agent.core.ConfigManager
import com.clawnode.agent.core.NodeStatusBus
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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

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

    // takeScreenshot 的回调执行线程（单线程足够，截图本身串行）
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private lateinit var wsManager: WsManager
    private lateinit var gestureController: GestureController
    private lateinit var visionManager: VisionManager
    private lateinit var dispatcher: CommandDispatcher

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        gestureController = GestureController(this)
        wsManager = WsManager(scope)
        wsManager.setDeviceMeta(buildDeviceMeta())
        // 暴露给推流前台服务用于回传帧
        StreamBridge.wsRef = wsManager
        visionManager = VisionManager(
            context = applicationContext,
            accessibilityService = this,
            ws = wsManager
        )
        dispatcher = CommandDispatcher(
            scope = scope,
            gesture = gestureController,
            vision = visionManager,
            ws = wsManager,
            onWakeUp = ::launchWakeUp,
            onKeyEvent = ::performKeyEvent,
            onStopApp = ::stopApp
        )

        // 把上行指令流接到分发器
        wsManager.incomingCommands
            .onEach { dispatcher.dispatch(it) }
            .launchIn(scope)

        // 观察持久化配置：服务已连接（能进到这里即代表无障碍就绪），
        // 由 WsManager.applySettings 完成“URL 有效才连接”的门禁判断。
        // 配置变更（保存新 URL/token）会自动推送并热更新连接。
        ConfigManager.get(applicationContext).settings
            .onEach { wsManager.applySettings(it) }
            .launchIn(scope)
    }

    private fun launchWakeUp() {
        val intent = Intent(this, WakeUpActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    /** KEY_EVENT：用无障碍全局动作落地返回键/Home/最近任务。返回是否支持。 */
    private fun performKeyEvent(key: String): Boolean {
        val action = when (key.lowercase()) {
            "back", "4" -> GLOBAL_ACTION_BACK
            "home", "3" -> GLOBAL_ACTION_HOME
            "recents", "recent", "187" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    /**
     * STOP_APP：无障碍服务无 root、无法 force-stop 任意应用。
     * 这里只做诚实降级——返回 false，让上层知道未生效（不假装成功）。
     */
    private fun stopApp(pkg: String): Boolean {
        android.util.Log.w("ActionExecutor", "stop_app($pkg) unsupported: no root/force-stop permission")
        return false
    }

    /** 收集设备元信息用于注册帧（型号 / 系统版本 / 分辨率）。 */
    private fun buildDeviceMeta(): WsManager.DeviceMeta {
        val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val osVersion = "Android ${android.os.Build.VERSION.RELEASE}"
        val dm = resources.displayMetrics
        val resolution = "${dm.widthPixels}x${dm.heightPixels}"
        return WsManager.DeviceMeta(model = model, osVersion = osVersion, resolution = resolution)
    }

    /**
     * 以挂起函数形式封装 [takeScreenshot]（API 30+）。
     *
     * 把系统的回调式 API 桥接为协程，VisionManager 可直接 `await`。
     * 仅负责拿到软件位图；JPEG 压缩/编码由调用方在 IO 线程完成，本函数不阻塞。
     *
     * @return 成功返回可被 compress 的 ARGB_8888 软件位图；失败返回 [ScreenshotError]。
     */
    suspend fun captureScreenshotBitmap(): Result<Bitmap> =
        suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer: HardwareBuffer = result.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                // 转软件位图，硬件位图无法直接 JPEG 压缩
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            buffer.close()
                        }
                        if (!cont.isActive) {
                            bitmap?.recycle(); return
                        }
                        if (bitmap == null) {
                            cont.resume(Result.failure(ScreenshotError("wrapHardwareBuffer returned null")))
                        } else {
                            cont.resume(Result.success(bitmap))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(Result.failure(ScreenshotError("takeScreenshot failed code=$errorCode")))
                        }
                    }
                }
            )
        }

    class ScreenshotError(message: String) : Exception(message)

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
        NodeStatusBus.reset()
        runCatching { screenshotExecutor.shutdownNow() }
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
