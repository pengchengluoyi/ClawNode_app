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
import com.clawnode.agent.core.NodeSettings
import com.clawnode.agent.core.NodeStatusBus
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.ListView
import com.clawnode.agent.discovery.GatewayProbe
import com.clawnode.agent.discovery.ServerDiscovery
import com.clawnode.agent.databinding.ActivityMainBinding
import com.clawnode.agent.log.LogUploadManager
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
        promptInstallPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        ClawLog.bp(TAG, "onResume", "refresh states")
        runCatching { refreshPermissionStates() }
            .onFailure { e -> ClawLog.e(TAG, "refresh_fail", "", e) }
    }

    private fun loadConfigIntoInputs() {
        lifecycleScope.launch {
            runCatching {
                val s = config.settings.first()
                binding.etWsUrl.setText(s.wsUrl)
                binding.etAuthToken.setText(s.authToken)
            }.onFailure { e -> ClawLog.e(TAG, "load_config_fail", "", e) }
        }
    }

    private fun bindButtons() {
        binding.btnSaveConfig.setOnClickListener {
            val url = binding.etWsUrl.text?.toString()?.trim().orEmpty()
            val token = binding.etAuthToken.text?.toString()?.trim().orEmpty()
            if (!NodeSettings.isValidUrlInput(url)) {
                toast("URL 须为 ws://、wss://、auto 或 ws://auto")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val normalizedUrl = when {
                    url.isBlank() || url.equals("auto", ignoreCase = true) -> NodeSettings.AUTO_DISCOVERY_URL
                    else -> url
                }
                config.save(normalizedUrl, token)
                ClawLog.bp(TAG, "config_saved", "url=$normalizedUrl")
                toast("已保存，连接将自动更新")
            }
        }
        binding.btnDiscover.setOnClickListener { discoverGateway() }
        binding.btnRestricted.setOnClickListener { showRestrictedSettingsGuide() }
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
        binding.btnUploadLog.setOnClickListener { uploadLogs() }
        binding.btnShareLog.setOnClickListener { shareLogs() }
        binding.btnInstallPerm.setOnClickListener {
            AppUpdateManager.openInstallPermissionSettings(this)
        }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate(manual = true) }
        binding.btnWake.setOnClickListener {
            SystemController.wakeUpScreen(this)
        }
    }

    private fun discoverGateway() {
        lifecycleScope.launch {
            binding.btnDiscover.isEnabled = false
            binding.btnDiscover.text = "搜索网关…"
            try {
                val token = binding.etAuthToken.text?.toString()?.trim().orEmpty()
                if (token.isBlank()) {
                    toast("请先填写 Auth Token")
                    return@launch
                }
                val gateways = ServerDiscovery.findGateways(this@MainActivity)
                if (gateways.isEmpty()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("未发现网关")
                        .setMessage("请确认手机与 MiniOrangeServer 在同一 WiFi，且服务端已启动。")
                        .setPositiveButton("确定", null)
                        .show()
                    return@launch
                }
                showGatewayPicker(gateways, token)
            } catch (e: Exception) {
                ClawLog.e(TAG, "discover_fail", "", e)
                toast("搜索失败：${e.message}")
            } finally {
                binding.btnDiscover.isEnabled = true
                binding.btnDiscover.text = "发现网关并配对"
            }
        }
    }

    private fun showGatewayPicker(gateways: List<ServerDiscovery.Gateway>, token: String) {
        val inflater = LayoutInflater.from(this)
        val listView = ListView(this)
        val adapter = object : ArrayAdapter<ServerDiscovery.Gateway>(
            this,
            android.R.layout.simple_list_item_single_choice,
            gateways
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: inflater.inflate(com.clawnode.agent.R.layout.item_gateway, parent, false)
                val item = getItem(position)!!
                view.findViewById<android.widget.TextView>(com.clawnode.agent.R.id.tvGatewayTitle).text =
                    item.displayName
                view.findViewById<android.widget.TextView>(com.clawnode.agent.R.id.tvGatewaySubtitle).text =
                    "${item.wsUrl}\n${item.lanHost ?: item.host}"
                return view
            }
        }
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        listView.setItemChecked(0, true)

        AlertDialog.Builder(this)
            .setTitle("选择网关（${gateways.size}）")
            .setView(listView)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认连接") { _, _ ->
                val idx = listView.checkedItemPosition.takeIf { it >= 0 } ?: 0
                val picked = gateways[idx]
                confirmPairGateway(picked, token)
            }
            .show()
    }

    private fun confirmPairGateway(gateway: ServerDiscovery.Gateway, token: String) {
        lifecycleScope.launch {
            binding.btnDiscover.isEnabled = false
            binding.btnDiscover.text = "鉴权中…"
            try {
                val nodeSn = config.settings.first().nodeSn
                when (
                    val probe = GatewayProbe.probe(
                        wsUrl = gateway.wsUrl,
                        token = token,
                        nodeSn = nodeSn
                    )
                ) {
                    is GatewayProbe.ProbeResult.Accepted -> {
                        binding.etWsUrl.setText(gateway.wsUrl)
                        config.savePairing(gateway.wsUrl, token, gateway.instanceId)
                        toast("已配对：${gateway.displayName}")
                    }
                    is GatewayProbe.ProbeResult.AuthRejected -> {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Token 不匹配")
                            .setMessage("${gateway.displayName}\n请检查 Auth Token 是否为该网关的密钥。")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                    is GatewayProbe.ProbeResult.Failed -> {
                        toast("连接失败：${probe.message}")
                    }
                }
            } finally {
                binding.btnDiscover.isEnabled = true
                binding.btnDiscover.text = "发现网关并配对"
            }
        }
    }

    private fun applyDiscoveredGateway(result: ServerDiscovery.Gateway) {
        binding.etWsUrl.setText(result.wsUrl)
        val token = binding.etAuthToken.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            config.savePairing(result.wsUrl, token, result.instanceId)
            toast("已配对：${result.displayName} → ${result.wsUrl}")
        }
    }

    private fun promptInstallPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !AppUpdateManager.canInstallPackages(this)) {
            binding.btnInstallPerm.visibility = View.VISIBLE
        }
    }

    private fun showRestrictedSettingsGuide() {
        AlertDialog.Builder(this)
            .setTitle("解除「受限设置」（必做）")
            .setMessage(
                "APK 直接安装的应用在 Android 13+ 默认被限制，无障碍会显示「由受限设置控制」。\n\n" +
                    "1. 点「确定」进入应用详情\n" +
                    "2. 右上角 ⋮ → 「允许受限设置」\n" +
                    "3. 返回开启 ClawNode Agent 无障碍\n\n" +
                    "小米：应用管理 → ClawNode → 权限 → 允许受限设置"
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
                binding.tvAccessibilityState.text = "🔴 无障碍：受限设置未解除"
                binding.tvRestrictedHint.visibility = View.VISIBLE
                binding.btnRestricted.visibility = View.VISIBLE
            }
            else -> {
                binding.tvAccessibilityState.text = "🔴 无障碍服务：未开启"
                binding.tvRestrictedHint.visibility = View.GONE
                binding.btnRestricted.visibility = View.GONE
            }
        }

        binding.tvCaptureState.text =
            if (MediaProjectionHolder.hasAuthorization()) "🟢 屏幕捕获：已授权（支持后台截图）"
            else "🟡 屏幕捕获：未授权（后台截图需先授权）"

        binding.tvBatteryState.text =
            if (SystemController.isBatteryOptimizationIgnored(this)) "🟢 电池优化：已忽略（推荐）"
            else "🔴 电池优化：未忽略（后台可能被杀死）"

        binding.btnInstallPerm.visibility =
            if (AppUpdateManager.canInstallPackages(this)) View.GONE else View.VISIBLE
    }

    private fun uploadLogs() {
        lifecycleScope.launch {
            binding.btnUploadLog.isEnabled = false
            binding.btnUploadLog.text = "上传中…"
            try {
                val result = LogUploadManager.uploadWithCurrentSettings(this@MainActivity)
                if (result.success) {
                    toast(result.message)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("上传失败")
                        .setMessage("${result.message}\n\n可改用「分享日志」通过微信/邮件发送。")
                        .setPositiveButton("分享日志") { _, _ -> shareLogs() }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                ClawLog.e(TAG, "upload_log_fail", "", e)
                toast("上传异常：${e.message}")
            } finally {
                binding.btnUploadLog.isEnabled = true
                binding.btnUploadLog.text = "上传日志到网关"
            }
        }
    }

    private fun shareLogs() {
        lifecycleScope.launch {
            try {
                val file = LogUploadManager.prepareExportFile(this@MainActivity)
                LogUploadManager.shareLogFile(this@MainActivity, file)
            } catch (e: Exception) {
                toast("导出日志失败：${e.message}")
            }
        }
    }

    private fun checkUpdate(manual: Boolean) {
        lifecycleScope.launch {
            binding.btnCheckUpdate.isEnabled = false
            binding.btnCheckUpdate.text = "检查中…"
            try {
                if (!AppUpdateManager.canInstallPackages(this@MainActivity)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("需要安装权限")
                        .setMessage("自动更新需先允许「安装未知应用」（一次性设置）")
                        .setPositiveButton("去设置") { _, _ ->
                            AppUpdateManager.openInstallPermissionSettings(this@MainActivity)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@launch
                }
                val info = AppUpdateManager.checkForUpdate()
                if (!info.hasUpdate) {
                    toast("已是最新版本 ${info.currentVersion}")
                    return@launch
                }
                if (info.downloadUrl.isNullOrBlank()) {
                    toast("发现 v${info.latestVersion}，但 Release 无 APK")
                    return@launch
                }
                showUpdateDialog(info.latestVersion, info.releaseNotes, info.downloadUrl, info.assetName ?: "ClawNode.apk")
            } catch (e: Exception) {
                ClawLog.e(TAG, "update_check_fail", "", e)
                if (manual) toast("检查更新失败：${e.message}")
            } finally {
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "检查更新（GitHub 自动）"
            }
        }
    }

    private fun showUpdateDialog(version: String, notes: String, url: String, fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v$version")
            .setMessage(notes.ifBlank { "将自动下载并弹出系统安装确认。" })
            .setPositiveButton("立即更新") { _, _ ->
                lifecycleScope.launch { downloadAndInstall(url, fileName) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private suspend fun downloadAndInstall(url: String, fileName: String) {
        binding.btnCheckUpdate.text = "下载中…"
        binding.btnCheckUpdate.isEnabled = false
        try {
            val file = AppUpdateManager.downloadApk(this, url, fileName)
            AppUpdateManager.installApk(this, file)
            toast("已下载，请在系统弹窗点「安装」")
        } catch (e: Exception) {
            ClawLog.e(TAG, "update_download_fail", "", e)
            toast("下载失败：${e.message}")
        } finally {
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = "检查更新（GitHub 自动）"
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NodeStatusBus.connection.collect { state ->
                    runCatching { renderConnection(state) }
                }
            }
        }
    }

    private fun renderConnection(state: ConnectionState) {
        binding.tvConnState.text = when (state) {
            is ConnectionState.Idle -> "⚪ 未连接（请确认无障碍已开启且已填写 Token）"
            is ConnectionState.Discovering -> "🔍 正在发现局域网网关…"
            is ConnectionState.Connecting -> "🟡 连接中…"
            is ConnectionState.Connected -> "🟡 已连接，鉴权中…"
            is ConnectionState.Authenticated -> "🟢 已连接且已鉴权"
            is ConnectionState.Reconnecting -> "🟠 重连中…（第 ${state.attempt} 次，${state.nextDelayMs}ms 后）"
            is ConnectionState.Disconnected -> "🔴 已断开：${state.reason}"
            is ConnectionState.AuthFailed -> "⛔ 鉴权失败，请检查 Token（${state.reason}）"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
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
