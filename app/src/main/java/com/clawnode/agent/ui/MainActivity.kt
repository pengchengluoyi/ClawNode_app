package com.clawnode.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clawnode.agent.core.ConfigManager
import com.clawnode.agent.core.ConnectionState
import com.clawnode.agent.core.NodeStatusBus
import com.clawnode.agent.databinding.ActivityMainBinding
import com.clawnode.agent.system.SystemController
import com.clawnode.agent.vision.MediaProjectionHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 节点控制台。职责仅限：
 *  - 编辑并持久化 WebSocket URL / Auth Token（经 [ConfigManager]）；
 *  - 展示无障碍 / 屏幕捕获两项权限就绪状态；
 *  - 实时显示来自 [NodeStatusBus] 的 WebSocket 连接状态。
 *
 * 不含任何节点业务逻辑——保存配置后由常驻服务自动热更新连接。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val config by lazy { ConfigManager.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadConfigIntoInputs()
        bindButtons()
        observeConnectionState()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    // ---------------- 配置 ----------------

    private fun loadConfigIntoInputs() {
        lifecycleScope.launch {
            val s = config.settings.first()
            binding.etWsUrl.setText(s.wsUrl)
            binding.etAuthToken.setText(s.authToken)
        }
    }

    private fun bindButtons() {
        binding.btnSaveConfig.setOnClickListener {
            val url = binding.etWsUrl.text?.toString()?.trim().orEmpty()
            val token = binding.etAuthToken.text?.toString()?.trim().orEmpty()
            if (!(url.startsWith("ws://") || url.startsWith("wss://"))) {
                toast("URL 必须以 ws:// 或 wss:// 开头")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                config.save(url, token)
                toast("已保存，连接将自动更新")
            }
        }
        binding.btnAccessibility.setOnClickListener {
            SystemController.openAccessibilitySettings(this)
        }
        binding.btnNotification.setOnClickListener {
            SystemController.openNotificationSettings(this)
        }
        binding.btnWake.setOnClickListener {
            SystemController.wakeUpScreen(this)
        }
    }

    // ---------------- 权限就绪 ----------------

    private fun refreshPermissionStates() {
        val a11yOn = SystemController.isAccessibilityEnabled(this)
        binding.tvAccessibilityState.text =
            if (a11yOn) "🟢 无障碍服务：已就绪" else "🔴 无障碍服务：未开启"

        // 屏幕捕获“就绪”指已持有 MediaProjection 授权（单帧截图本身无需此授权）
        val captureReady = MediaProjectionHolder.hasAuthorization()
        binding.tvCaptureState.text =
            if (captureReady) "🟢 屏幕捕获：已授权" else "🟡 屏幕捕获：未授权（推流时按需申请）"
    }

    // ---------------- 连接状态 ----------------

    private fun observeConnectionState() {
        lifecycleScope.launch {
            // 仅在界面可见时收集，避免后台无谓刷新
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NodeStatusBus.connection.collect { renderConnection(it) }
            }
        }
    }

    private fun renderConnection(state: ConnectionState) {
        binding.tvConnState.text = when (state) {
            is ConnectionState.Idle ->
                "⚪ 未连接（请确认无障碍已开启且已填写有效 URL）"
            is ConnectionState.Connecting ->
                "🟡 连接中…"
            is ConnectionState.Connected ->
                "🟡 已连接，鉴权中…"
            is ConnectionState.Authenticated ->
                "🟢 已连接且已鉴权"
            is ConnectionState.Reconnecting ->
                "🟠 重连中…（第 ${state.attempt} 次，${state.nextDelayMs}ms 后）"
            is ConnectionState.Disconnected ->
                "🔴 已断开：${state.reason}"
            is ConnectionState.AuthFailed ->
                "⛔ 鉴权失败，请检查 Token（${state.reason}）"
        }
    }

    // ---------------- 通知权限 ----------------

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                )
            }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_NOTIF = 0x01
    }
}
