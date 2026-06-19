package com.clawnode.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.ConfigManager
import com.clawnode.agent.core.ConnectionState
import com.clawnode.agent.core.NodeStatusBus
import com.clawnode.agent.databinding.ActivityMainBinding
import com.clawnode.agent.system.MediaProjectionRequestActivity
import com.clawnode.agent.system.SystemController
import com.clawnode.agent.vision.MediaProjectionHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val config by lazy { ConfigManager.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClawLog.bp(TAG, "onCreate", "main activity")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadConfigIntoInputs()
        bindButtons()
        observeConnectionState()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        ClawLog.bp(TAG, "onResume", "refresh permission states")
        refreshPermissionStates()
    }

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
                ClawLog.bp(TAG, "config_saved", "url=$url")
                toast("已保存，连接将自动更新")
            }
        }
        binding.btnAccessibility.setOnClickListener {
            SystemController.openAccessibilitySettings(this)
        }
        binding.btnNotification.setOnClickListener {
            SystemController.openNotificationSettings(this)
        }
        binding.btnBattery.setOnClickListener {
            SystemController.requestIgnoreBatteryOptimizations(this)
        }
        binding.btnScreenCapture.setOnClickListener {
            val intent = Intent(this, MediaProjectionRequestActivity::class.java)
                .putExtra(MediaProjectionRequestActivity.EXTRA_MODE, MediaProjectionRequestActivity.MODE_AUTHORIZE)
            startActivity(intent)
        }
        binding.btnWake.setOnClickListener {
            SystemController.wakeUpScreen(this)
        }
    }

    private fun refreshPermissionStates() {
        val a11yOn = SystemController.isAccessibilityEnabled(this)
        binding.tvAccessibilityState.text =
            if (a11yOn) "🟢 无障碍服务：已就绪" else "🔴 无障碍服务：未开启"

        val captureReady = MediaProjectionHolder.hasAuthorization()
        binding.tvCaptureState.text =
            if (captureReady) "🟢 屏幕捕获：已授权（支持后台截图）"
            else "🟡 屏幕捕获：未授权（后台截图需先授权）"

        val batteryOk = SystemController.isBatteryOptimizationIgnored(this)
        binding.tvBatteryState.text =
            if (batteryOk) "🟢 电池优化：已忽略（推荐）"
            else "🔴 电池优化：未忽略（后台可能被杀死）"
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
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
        private const val TAG = "MainActivity"
        private const val REQ_NOTIF = 0x01
    }
}
