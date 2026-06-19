package com.clawnode.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clawnode.agent.BuildConfig
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.ConfigManager
import com.clawnode.agent.core.ConnectionState
import com.clawnode.agent.core.NodeStatusBus
import com.clawnode.agent.databinding.ActivityMainBinding
import com.clawnode.agent.system.MediaProjectionRequestActivity
import com.clawnode.agent.system.SystemController
import com.clawnode.agent.update.AppUpdateManager
import com.clawnode.agent.vision.MediaProjectionHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val config by lazy { ConfigManager.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClawLog.bp(TAG, "onCreate", "main activity v${BuildConfig.VERSION_NAME}")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

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
        binding.btnRestricted.setOnClickListener {
            showRestrictedSettingsGuide()
        }
        binding.btnAccessibility.setOnClickListener {
            if (!SystemController.isRestrictedSettingsAllowed(this)) {
                showRestrictedSettingsGuide()
            } else {
                SystemController.openAccessibilitySettings(this)
            }
        }
        binding.btnNotification.setOnClickListener {
            SystemController.openNotificationSettings(this)
        }
        binding.btnBattery.setOnClickListener {
            SystemController.requestIgnoreBatteryOptimizations(this)
        }
        binding.btnScreenCapture.setOnClickListener {
            startActivity(
                Intent(this, MediaProjectionRequestActivity::class.java)
                    .putExtra(MediaProjectionRequestActivity.EXTRA_MODE, MediaProjectionRequestActivity.MODE_AUTHORIZE)
            )
        }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate(manual = true) }
        binding.btnWake.setOnClickListener {
            SystemController.wakeUpScreen(this)
        }
    }

    private fun showRestrictedSettingsGuide() {
        AlertDialog.Builder(this)
            .setTitle("解除「受限设置」（必做）")
            .setMessage(
                "APK 直接安装的应用在 Android 13+ 默认被限制，无障碍会显示「由受限设置控制」。\n\n" +
                    "请按顺序操作：\n" +
                    "1. 点「确定」进入应用详情\n" +
                    "2. 点右上角 ⋮（更多）\n" +
                    "3. 选择「允许受限设置」\n" +
                    "4. 返回本页，点「开启无障碍服务」\n" +
                    "5. 打开 ClawNode Agent 开关\n\n" +
                    "小米/红米：设置 → 应用设置 → 应用管理 → ClawNode → 权限相关 → 允许受限设置"
            )
            .setPositiveButton("去应用详情") { _, _ ->
                SystemController.openAppDetailsSettings(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshPermissionStates() {
        val a11yOn = SystemController.isAccessibilityEnabled(this)
        val restrictedOk = SystemController.isRestrictedSettingsAllowed(this)

        when {
            a11yOn -> binding.tvAccessibilityState.text = "🟢 无障碍服务：已就绪"
            !restrictedOk -> {
                binding.tvAccessibilityState.text =
                    "🔴 无障碍：受限设置未解除（见下方红色提示）"
                binding.tvRestrictedHint.visibility = View.VISIBLE
                binding.btnRestricted.visibility = View.VISIBLE
            }
            else -> {
                binding.tvAccessibilityState.text = "🔴 无障碍服务：未开启"
                binding.tvRestrictedHint.visibility = View.GONE
                binding.btnRestricted.visibility = View.GONE
            }
        }

        val captureReady = MediaProjectionHolder.hasAuthorization()
        binding.tvCaptureState.text =
            if (captureReady) "🟢 屏幕捕获：已授权（支持后台截图）"
            else "🟡 屏幕捕获：未授权（后台截图需先授权）"

        val batteryOk = SystemController.isBatteryOptimizationIgnored(this)
        binding.tvBatteryState.text =
            if (batteryOk) "🟢 电池优化：已忽略（推荐）"
            else "🔴 电池优化：未忽略（后台可能被杀死）"
    }

    private fun checkUpdate(manual: Boolean) {
        lifecycleScope.launch {
            binding.btnCheckUpdate.isEnabled = false
            binding.btnCheckUpdate.text = "检查中…"
            try {
                val info = AppUpdateManager.checkForUpdate()
                if (!info.hasUpdate) {
                    toast("已是最新版本 ${info.currentVersion}")
                    return@launch
                }
                if (info.downloadUrl.isNullOrBlank()) {
                    toast("发现新版本 ${info.latestVersion}，但 Release 无 APK 附件")
                    return@launch
                }
                showUpdateDialog(info.latestVersion, info.releaseNotes, info.downloadUrl, info.assetName ?: "clawnode.apk")
            } catch (e: Exception) {
                ClawLog.e(TAG, "update_check_fail", "", e)
                if (manual) toast("检查更新失败：${e.message}")
            } finally {
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "检查更新（GitHub）"
            }
        }
    }

    private fun showUpdateDialog(version: String, notes: String, url: String, fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v$version")
            .setMessage(notes.ifBlank { "是否下载并安装？" })
            .setPositiveButton("下载安装") { _, _ ->
                lifecycleScope.launch { downloadAndInstall(url, fileName) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun downloadAndInstall(url: String, fileName: String) {
        if (!AppUpdateManager.canInstallPackages(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("请允许「安装未知应用」后再试")
                .setPositiveButton("去设置") { _, _ ->
                    AppUpdateManager.openInstallPermissionSettings(this)
                }
                .show()
            return
        }
        binding.btnCheckUpdate.text = "下载中…"
        binding.btnCheckUpdate.isEnabled = false
        try {
            val file = AppUpdateManager.downloadApk(this, url, fileName)
            AppUpdateManager.installApk(this, file)
            toast("已下载，请在系统弹窗中确认安装")
        } catch (e: Exception) {
            ClawLog.e(TAG, "update_download_fail", "", e)
            toast("下载失败：${e.message}")
        } finally {
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = "检查更新（GitHub）"
        }
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
            is ConnectionState.Connecting -> "🟡 连接中…"
            is ConnectionState.Connected -> "🟡 已连接，鉴权中…"
            is ConnectionState.Authenticated -> "🟢 已连接且已鉴权"
            is ConnectionState.Reconnecting ->
                "🟠 重连中…（第 ${state.attempt} 次，${state.nextDelayMs}ms 后）"
            is ConnectionState.Disconnected -> "🔴 已断开：${state.reason}"
            is ConnectionState.AuthFailed -> "⛔ 鉴权失败，请检查 Token（${state.reason}）"
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
