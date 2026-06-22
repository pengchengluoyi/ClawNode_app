package com.clawnode.agent.ws

import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.ConnectionState
import com.clawnode.agent.core.NodeConfig
import com.clawnode.agent.core.NodeSettings
import com.clawnode.agent.core.NodeStatusBus
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.GatewayControl
import com.clawnode.agent.model.HeartbeatFrame
import com.clawnode.agent.model.NodeResponse
import com.clawnode.agent.model.RegisterFrame
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * WebSocket 通信模块。
 */
class WsManager(
    private val scope: CoroutineScope,
    /** 局域网 mDNS 发现：返回解析后的 ws:// URL，失败返回 null */
    private val discoverServer: (suspend () -> String?)? = null,
    /** 服务端下发 PAIR_CONFIG 时持久化凭证 */
    private val onPairConfig: (suspend (wsUrl: String, authToken: String, gatewayId: String) -> Unit)? = null,
    /** 服务端解绑时清除凭证 */
    private val onUnpair: (suspend () -> Unit)? = null,
    private val gson: Gson = Gson()
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(NodeConfig.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var manualClosed = false

    @Volatile
    private var authHalted = false

    @Volatile
    private var settings: NodeSettings = NodeSettings.EMPTY

    @Volatile
    private var deviceMeta: DeviceMeta = DeviceMeta("unknown", "Android", "0x0")

    /** 每次 connect / stop / restart 递增，用于忽略已 supersede 的 OkHttp 回调。 */
    @Volatile
    private var connectionEpoch = 0L

    fun setDeviceMeta(meta: DeviceMeta) {
        deviceMeta = meta
        ClawLog.bp(TAG, "device_meta", "model=${meta.model} res=${meta.resolution}")
    }

    data class DeviceMeta(val model: String, val osVersion: String, val resolution: String, val appVersion: String = "")

    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0
    private var heartbeatSeq = 0

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private fun setState(s: ConnectionState) {
        ClawLog.bp(TAG, "state_change", "${_state.value} → $s")
        _state.value = s
        NodeStatusBus.publish(s)
    }

    private val _incomingCommands = MutableSharedFlow<Command>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingCommands: SharedFlow<Command> = _incomingCommands.asSharedFlow()

    fun applySettings(newSettings: NodeSettings) {
        val old = settings
        settings = newSettings

        if (!newSettings.isConnectable) {
            ClawLog.w(TAG, "settings_idle", "url='${newSettings.wsUrl}' not connectable")
            stop()
            return
        }

        val tokenChanged = old.authToken != newSettings.authToken
        val urlChanged = old.wsUrl != newSettings.wsUrl

        if (authHalted && (tokenChanged || urlChanged)) {
            ClawLog.bp(TAG, "auth_halt_clear", "credentials updated, retry allowed")
            authHalted = false
        }

        val running = connectJob?.isActive == true
        when {
            authHalted -> ClawLog.w(TAG, "auth_halted", "awaiting new credentials")
            !running -> {
                ClawLog.bp(TAG, "connect_start", "sn=${newSettings.nodeSn} url=${newSettings.wsUrl}")
                start()
            }
            urlChanged -> {
                ClawLog.bp(TAG, "settings_url_change", "reconnect → ${newSettings.wsUrl}")
                restart()
            }
            tokenChanged -> {
                ClawLog.bp(TAG, "settings_token_change", "reconnect for re-handshake")
                restart()
            }
            else -> ClawLog.bp(TAG, "settings_unchanged", "connection kept")
        }
    }

    fun start() {
        if (!settings.isConnectable) {
            ClawLog.w(TAG, "start_blocked", "url not connectable")
            return
        }
        if (connectJob?.isActive == true) {
            ClawLog.bp(TAG, "start_skip", "connect loop already running")
            return
        }
        manualClosed = false
        connectJob = scope.launch { connectLoop() }
    }

    private fun restart() {
        ClawLog.bp(TAG, "restart", "stop then start")
        stopInternal(resetAuthHalt = false)
        manualClosed = false
        start()
    }

    fun stop() {
        stopInternal(resetAuthHalt = true)
    }

    private fun stopInternal(resetAuthHalt: Boolean) {
        ClawLog.bp(TAG, "stop", "manualClosed=true resetAuth=$resetAuthHalt epoch=${connectionEpoch + 1}")
        manualClosed = true
        invalidateInFlightConnections("client stop")
        stopHeartbeat()
        connectJob?.cancel()
        connectJob = null
        reconnectAttempt = 0
        if (resetAuthHalt) authHalted = false
        setState(ConnectionState.Idle)
    }

    /** 作废所有进行中的连接尝试，避免旧 openOnce 的 onFailure 清空新 webSocket。 */
    private fun invalidateInFlightConnections(reason: String) {
        connectionEpoch++
        webSocket?.close(NORMAL_CLOSURE, reason)
        webSocket = null
    }

    fun send(response: NodeResponse): Boolean = sendChecked(response)

    /** 发送并记录断点日志，便于排查「本地执行了但服务端没收到」。 */
    fun sendChecked(response: NodeResponse): Boolean {
        val ws = webSocket
        if (ws == null) {
            ClawLog.w(TAG, "send_no_ws", "trace=${response.traceId} type=${response.type}")
            return false
        }
        val json = gson.toJson(response)
        val ok = ws.send(json)
        if (ok) {
            ClawLog.bp(TAG, "send_ok", "trace=${response.traceId} type=${response.type} bytes=${json.length}")
        } else {
            ClawLog.w(TAG, "send_fail", "trace=${response.traceId} type=${response.type}")
        }
        return ok
    }

    fun sendRaw(json: String): Boolean {
        val ws = webSocket ?: run {
            ClawLog.w(TAG, "send_raw_no_ws", "bytes=${json.length}")
            return false
        }
        val ok = ws.send(json)
        ClawLog.bp(TAG, if (ok) "send_raw_ok" else "send_raw_fail", "bytes=${json.length}")
        return ok
    }

    private suspend fun connectLoop() {
        ClawLog.bp(TAG, "connect_loop", "entered")
        while (scope.isActive && !manualClosed && !authHalted) {
            val connectUrl = resolveConnectUrl()
            if (connectUrl == null) {
                reconnectAttempt += 1
                val delayMs = min(
                    (NodeConfig.RECONNECT_BASE_DELAY_MS *
                        NodeConfig.RECONNECT_FACTOR.pow(reconnectAttempt - 1)).toLong(),
                    NodeConfig.RECONNECT_MAX_DELAY_MS
                )
                setState(ConnectionState.Reconnecting(reconnectAttempt, delayMs))
                ClawLog.w(TAG, "discovery_retry", "no gateway found attempt=$reconnectAttempt delayMs=$delayMs")
                delay(delayMs)
                continue
            }

            val closedReason = openOnce(connectUrl)

            if (manualClosed || authHalted) break

            reconnectAttempt += 1

            if (reconnectAttempt >= NodeConfig.DISCOVERY_AFTER_FAILURES) {
                tryAutoDiscovery()
            }

            val backoff = (NodeConfig.RECONNECT_BASE_DELAY_MS *
                NodeConfig.RECONNECT_FACTOR.pow(reconnectAttempt - 1)).toLong()
            val capped = min(backoff, NodeConfig.RECONNECT_MAX_DELAY_MS)
            val jitter = (reconnectAttempt * 137L) % NodeConfig.RECONNECT_JITTER_MS
            val delayMs = capped + jitter

            setState(ConnectionState.Reconnecting(reconnectAttempt, delayMs))
            ClawLog.w(TAG, "reconnect_scheduled", "reason=$closedReason attempt=$reconnectAttempt delayMs=$delayMs")
            delay(delayMs)
        }
        ClawLog.bp(TAG, "connect_loop", "exited manualClosed=$manualClosed authHalted=$authHalted")
    }

    /** ws://auto 或空 URL 时先 mDNS 发现；手动 URL 失败时由 [tryAutoDiscovery] 自愈 */
    private suspend fun resolveConnectUrl(): String? {
        val current = settings
        if (!current.usesAutoDiscovery) return current.wsUrl
        return runDiscovery("auto_mode")
    }

    private suspend fun tryAutoDiscovery() {
        if (discoverServer == null) return
        val discovered = runDiscovery("connect_failure") ?: return
        if (discovered.isNotBlank() && discovered != settings.wsUrl) {
            settings = settings.copy(wsUrl = discovered)
            reconnectAttempt = 0
            ClawLog.bp(TAG, "discovery_applied", "newUrl=$discovered")
        }
    }

    private suspend fun runDiscovery(reason: String): String? {
        if (discoverServer == null) {
            ClawLog.w(TAG, "discovery_skip", "no discoverServer callback reason=$reason")
            return null
        }
        setState(ConnectionState.Discovering)
        ClawLog.bp(TAG, "discovery_start", "reason=$reason")
        val discovered = discoverServer.invoke()
        if (discovered.isNullOrBlank()) {
            ClawLog.w(TAG, "discovery_miss", "reason=$reason")
        }
        return discovered
    }

    private suspend fun openOnce(connectUrl: String): String {
        val myEpoch = ++connectionEpoch
        setState(ConnectionState.Connecting)
        val current = settings
        val url = buildUrlWithToken(connectUrl, current.authToken, current.nodeSn)
        ClawLog.bp(TAG, "ws_open", "sn=${current.nodeSn} epoch=$myEpoch url=$url")

        val request = Request.Builder()
            .url(url)
            .addHeader("X-Node-Id", current.nodeSn)
            .apply {
                if (current.authToken.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${current.authToken}")
                }
            }
            .build()

        val done = kotlinx.coroutines.CompletableDeferred<String>()

        val listener = object : WebSocketListener() {
            private fun isStale(): Boolean = myEpoch != connectionEpoch

            private fun clearIfActive(ws: WebSocket) {
                if (webSocket === ws) webSocket = null
            }

            override fun onOpen(ws: WebSocket, response: Response) {
                if (isStale()) {
                    ClawLog.w(TAG, "ws_open_stale", "epoch=$myEpoch current=$connectionEpoch url=$url")
                    ws.close(NORMAL_CLOSURE, "stale connection")
                    if (!done.isCompleted) done.complete("stale")
                    return
                }
                webSocket = ws
                reconnectAttempt = 0
                setState(ConnectionState.Connected)
                ClawLog.bp(TAG, "ws_open_ok", "code=${response.code} sn=${current.nodeSn} epoch=$myEpoch")

                val register = RegisterFrame(
                    data = RegisterFrame.Data(
                        sn = current.nodeSn,
                        model = deviceMeta.model,
                        osVersion = deviceMeta.osVersion,
                        resolution = deviceMeta.resolution,
                        appVersion = deviceMeta.appVersion
                    )
                )
                val regJson = gson.toJson(register)
                val regOk = ws.send(regJson)
                ClawLog.bp(TAG, "register_sent", "sn=${current.nodeSn} ok=$regOk")

                startHeartbeat(current.nodeSn, myEpoch)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (isStale() || webSocket !== ws) return
                dispatchText(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (isStale() || webSocket !== ws) return
                dispatchText(bytes.utf8())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (isStale()) return
                ClawLog.w(TAG, "ws_closing", "code=$code reason=$reason")
                ws.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (isStale()) {
                    ClawLog.bp(TAG, "ws_closed_stale", "epoch=$myEpoch code=$code")
                    if (!done.isCompleted) done.complete("stale")
                    return
                }
                clearIfActive(ws)
                stopHeartbeat()
                ClawLog.w(TAG, "ws_closed", "code=$code reason=$reason")
                if (!authHalted) setState(ConnectionState.Disconnected("closed[$code] $reason"))
                if (!done.isCompleted) done.complete("closed[$code] $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (isStale()) {
                    ClawLog.bp(TAG, "ws_failure_stale", "epoch=$myEpoch msg=${t.message}")
                    if (!done.isCompleted) done.complete("stale")
                    return
                }
                clearIfActive(ws)
                stopHeartbeat()
                ClawLog.e(TAG, "ws_failure", "http=${response?.code} msg=${t.message}", t)
                if (!authHalted) setState(ConnectionState.Disconnected(t.message ?: "failure"))
                if (!done.isCompleted) done.complete("failure: ${t.message}")
            }
        }

        client.newWebSocket(request, listener)
        return done.await()
    }

    private fun dispatchText(text: String) {
        val preview = if (text.length > 120) text.take(120) + "…" else text
        ClawLog.bp(TAG, "rx", preview)

        val control = runCatching { gson.fromJson(text, GatewayControl::class.java) }.getOrNull()
        when (control?.type) {
            GatewayControl.TYPE_AUTH_OK -> {
                handleAuthOk()
                return
            }
            GatewayControl.TYPE_AUTH_FAILED -> {
                handleAuthFailed(control.data?.message ?: "gateway rejected token")
                return
            }
            GatewayControl.TYPE_PAIR_CONFIG -> {
                scope.launch {
                    handlePairConfig(control.data)
                }
                return
            }
            GatewayControl.TYPE_UNPAIR_CONFIG -> {
                scope.launch {
                    handleUnpairConfig()
                }
                return
            }
            GatewayControl.TYPE_DEVICE_LIST_UPDATE -> {
                ClawLog.bp(TAG, "rx_device_list_update", "ignored")
                return
            }
        }

        val ack = runCatching { gson.fromJson(text, ServerAck::class.java) }.getOrNull()
        if (!ack?.action.isNullOrBlank()) {
            ClawLog.bp(TAG, "rx_ack", "action=${ack.action} code=${ack.code}")
            if (ack.action == "register_clawnode" && ack.code == 200) {
                setState(ConnectionState.Authenticated)
            }
            return
        }

        val cmd = runCatching { gson.fromJson(text, Command::class.java) }.getOrNull()
        if (cmd == null || cmd.actionType.isNullOrBlank()) {
            ClawLog.w(TAG, "rx_drop", "malformed: $preview")
            return
        }
        if (webSocket == null) {
            ClawLog.w(TAG, "rx_cmd_no_ws", "trace=${cmd.traceId} action=${cmd.actionType}")
            return
        }
        val emitted = _incomingCommands.tryEmit(cmd)
        ClawLog.bp(TAG, "cmd_emit", "trace=${cmd.traceId} action=${cmd.actionType} ok=$emitted")
        if (!emitted) {
            ClawLog.w(TAG, "cmd_buffer_full", "trace=${cmd.traceId}")
        }
    }

    private data class ServerAck(val action: String? = null, val code: Int? = null)

    private fun startHeartbeat(sn: String, epoch: Long) {
        stopHeartbeat()
        heartbeatSeq = 0
        heartbeatJob = scope.launch {
            while (isActive && epoch == connectionEpoch) {
                // 连接后立即发首包，避免服务端 60s 超时窗口内一直等不到 heartbeat
                if (!sendHeartbeat(sn)) break
                delay(NodeConfig.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun sendHeartbeat(sn: String): Boolean {
        val ws = webSocket ?: return false
        heartbeatSeq += 1
        val json = gson.toJson(HeartbeatFrame(data = HeartbeatFrame.Data(sn = sn)))
        val ok = ws.send(json)
        ClawLog.bp(TAG, "heartbeat", "seq=$heartbeatSeq sn=$sn ok=$ok")
        return ok
    }

    private fun stopHeartbeat() {
        if (heartbeatJob != null) {
            ClawLog.bp(TAG, "heartbeat_stop", "lastSeq=$heartbeatSeq")
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun buildUrlWithToken(baseUrl: String, token: String, nodeSn: String): String {
        if (baseUrl.contains("token=") || baseUrl.contains("pairing=")) return baseUrl
        val sep = if (baseUrl.contains("?")) "&" else "?"
        if (token.isNotBlank()) return "$baseUrl${sep}token=$token"
        if (nodeSn.isNotBlank()) {
            return "$baseUrl${sep}pairing=1&node_sn=${java.net.URLEncoder.encode(nodeSn, "UTF-8")}"
        }
        return baseUrl
    }

    private suspend fun handlePairConfig(data: GatewayControl.Data?) {
        val wsUrl = data?.wsUrl?.trim().orEmpty()
        val authToken = data?.authToken?.trim().orEmpty()
        val gatewayId = data?.gatewayId?.trim().orEmpty()
        if (wsUrl.isBlank() || authToken.isBlank()) {
            ClawLog.w(TAG, "pair_config_invalid", "missing ws/token")
            return
        }
        ClawLog.bp(TAG, "pair_config_rx", "gateway=$gatewayId url=$wsUrl")
        onPairConfig?.invoke(wsUrl, authToken, gatewayId)
        authHalted = false
        reconnectAttempt = 0
        settings = settings.copy(wsUrl = wsUrl, authToken = authToken, pairedGatewayId = gatewayId)
        restart()
    }

    private suspend fun handleUnpairConfig() {
        ClawLog.bp(TAG, "unpair_config_rx", "clearing pairing")
        onUnpair?.invoke()
        authHalted = false
        reconnectAttempt = 0
        settings = settings.copy(
            wsUrl = NodeSettings.AUTO_DISCOVERY_URL,
            authToken = "",
            pairedGatewayId = "",
        )
        restart()
    }

    private fun handleAuthOk() {
        ClawLog.bp(TAG, "auth_ok", "gateway authenticated")
        setState(ConnectionState.Authenticated)
    }

    private fun handleAuthFailed(reason: String) {
        ClawLog.e(TAG, "auth_failed", reason)
        authHalted = true
        reconnectAttempt = 0
        invalidateInFlightConnections("auth failed")
        connectJob?.cancel()
        connectJob = null
        setState(ConnectionState.AuthFailed(reason))
    }

    companion object {
        private const val TAG = "WsManager"
        private const val NORMAL_CLOSURE = 1000
    }
}
