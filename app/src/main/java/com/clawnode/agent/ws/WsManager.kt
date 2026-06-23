package com.clawnode.agent.ws

import com.clawnode.agent.ClawNodeApp
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
    /** 局域网 mDNS 发现：按 pairedGatewayId 匹配网关，失败返回 null */
    private val discoverServer: (suspend (pairedGatewayId: String) -> String?)? = null,
    /** 服务端下发 PAIR_CONFIG 时持久化凭证 */
    private val onPairConfig: (suspend (wsUrl: String, authToken: String, gatewayId: String) -> Unit)? = null,
    /** mDNS 刷新到的新 ws URL 持久化 */
    private val onUrlDiscovered: (suspend (wsUrl: String) -> Unit)? = null,
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

    @Volatile
    private var unpairedHalt = false

    fun applySettings(newSettings: NodeSettings) {
        val old = settings
        settings = newSettings

        if (newSettings.userUnpaired) {
            unpairedHalt = false
            ClawLog.bp(TAG, "await_pair_push", "mDNS broadcast only sn=${newSettings.nodeSn}")
            stopInternal(resetAuthHalt = false)
            manualClosed = true
            setState(ConnectionState.Unpaired)
            return
        }

        if (!newSettings.isConnectable) {
            unpairedHalt = false
            ClawLog.w(TAG, "settings_not_connectable", "halt url=${newSettings.wsUrl}")
            stopInternal(resetAuthHalt = false)
            return
        }

        unpairedHalt = false

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

    /** 前台服务 / 亮屏回调：WS 已断则立即重连（含打断退避等待） */
    fun reconnectIfNeeded() {        if (!settings.isConnectable || authHalted || manualClosed) return
        if (webSocket != null) {
            ClawLog.bp(TAG, "reconnect_skip", "already connected")
            return
        }
        if (connectJob?.isActive == true) {
            ClawLog.bp(TAG, "reconnect_interrupt", "cancel backoff and restart ws")
            stopInternal(resetAuthHalt = false)
            manualClosed = false
        }
        ClawLog.bp(TAG, "reconnect_wake", "restart ws")
        reconnectAttempt = 0
        start()
    }

    fun isConnected(): Boolean = webSocket != null

    /**
     * 系统默认网络切换回调（WiFi↔蜂窝、断网恢复）。
     * 切换后旧 TCP 多半已失效但 OkHttp 可能尚未感知，故强制重建而非依赖 [reconnectIfNeeded]
     * 的「已连接则跳过」判断。
     */
    fun onNetworkChanged(hasNetwork: Boolean) {
        if (!hasNetwork) {
            ClawLog.bp(TAG, "net_lost", "network lost, await re-available")
            return
        }
        if (!settings.isConnectable || authHalted || manualClosed || unpairedHalt) {
            ClawLog.bp(
                TAG,
                "net_change_skip",
                "connectable=${settings.isConnectable} authHalted=$authHalted manual=$manualClosed unpaired=$unpairedHalt",
            )
            return
        }
        ClawLog.bp(TAG, "net_change", "force reconnect on network switch")
        reconnectAttempt = 0
        restart()
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
        while (scope.isActive && !manualClosed && !authHalted && !unpairedHalt) {
            if (NodeStatusBus.manualDiscoveryActive) {
                setState(ConnectionState.Discovering)
                delay(300)
                continue
            }

            val connectUrl = resolveConnectUrl()
            if (connectUrl == null) {
                reconnectAttempt += 1
                val delayMs = min(
                    (NodeConfig.RECONNECT_BASE_DELAY_MS *
                        NodeConfig.RECONNECT_FACTOR.pow(reconnectAttempt - 1)).toLong(),
                    NodeConfig.RECONNECT_MAX_DELAY_MS
                )
                setState(ConnectionState.Discovering)
                ClawLog.w(TAG, "discovery_retry", "no gateway found attempt=$reconnectAttempt delayMs=$delayMs")
                delay(delayMs)
                continue
            }

            val closedReason = openOnce(connectUrl)

            if (manualClosed || authHalted || closedReason.startsWith("auth:")) break

            reconnectAttempt += 1

            var urlUpdated = false
            if (reconnectAttempt >= NodeConfig.DISCOVERY_AFTER_FAILURES) {
                urlUpdated = tryAutoDiscovery()
            }

            if (urlUpdated) {
                reconnectAttempt = 0
                continue
            }

            val backoff = (NodeConfig.RECONNECT_BASE_DELAY_MS *
                NodeConfig.RECONNECT_FACTOR.pow(reconnectAttempt - 1)).toLong()
            val capped = min(backoff, NodeConfig.RECONNECT_MAX_DELAY_MS)
            val jitter = (reconnectAttempt * 137L) % NodeConfig.RECONNECT_JITTER_MS
            val delayMs = capped + jitter

            if (reconnectAttempt >= NodeConfig.DISCOVERY_AFTER_FAILURES && discoverServer != null) {
                setState(ConnectionState.Discovering)
                ClawLog.w(TAG, "discovery_backoff", "reason=$closedReason attempt=$reconnectAttempt delayMs=$delayMs")
            } else {
                setState(ConnectionState.Reconnecting(reconnectAttempt, delayMs))
                ClawLog.w(TAG, "reconnect_scheduled", "reason=$closedReason attempt=$reconnectAttempt delayMs=$delayMs")
            }
            delay(delayMs)
        }
        ClawLog.bp(TAG, "connect_loop", "exited manualClosed=$manualClosed authHalted=$authHalted")
    }

    /** 
     * Resolve the WS URL to connect to.
     *
     * - For "auto" (blank/ws://auto) or when a pairedGatewayId exists: always do mDNS discovery first.
     *   Discovery (ServerDiscovery) now prefers the lanHost (xxx.local) advertised by the server.
     *   This makes ClawNode resilient to the server's LAN IP changing (Wi-Fi switch, new DHCP lease, etc.).
     *   The OS mDNS resolver will give the current IP when connecting to the .local name.
     * - Fall back to any previously stored URL (could be IP or previous .local) only if discovery yields nothing.
     */
    private suspend fun resolveConnectUrl(): String? {
        val current = settings
        // Always attempt fresh mDNS when we are in auto mode OR have a paired gateway id.
        // This prevents sticking to a stale fixed IP after the gateway's IP changes.
        if (current.usesAutoDiscovery || current.pairedGatewayId.isNotBlank()) {
            val fresh = runDiscovery("auto_or_paired")
            if (!fresh.isNullOrBlank()) return fresh
        }
        return current.wsUrl.takeIf { it.isNotBlank() }
    }

    private suspend fun tryAutoDiscovery(): Boolean {
        if (discoverServer == null) return false
        val discovered = runDiscovery("connect_failure") ?: return false
        if (discovered.isBlank() || discovered == settings.wsUrl) return false
        settings = settings.copy(wsUrl = discovered)
        reconnectAttempt = 0
        onUrlDiscovered?.invoke(discovered)
        ClawLog.bp(TAG, "discovery_applied", "newUrl=$discovered")
        return true
    }

    private suspend fun runDiscovery(reason: String): String? {
        if (discoverServer == null) {
            ClawLog.w(TAG, "discovery_skip", "no discoverServer callback reason=$reason")
            return null
        }
        setState(ConnectionState.Discovering)
        ClawLog.bp(TAG, "discovery_start", "reason=$reason paired=${settings.pairedGatewayId}")
        val discovered = discoverServer.invoke(settings.pairedGatewayId)
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
                ConnectionKeepAlive.acquire(ClawNodeApp.instance.applicationContext)
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
                val classified = classifyFailure(t.message ?: "HTTP ${response?.code}")
                if (isAuthFailure(t, response)) {
                    if (!done.isCompleted) done.complete("auth: ${t.message}")
                    handleAuthFailed(classified)
                    return
                }
                if (!authHalted) setState(ConnectionState.Disconnected(classified))
                if (!done.isCompleted) done.complete("failure: ${t.message}")
                // 后台断线后立即尝试重连，不等待 connectLoop 退避（OEM 可能很快冻结协程）
                scope.launch {
                    if (!manualClosed && !authHalted && settings.isConnectable) {
                        delay(500)
                        reconnectIfNeeded()
                    }
                }
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
                unpairedHalt = true
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
        val ackAction = ack?.action
        if (!ackAction.isNullOrBlank()) {
            ClawLog.bp(TAG, "rx_ack", "action=$ackAction code=${ack?.code}")
            if (ackAction == "register_clawnode" && ack?.code == 200) {
                setState(ConnectionState.Authenticated)
            }
            return
        }

        // ClawNode 现在使用与服务端完全一致的命令格式：
        //   { "type": "command", "command": "XXX", "params": { ... } }
        //   或 { "command": "XXX", "params": { ... } }
        // 边界做极小 key 兼容（把旧的 action_type/payload 映射到 command/params），
        // 之后所有业务逻辑只认 command + params（与 server 同一套）。
        val normalizedText = normalizeToServerCommandFormat(text) ?: text
        val cmd = runCatching { gson.fromJson(normalizedText, Command::class.java) }.getOrNull()
        if (cmd == null || cmd.command.isNullOrBlank()) {
            ClawLog.w(TAG, "rx_drop", "malformed: $preview")
            return
        }
        if (webSocket == null) {
            ClawLog.w(TAG, "rx_cmd_no_ws", "trace=${cmd.safeTraceId} command=${cmd.command}")
            return
        }
        val emitted = _incomingCommands.tryEmit(cmd)
        ClawLog.bp(TAG, "cmd_emit", "trace=${cmd.safeTraceId} command=${cmd.command} ok=$emitted")
        if (!emitted) {
            ClawLog.w(TAG, "cmd_buffer_full", "trace=${cmd.safeTraceId}")
        }
    }

    private data class ServerAck(val action: String? = null, val code: Int? = null)

    /**
     * 极小边界归一化：确保最终 JSON 拥有 "command" + "params" 字段（服务端主格式），
     * 这样 Gson 能直接反序列化到 Command（其字段用 @SerializedName("command") / "params"）。
     *
     * - 如果只有 action_type / payload（历史 Claw 方言），重命名为 command / params。
     * - 提取 trace_id / req_id。
     * - 解包 type:"command" 或 data 层。
     *
     * 目的：ClawNode 与 server 使用同一套收发结构，消除双格式维护。
     */
    private fun normalizeToServerCommandFormat(raw: String): String? {
        return try {
            val root = gson.fromJson(raw, com.google.gson.JsonObject::class.java) ?: return null

            // 解包
            val obj = when {
                root.has("type") && root.get("type").asString == "command" && root.has("command") -> root
                root.has("data") && root.get("data").isJsonObject -> root.getAsJsonObject("data")
                else -> root
            }

            // 决定最终的 command 值（优先 command，其次 action_type）
            val cmdValue = when {
                obj.has("command") && !obj.get("command").isJsonNull -> obj.get("command").asString
                obj.has("action_type") && !obj.get("action_type").isJsonNull -> obj.get("action_type").asString
                else -> null
            } ?: return null

            // 决定 params 对象（优先 params，其次 payload）
            val paramsObj = when {
                obj.has("params") && obj.get("params").isJsonObject -> obj.getAsJsonObject("params")
                obj.has("payload") && obj.get("payload").isJsonObject -> obj.getAsJsonObject("payload")
                else -> com.google.gson.JsonObject()
            }

            // trace 兜底
            val trace = when {
                obj.has("trace_id") && !obj.get("trace_id").isJsonNull -> obj.get("trace_id").asString
                obj.has("req_id") && !obj.get("req_id").isJsonNull -> obj.get("req_id").asString
                else -> "srv-${System.currentTimeMillis()}"
            }

            val normalized = com.google.gson.JsonObject().apply {
                addProperty("trace_id", trace)
                addProperty("command", cmdValue)
                add("params", paramsObj)
                // 保留 type 如果有
                if (obj.has("type") && !obj.get("type").isJsonNull) {
                    addProperty("type", obj.get("type").asString)
                }
            }

            gson.toJson(normalized)
        } catch (_: Exception) {
            null
        }
    }

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
        if (ok) {
            ConnectionKeepAlive.acquire(ClawNodeApp.instance.applicationContext)
        }
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

    /** Server 通过局域网 HTTP 推送配对配置（被动接受，类似蓝牙配对）。 */
    suspend fun applyPairConfigPush(wsUrl: String, authToken: String, gatewayId: String): Boolean {
        return runCatching {
            handlePairConfig(
                com.clawnode.agent.model.GatewayControl.Data(
                    wsUrl = wsUrl,
                    authToken = authToken,
                    gatewayId = gatewayId,
                ),
            )
            true
        }.onFailure { e ->
            ClawLog.e(TAG, "pair_push_apply_fail", e.message ?: "", e)
        }.getOrDefault(false)
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
        unpairedHalt = false
        manualClosed = false
        onPairConfig?.invoke(wsUrl, authToken, gatewayId)
        authHalted = false
        reconnectAttempt = 0
        settings = settings.copy(
            wsUrl = wsUrl,
            authToken = authToken,
            pairedGatewayId = gatewayId,
            userUnpaired = false,
        )
        restart()
    }

    private suspend fun handleUnpairConfig() {
        ClawLog.bp(TAG, "unpair_config_rx", "clearing pairing, halt reconnect")
        unpairedHalt = true
        onUnpair?.invoke()
        reconnectAttempt = 0
        settings = settings.copy(
            wsUrl = "",
            authToken = "",
            pairedGatewayId = "",
            userUnpaired = true,
        )
        stopInternal(resetAuthHalt = false)
        setState(ConnectionState.Unpaired)
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

    private fun classifyFailure(message: String?): String {
        val m = message?.lowercase().orEmpty()
        return when {
            m.contains("failed to connect") || m.contains("connectexception") || m.contains("timeout") || m.contains("no route") ->
                "network_unreachable: $message"
            m.contains("401") || m.contains("403") || m.contains("policy violation") ->
                "auth_rejected: $message"
            else -> message ?: "unknown"
        }
    }

    private fun isAuthFailure(t: Throwable, response: Response?): Boolean {
        val code = response?.code
        if (code == 401 || code == 403) return true
        val msg = t.message?.lowercase().orEmpty()
        return msg.contains("403") || msg.contains("401") ||
            msg.contains("policy violation") || msg.contains("1008")
    }

    companion object {
        private const val TAG = "WsManager"
        private const val NORMAL_CLOSURE = 1000
    }
}
