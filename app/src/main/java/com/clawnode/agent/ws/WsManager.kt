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

    fun setDeviceMeta(meta: DeviceMeta) {
        deviceMeta = meta
        ClawLog.bp(TAG, "device_meta", "model=${meta.model} res=${meta.resolution}")
    }

    data class DeviceMeta(val model: String, val osVersion: String, val resolution: String)

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
        ClawLog.bp(TAG, "stop", "manualClosed=true resetAuth=$resetAuthHalt")
        manualClosed = true
        stopHeartbeat()
        connectJob?.cancel()
        connectJob = null
        webSocket?.close(NORMAL_CLOSURE, "client stop")
        webSocket = null
        reconnectAttempt = 0
        if (resetAuthHalt) authHalted = false
        setState(ConnectionState.Idle)
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
            val closedReason = openOnce()

            if (manualClosed || authHalted) break

            reconnectAttempt += 1
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

    private suspend fun openOnce(): String {
        setState(ConnectionState.Connecting)
        val current = settings
        val url = buildUrlWithToken(current.wsUrl, current.authToken)
        ClawLog.bp(TAG, "ws_open", "sn=${current.nodeSn} url=$url")

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
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                reconnectAttempt = 0
                setState(ConnectionState.Connected)
                ClawLog.bp(TAG, "ws_open_ok", "code=${response.code} sn=${current.nodeSn}")

                val register = RegisterFrame(
                    data = RegisterFrame.Data(
                        sn = current.nodeSn,
                        model = deviceMeta.model,
                        osVersion = deviceMeta.osVersion,
                        resolution = deviceMeta.resolution
                    )
                )
                val regJson = gson.toJson(register)
                val regOk = ws.send(regJson)
                ClawLog.bp(TAG, "register_sent", "sn=${current.nodeSn} ok=$regOk")

                startHeartbeat(current.nodeSn)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                dispatchText(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                dispatchText(bytes.utf8())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ClawLog.w(TAG, "ws_closing", "code=$code reason=$reason")
                ws.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                stopHeartbeat()
                ClawLog.w(TAG, "ws_closed", "code=$code reason=$reason")
                if (!authHalted) setState(ConnectionState.Disconnected("closed[$code] $reason"))
                if (!done.isCompleted) done.complete("closed[$code] $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                webSocket = null
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
        }

        val ack = runCatching { gson.fromJson(text, ServerAck::class.java) }.getOrNull()
        if (ack?.action != null) {
            ClawLog.bp(TAG, "rx_ack", "action=${ack.action} code=${ack.code}")
            if (ack.action == "register_clawnode" && ack.code == 200) {
                setState(ConnectionState.Authenticated)
            }
            return
        }

        val cmd = runCatching { gson.fromJson(text, Command::class.java) }.getOrNull()
        if (cmd == null || cmd.actionType.isBlank()) {
            ClawLog.w(TAG, "rx_drop", "malformed: $preview")
            return
        }
        val emitted = _incomingCommands.tryEmit(cmd)
        ClawLog.bp(TAG, "cmd_emit", "trace=${cmd.traceId} action=${cmd.actionType} ok=$emitted")
        if (!emitted) {
            ClawLog.w(TAG, "cmd_buffer_full", "trace=${cmd.traceId}")
        }
    }

    private data class ServerAck(val action: String? = null, val code: Int? = null)

    private fun startHeartbeat(sn: String) {
        stopHeartbeat()
        heartbeatSeq = 0
        heartbeatJob = scope.launch {
            while (isActive) {
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

    private fun buildUrlWithToken(baseUrl: String, token: String): String {
        if (token.isBlank() || baseUrl.contains("token=")) return baseUrl
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${sep}token=$token"
    }

    private fun handleAuthOk() {
        ClawLog.bp(TAG, "auth_ok", "gateway authenticated")
        setState(ConnectionState.Authenticated)
    }

    private fun handleAuthFailed(reason: String) {
        ClawLog.e(TAG, "auth_failed", reason)
        authHalted = true
        reconnectAttempt = 0
        webSocket?.close(NORMAL_CLOSURE, "auth failed")
        webSocket = null
        connectJob?.cancel()
        connectJob = null
        setState(ConnectionState.AuthFailed(reason))
    }

    companion object {
        private const val TAG = "WsManager"
        private const val NORMAL_CLOSURE = 1000
    }
}
