package com.clawnode.agent.core

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 顶层委托：每个进程对同一文件仅有一个 DataStore 实例
private val Context.configDataStore by preferencesDataStore(name = "clawnode_config")

/**
 * 节点配置持久化管理器。
 *
 * 用 DataStore(Preferences) 存储 [ws_url] / [auth_token] / [node_sn]，
 * 以 [settings] 冷流的形式对外暴露，配置变更会自动推送给所有订阅者
 * （包括常驻的 AccessibilityService，从而触发连接热更新）。
 *
 * node_sn 默认取设备的 Android ID（稳定、设备唯一），可被用户覆盖。
 *
 * 单例：所有组件共享同一实例，避免多 DataStore 句柄。
 */
class ConfigManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    /** 设备默认 SN：Android ID。重置/刷机会变，但同一安装内稳定。 */
    @SuppressLint("HardwareIds")
    val defaultNodeSn: String =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { "claw-$it" }
            ?: "claw-unknown"

    /** 配置流：DataStore 写入后自动发射新的 [NodeSettings] */
    val settings: Flow<NodeSettings> = appContext.configDataStore.data.map { prefs ->
        NodeSettings(
            wsUrl = prefs[KEY_WS_URL].orEmpty(),
            authToken = prefs[KEY_AUTH_TOKEN].orEmpty(),
            nodeSn = prefs[KEY_NODE_SN]?.takeIf { it.isNotBlank() } ?: defaultNodeSn,
            pairedGatewayId = prefs[KEY_PAIRED_GATEWAY_ID].orEmpty()
        )
    }

    suspend fun save(wsUrl: String, authToken: String) {
        appContext.configDataStore.edit { prefs ->
            prefs[KEY_WS_URL] = wsUrl.trim()
            prefs[KEY_AUTH_TOKEN] = authToken.trim()
        }
    }

    suspend fun savePairing(
        wsUrl: String,
        authToken: String,
        gatewayId: String
    ) {
        appContext.configDataStore.edit { prefs ->
            prefs[KEY_WS_URL] = wsUrl.trim()
            prefs[KEY_AUTH_TOKEN] = authToken.trim()
            prefs[KEY_PAIRED_GATEWAY_ID] = gatewayId.trim()
        }
    }

    suspend fun clearPairing() {
        appContext.configDataStore.edit { prefs ->
            prefs[KEY_WS_URL] = NodeSettings.AUTO_DISCOVERY_URL
            prefs[KEY_AUTH_TOKEN] = ""
            prefs[KEY_PAIRED_GATEWAY_ID] = ""
        }
    }

    companion object {
        private val KEY_WS_URL = stringPreferencesKey("ws_url")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_NODE_SN = stringPreferencesKey("node_sn")
        private val KEY_PAIRED_GATEWAY_ID = stringPreferencesKey("paired_gateway_id")

        @Volatile
        private var INSTANCE: ConfigManager? = null

        fun get(context: Context): ConfigManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context).also { INSTANCE = it }
            }
    }
}
