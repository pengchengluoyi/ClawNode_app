package com.clawnode.agent.action

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.ConfigManager
import com.clawnode.agent.core.NodeStatusBus
import com.clawnode.agent.service.NodeForegroundService
import com.clawnode.agent.system.AppController
import com.clawnode.agent.system.WakeUpActivity
import com.clawnode.agent.vision.StreamBridge
import com.clawnode.agent.vision.VisionManager
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 节点核心载体（AccessibilityService）。
 */
class ActionExecutorService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private lateinit var wsManager: WsManager
    private lateinit var gestureController: GestureController
    private lateinit var visionManager: VisionManager
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var appController: AppController

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ClawLog.bp(TAG, "service_connected", "accessibility service ready")

        NodeForegroundService.start(applicationContext)

        gestureController = GestureController(this)
        appController = AppController(applicationContext, this)
        val configManager = ConfigManager.get(applicationContext)
        wsManager = WsManager(scope, discoverServer = {
            val current = configManager.settings.first()
            if (current.pairedGatewayId.isBlank()) {
                ClawLog.w(TAG, "discovery_skip", "no paired gateway")
                return@WsManager null
            }
            val gateways = com.clawnode.agent.discovery.ServerDiscovery.findGateways(applicationContext)
            val matched = com.clawnode.agent.discovery.ServerDiscovery.matchPaired(
                gateways,
                current.pairedGatewayId
            ) ?: return@WsManager null
            if (matched.wsUrl != current.wsUrl) {
                configManager.savePairing(matched.wsUrl, current.authToken, current.pairedGatewayId)
            }
            matched.wsUrl
        })
        wsManager.setDeviceMeta(buildDeviceMeta())
        val meta = buildDeviceMeta()
        com.clawnode.agent.discovery.NodeBeacon.start(
            applicationContext,
            sn = configManager.defaultNodeSn,
            model = meta.model
        )
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
            onLaunchApp = { pkg, activity ->
                appController.launchApp(pkg, activity).let { it.success to it.message }
            },
            onCloseApp = { pkg ->
                appController.closeApp(pkg).let { it.success to it.message }
            }
        )

        wsManager.incomingCommands
            .onEach { dispatcher.dispatch(it) }
            .launchIn(scope)

        ConfigManager.get(applicationContext).settings
            .onEach { wsManager.applySettings(it) }
            .launchIn(scope)
    }

    private fun launchWakeUp() {
        ClawLog.bp(TAG, "wake_up", "launching WakeUpActivity")
        val intent = Intent(this, WakeUpActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun performKeyEvent(key: String): Boolean {
        val action = when (key.lowercase()) {
            "back", "4" -> GLOBAL_ACTION_BACK
            "home", "3" -> GLOBAL_ACTION_HOME
            "recents", "recent", "187" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        val ok = performGlobalAction(action)
        ClawLog.bp(TAG, "key_event", "key=$key ok=$ok")
        return ok
    }

    private fun buildDeviceMeta(): WsManager.DeviceMeta {
        val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val osVersion = "Android ${android.os.Build.VERSION.RELEASE}"
        val dm = resources.displayMetrics
        val resolution = "${dm.widthPixels}x${dm.heightPixels}"
        return WsManager.DeviceMeta(model = model, osVersion = osVersion, resolution = resolution)
    }

    suspend fun captureScreenshotBitmap(): Result<Bitmap> =
        suspendCancellableCoroutine { cont ->
            ClawLog.bp(TAG, "takeScreenshot_call", "display=DEFAULT")
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer: HardwareBuffer = result.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            buffer.close()
                        }
                        if (!cont.isActive) {
                            bitmap?.recycle()
                            return
                        }
                        if (bitmap == null) {
                            ClawLog.w(TAG, "takeScreenshot_fail", "wrapHardwareBuffer null")
                            cont.resume(Result.failure(ScreenshotError("wrapHardwareBuffer returned null")))
                        } else {
                            ClawLog.bp(TAG, "takeScreenshot_ok", "size=${bitmap.width}x${bitmap.height}")
                            cont.resume(Result.success(bitmap))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val name = screenshotErrorName(errorCode)
                        ClawLog.w(TAG, "takeScreenshot_fail", "code=$errorCode $name")
                        if (cont.isActive) {
                            cont.resume(Result.failure(ScreenshotError("takeScreenshot failed code=$errorCode ($name)")))
                        }
                    }
                }
            )
        }

    private fun screenshotErrorName(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TOO_SHORT"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "INVALID_WINDOW"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW"
        else -> "UNKNOWN"
    }

    class ScreenshotError(message: String) : Exception(message)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }

    override fun onInterrupt() {
        ClawLog.w(TAG, "onInterrupt", "accessibility interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ClawLog.w(TAG, "onUnbind", "accessibility unbind")
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ClawLog.bp(TAG, "onDestroy", "accessibility destroy")
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        ClawLog.bp(TAG, "teardown", "releasing node resources")
        if (instance === this) instance = null
        NodeForegroundService.stop(applicationContext)
        StreamBridge.wsRef = null
        if (::visionManager.isInitialized) {
            runCatching { visionManager.stopStream(null) }
        }
        com.clawnode.agent.discovery.NodeBeacon.stop(applicationContext)
        if (::wsManager.isInitialized) {
            runCatching { wsManager.stop() }
        }
        NodeStatusBus.reset()
        runCatching { screenshotExecutor.shutdownNow() }
        scope.cancel()
    }

    companion object {
        private const val TAG = "ActionExecutor"

        @Volatile
        var instance: ActionExecutorService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
