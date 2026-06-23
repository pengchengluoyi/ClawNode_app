package com.clawnode.agent.action

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.clawnode.agent.BuildConfig
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.log.LogUploadManager
import com.clawnode.agent.core.ConfigManager
import com.clawnode.agent.core.NodeStatusBus
import com.clawnode.agent.service.NodeForegroundService
import com.clawnode.agent.pairing.PairingBridge
import com.clawnode.agent.pairing.PairingHttpServer
import com.clawnode.agent.system.AppController
import com.clawnode.agent.system.ClipboardController
import com.clawnode.agent.system.ShellController
import com.clawnode.agent.update.AppUpdateManager
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.system.WakeUpActivity
import com.clawnode.agent.vision.MediaProjectionHolder
import com.clawnode.agent.vision.StreamBridge
import com.clawnode.agent.vision.VisionManager
import com.clawnode.agent.ws.ConnectionKeepAlive
import com.clawnode.agent.ws.NetworkMonitor
import com.clawnode.agent.ws.WsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
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
    private lateinit var shellController: ShellController
    private lateinit var clipboardController: ClipboardController
    private var screenWakeReceiver: BroadcastReceiver? = null
    private var networkMonitor: NetworkMonitor? = null
    @Volatile
    private var foregroundPackageName: String = ""

    /** 远程触发的安装在接下来的这段时间内尝试用无障碍自动确认系统安装弹窗 */
    @Volatile
    private var remoteInstallAutoConfirmUntil: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ClawLog.bp(TAG, "service_connected", "accessibility service ready")
        MediaProjectionHolder.restoreFromDisk(applicationContext)

        // After restart/update, if user previously granted screen capture, auto re-request to refresh token.
        // This makes "authorize once" experience: system dialog may appear once; no need to click in-app buttons again.
        if (!MediaProjectionHolder.hasAuthorization() && MediaProjectionHolder.hasPriorGrant(applicationContext)) {
            try {
                val i = Intent(applicationContext, MediaProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MediaProjectionRequestActivity.EXTRA_MODE, MediaProjectionRequestActivity.MODE_AUTHORIZE)
                applicationContext.startActivity(i)
            } catch (_: Exception) {}
        }

        NodeForegroundService.start(applicationContext)

        gestureController = GestureController(this)
        appController = AppController(applicationContext, this)
        shellController = ShellController(applicationContext)
        clipboardController = ClipboardController(applicationContext)
        val configManager = ConfigManager.get(applicationContext)
        wsManager = WsManager(
            scope,
            discoverServer = { pairedGatewayId ->
                val gateways = com.clawnode.agent.discovery.ServerDiscovery.findGateways(applicationContext)
                com.clawnode.agent.discovery.ServerDiscovery.matchPaired(gateways, pairedGatewayId)?.wsUrl
                    ?: gateways.firstOrNull()?.wsUrl
            },
            onPairConfig = { wsUrl, authToken, gatewayId ->
                configManager.savePairing(wsUrl, authToken, gatewayId)
            },
            onUrlDiscovered = { wsUrl ->
                configManager.saveDiscoveredUrl(wsUrl)
            },
            onUnpair = {
                configManager.clearPairing()
                val meta = buildDeviceMeta()
                com.clawnode.agent.discovery.NodeBeacon.start(
                    applicationContext,
                    sn = configManager.defaultNodeSn,
                    model = meta.model,
                    paired = false,
                )
                PairingHttpServer.start()
            },
        )
        wsManager.setDeviceMeta(buildDeviceMeta())
        PairingBridge.onPairPush = { payload ->
            wsManager.applyPairConfigPush(payload.wsUrl, payload.authToken, payload.gatewayId)
        }

        // Wire self-update progress (for progress bar on server side for ClawNode self-update path)
        AppUpdateManager.setSelfProgressReporter { stage, percent, message, version ->
            wsManager.sendChecked(
                NodeResponse.installProgress(
                    traceId = "self-update",
                    stage = stage,
                    percent = percent,
                    message = message,
                    version = version
                )
            )
        }
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
            },
            onKillApp = { pkg ->
                appController.killApp(pkg).let { it.success to it.message }
            },
            onClearAppCache = { pkg ->
                appController.clearAppCache(pkg).let { it.success to it.message }
            },
            onExportLogs = { minutes ->
                LogUploadManager.uploadWithCurrentSettings(applicationContext, minutes).let {
                    it.success to it.message
                }
            },
            onRunShell = { command ->
                shellController.run(command).let { Triple(it.success, it.stdout, it.stderr) }
            },
            onInstallApk = { traceId, url, fileName ->
                try {
                    wsManager.sendChecked(NodeResponse.installProgress(traceId, NodeResponse.STAGE_DOWNLOADING, 0, "start download"))
                    if (!AppUpdateManager.canInstallPackages(applicationContext)) {
                        wsManager.sendChecked(NodeResponse.installProgress(traceId, NodeResponse.STAGE_FAILED, message = "no install permission"))
                        false to "no REQUEST_INSTALL_PACKAGES permission"
                    } else {
                        val name = fileName?.takeIf { it.isNotBlank() } ?: "remote_install.apk"
                        val file = AppUpdateManager.downloadApk(applicationContext, url, name)
                        wsManager.sendChecked(NodeResponse.installProgress(traceId, NodeResponse.STAGE_DOWNLOADED, 100, "download complete"))
                        wsManager.sendChecked(NodeResponse.installProgress(traceId, NodeResponse.STAGE_INSTALLING, message = "starting install"))
                        // 标记远程安装，onAccessibilityEvent 会尝试自动点击系统安装确认对话框
                        remoteInstallAutoConfirmUntil = System.currentTimeMillis() + 120_000
                        AppUpdateManager.installApk(applicationContext, file)
                        // The actual "pending_user" will be logged in InstallResultReceiver; we can send a stage here too
                        wsManager.sendChecked(NodeResponse.installProgress(traceId, NodeResponse.STAGE_AWAITING_CONFIRM, message = "awaiting user install confirmation"))
                        true to "install initiated: ${file.name}"
                    }
                } catch (e: Exception) {
                    wsManager.sendChecked(NodeResponse.installProgress(traceId, NodeResponse.STAGE_FAILED, message = e.message))
                    false to (e.message ?: "install failed")
                }
            },
            onSetClipboard = { text ->
                clipboardController.setText(text).let { it.success to it.message }
            },
        )

        wsManager.incomingCommands
            .onEach { dispatcher.dispatch(it) }
            .launchIn(scope)

        ConfigManager.get(applicationContext).settings
            .onEach { wsManager.applySettings(it) }
            .launchIn(scope)

        registerScreenWakeReceiver()
        registerNetworkMonitor()
    }

    /** 供 NodeForegroundService 后台看门狗调用 */
    fun reconnectWebSocketIfNeeded() {
        if (::wsManager.isInitialized) wsManager.reconnectIfNeeded()
    }

    private fun registerScreenWakeReceiver() {
        if (screenWakeReceiver != null) return
        screenWakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> wsManager.reconnectIfNeeded()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenWakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenWakeReceiver, filter)
        }
    }

    private fun unregisterScreenWakeReceiver() {
        screenWakeReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenWakeReceiver = null
    }

    private fun registerNetworkMonitor() {
        if (networkMonitor != null) return
        networkMonitor = NetworkMonitor(applicationContext) { hasNetwork ->
            if (::wsManager.isInitialized) wsManager.onNetworkChanged(hasNetwork)
        }.also { it.start() }
    }

    private fun unregisterNetworkMonitor() {
        networkMonitor?.let { runCatching { it.stop() } }
        networkMonitor = null
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
        return WsManager.DeviceMeta(
            model = model,
            osVersion = osVersion,
            resolution = resolution,
            appVersion = BuildConfig.VERSION_NAME,
        )
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()?.trim().orEmpty()
        if (pkg.isNotBlank()) {
            foregroundPackageName = pkg
        }

        // 远程安装或自更新自动确认逻辑：当系统安装器弹窗出现时，尝试自动点“安装”
        val now = System.currentTimeMillis()
        val wantConfirm = remoteInstallAutoConfirmUntil > now ||
            AppUpdateManager.requestAutoConfirmUntil > now
        if (wantConfirm && isInstallerPackage(pkg)) {
            tryAutoConfirmInstall(event)
        }
    }

    private fun isInstallerPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("packageinstaller") || p.contains("installer") || p.contains("package_installer")
    }

    private fun tryAutoConfirmInstall(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return
        // 常见肯定按钮文案
        val positiveTexts = listOf("安装", "确定安装", "install", "确认", "continue", "确认安装")
        val nodes = root.findAccessibilityNodeInfosByText("")
            .filter { node ->
                val text = (node.text ?: node.contentDescription ?: "").toString().trim().lowercase()
                positiveTexts.any { it in text } && node.isClickable && node.isEnabled
            }

        nodes.firstOrNull()?.let { node ->
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val cx = bounds.centerX().toFloat()
                val cy = bounds.centerY().toFloat()
                ClawLog.bp(TAG, "auto_install_click", "tapping installer button at ($cx,$cy)")
                // 必须异步调用 suspend 的 tap
                scope.launch {
                    gestureController.tap(cx, cy, 80)
                }
                remoteInstallAutoConfirmUntil = 0 // 只试一次
            }
        }
    }

    override fun onInterrupt() {
        ClawLog.w(TAG, "onInterrupt", "accessibility interrupted")
    }

    fun currentForegroundPackage(): String = foregroundPackageName.trim()

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
        unregisterScreenWakeReceiver()
        unregisterNetworkMonitor()
        ConnectionKeepAlive.release()
        if (instance === this) instance = null
        NodeForegroundService.stop(applicationContext)
        StreamBridge.wsRef = null
        if (::visionManager.isInitialized) {
            runCatching { visionManager.stopStream(null) }
        }
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
