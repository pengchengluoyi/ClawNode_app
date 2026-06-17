package com.clawnode.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.clawnode.agent.action.ActionExecutorService
import com.clawnode.agent.core.NodeConfig
import com.clawnode.agent.databinding.ActivityMainBinding
import com.clawnode.agent.system.SystemController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 节点控制台。仅用于：权限引导、Gateway 配置、状态展示、唤醒自测。
 * 所有业务逻辑均不在此处——本类只触达 SystemController 与配置。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etGateway.setText(NodeConfig.gatewayUrl)

        binding.btnAccessibility.setOnClickListener {
            SystemController.openAccessibilitySettings(this)
        }
        binding.btnSaveGateway.setOnClickListener {
            val url = binding.etGateway.text?.toString()?.trim().orEmpty()
            if (url.startsWith("ws://") || url.startsWith("wss://")) {
                NodeConfig.gatewayUrl = url
                toast("已保存：$url（重启无障碍服务后生效）")
            } else {
                toast("地址必须以 ws:// 或 wss:// 开头")
            }
        }
        binding.btnNotification.setOnClickListener {
            SystemController.openNotificationSettings(this)
        }
        binding.btnWake.setOnClickListener {
            SystemController.wakeUpScreen(this)
        }

        requestNotificationPermissionIfNeeded()
        observeConnectionState()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityState()
    }

    private fun refreshAccessibilityState() {
        val on = SystemController.isAccessibilityEnabled(this)
        binding.tvAccessibilityState.text = "无障碍服务：${if (on) "已开启 ✓" else "未开启 ✗"}"
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            // 服务可能尚未连接；轮询拿到实例后再收集其状态流
            while (true) {
                val svc = ActionExecutorService.instance
                if (svc != null) break
                kotlinx.coroutines.delay(500)
            }
            // 这里只展示“服务在线”；WsManager.state 属于服务内部，
            // 真实项目可通过 bound service / 全局总线暴露，示例中简化处理。
            binding.tvConnState.text = "连接状态：节点服务已在线"
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
        private const val REQ_NOTIF = 0x01
    }
}
