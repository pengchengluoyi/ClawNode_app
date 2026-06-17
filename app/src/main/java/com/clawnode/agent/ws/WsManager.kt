package com.clawnode.agent.ws

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
 *
 * 职责：
 *  - 与 Gateway 建立持久化长连接；
 *  - 网络波动下指数退避重连（带抖动）；
 *  - 收到的指令解析为 [Command] 经 [incomingCommands] 暴露给分发器；
 *  - 把执行结果 / 图像数据序列化后回传网关。
 *
 * 设计为可注入的 scope（由 AccessibilityService 持有），不自行管理线程池。
 */
class WsManager(
    private val scope: CoroutineScope,
    private val gson: Gson = Gson()
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(NodeConfig.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        // WebSocket 读超时设为 0 = 永不超时，长连接靠 ping 维持
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var manualClosed = false

    /**
     * 鉴权失败熔断标志。一旦网关回 AUTH_FAILED 即置位，连接循环立刻终止、
     * 清空退避重试，直到用户保存新 Token（applySettings 时复位）。防止
     * 用错 token 时无意义地指数重连耗电。
     */
    @Volatile
    private var authHalted = false

    /** 当前生效配置（URL + token + sn）。由服务在配置变更时通过 [applySettings] 注入。 */
    @Volatile
    private var settings: NodeSettings = NodeSettings.EMPTY

    /**
     * 设备元信息（型号/系统/分辨率），由服务在装配时通过 [setDeviceMeta] 注入，
     * 用于构造 register 帧。WsManager 自身不依赖 Android Context。
     */
    @Volatile
    private var deviceMeta: DeviceMeta = DeviceMeta("unknown", "Android", "0x0")

    fun setDeviceMeta(meta: DeviceMeta) {
        deviceMeta = meta
    }

    data class DeviceMeta(val model: String, val osVersion: String, val resolution: String)

    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempt = 0

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** 统一变更状态：同时更新本地流并转发到全局总线供 UI 订阅 */
    private fun setState(s: ConnectionState) {
        _state.value = s
        NodeStatusBus.publish(s)
    }

    /** 上行解析后的指令流；replay=0，extraBufferCapacity 防止快速下发时丢包 */
    private val _incomingCommands = MutableSharedFlow<Command>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingCommands: SharedFlow<Command> = _incomingCommands.asSharedFlow()

    /**
     * 应用新配置并据此决定连接行为（连接门禁的核心）：
     *  - 若 URL 不合法（非 ws/wss）→ 停止连接并保持空闲；
     *  - 若 URL 有效但与当前不同 → 断开旧连接、用新配置重连；
     *  - 若 URL 有效且未变 → 保持现状（幂等）。
     *
     * 注意：无障碍服务是否就绪的门禁在调用方（仅服务装配后才会 applySettings），
     * 因此能进到这里就意味着服务已连接。
     */
    fun applySettings(newSettings: NodeSettings) {
        val old = settings
        settings = newSettings

        if (!newSettings.isConnectable) {
            log("settings not connectable (url='${newSettings.wsUrl}'), staying idle")
            stop()
            return
        }

        val tokenChanged = old.authToken != newSettings.authToken
        val urlChanged = old.wsUrl != newSettings.wsUrl

        // 用户更新了 token（或 URL）→ 解除鉴权熔断，给新凭证一次机会
        if (authHalted && (tokenChanged || urlChanged)) {
            log("credentials changed; clearing auth-halt and retrying")
            authHalted = false
        }

        val running = connectJob?.isActive == true
        when {
            authHalted -> log("auth halted; awaiting new credentials")
            !running -> start()
            urlChanged -> {
                // URL 变了：重启连接循环以走新地址
                log("ws url changed, reconnecting to ${newSettings.wsUrl}")
                restart()
            }
            tokenChanged -> {
                // token 变了：重连以用新凭证重新握手
                log("auth token changed, reconnecting to re-handshake")
                restart()
            }
            else -> log("settings unchanged; connection kept")
        }
    }

    /** 启动连接（幂等）。会一直存活，断线自动重连，直到 [stop]。 */
    fun start() {
        if (!settings.isConnectable) {
            log("start() blocked: url not connectable")
            return
        }
        if (connectJob?.isActive == true) return
        manualClosed = false
        connectJob = scope.launch { connectLoop() }
    }

    private fun restart() {
        stop()
        start()
    }

    /** 主动停止，不再重连 */
    fun stop() {
        manualClosed = true
        stopHeartbeat()
        connectJob?.cancel()
        connectJob = null
        webSocket?.close(NORMAL_CLOSURE, "client stop")
        webSocket = null
        reconnectAttempt = 0
        authHalted = false
        setState(ConnectionState.Idle)
    }

    /** 回传文本帧（JSON）。线程安全：OkHttp WebSocket.send 自身线程安全。 */
    fun send(response: NodeResponse): Boolean {
        val ws = webSocket ?: return false
        return ws.send(gson.toJson(response))
    }

    fun sendRaw(json: String): Boolean = webSocket?.send(json) ?: false

    // ----------------------------------------------------------------

    private suspend fun connectLoop() {
        while (scope.isActive && !manualClosed && !authHalted) {
            // CompletableJob 风格：用一个挂起点等待本次连接结束
            val closedReason = openOnce()

            if (manualClosed || authHalted) break

            // 计算指数退避：base * factor^attempt，封顶 + 抖动
            reconnectAttempt += 1
            val backoff = (NodeConfig.RECONNECT_BASE_DELAY_MS *
                NodeConfig.RECONNECT_FACTOR.pow(reconnectAttempt - 1)).toLong()
            val capped = min(backoff, NodeConfig.RECONNECT_MAX_DELAY_MS)
            // 抖动：用 attempt 派生的伪随机，避免依赖被禁用的 Math.random()
            val jitter = (reconnectAttempt * 137L) % NodeConfig.RECONNECT_JITTER_MS
            val delayMs = capped + jitter

            setState(ConnectionState.Reconnecting(reconnectAttempt, delayMs))
            log("connection closed ($closedReason); reconnect #$reconnectAttempt in ${delayMs}ms")
            delay(delayMs)
        }
    }

    /**
     * 打开一次连接并挂起，直到该连接关闭或失败。
     * 返回关闭原因字符串。用回调 → 挂起的桥接，避免回调地狱。
     */
    private suspend fun openOnce(): String {
        setState(ConnectionState.Connecting)
        val current = settings
        // MiniOrangeServer 的 /ws 在握手阶段读取 query 参数 token 鉴权；
        // 同时保留 Authorization Header 以兼容通用网关。
        val url = buildUrlWithToken(current.wsUrl, current.authToken)
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
                log("connected to ${current.wsUrl}")
                // 上送注册帧：让本节点进入服务端设备列表并标记为直连节点
                val register = RegisterFrame(
                    data = RegisterFrame.Data(
                        sn = current.nodeSn,
                        model = deviceMeta.model,
                        osVersion = deviceMeta.osVersion,
                        resolution = deviceMeta.resolution
                    )
                )
                ws.send(gson.toJson(register))
                // 启动应用层心跳（服务端按 heartbeat action 判活）
                startHeartbeat(current.nodeSn)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                dispatchText(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // 协议为 JSON 文本；二进制帧按 UTF-8 兜底解析
                dispatchText(bytes.utf8())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                stopHeartbeat()
                // 鉴权失败导致的主动关闭：保留 AuthFailed 状态，不覆盖为 Disconnected
                if (!authHalted) setState(ConnectionState.Disconnected("closed[$code] $reason"))
                if (!done.isCompleted) done.complete("closed[$code] $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                webSocket = null
                stopHeartbeat()
                if (!authHalted) setState(ConnectionState.Disconnected(t.message ?: "failure"))
                log("ws failure: ${t.message}")
                if (!done.isCompleted) done.complete("failure: ${t.message}")
            }
        }

        client.newWebSocket(request, listener)
        return done.await()
    }

    /**
     * 解析上行文本并路由。先窥探是否为控制帧（带 `type`），
     * 是则就地处理（鉴权结果等）；否则按动作指令（带 `action_type`）投递。
     */
    private fun dispatchText(text: String) {
        // 1) 控制帧优先：AUTH_OK / AUTH_FAILED
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

        // 2) MiniOrangeServer 的服务端响应帧（带 action + code）：
        //    register_clawnode 成功(code=200) → 进入已鉴权终态；其余 ack 忽略。
        val ack = runCatching { gson.fromJson(text, ServerAck::class.java) }.getOrNull()
        if (ack?.action != null) {
            if (ack.action == "register_clawnode" && ack.code == 200) {
                log("register_clawnode ok → authenticated")
                setState(ConnectionState.Authenticated)
            }
            // 其它服务端 ack（heartbeat 回执、错误码等）无需作为指令处理
            return
        }

        // 3) 动作指令：必须带非空 action_type
        val cmd = runCatching { gson.fromJson(text, Command::class.java) }.getOrNull()
        if (cmd == null || cmd.actionType.isBlank()) {
            log("drop malformed message: $text")
            return
        }
        // tryEmit 不挂起；缓冲已足够大，极端丢弃也只丢单条指令
        if (!_incomingCommands.tryEmit(cmd)) {
            log("command buffer full, dropped trace=${cmd.traceId}")
        }
    }

    /** 服务端响应帧的窥探模型（仅取路由所需字段） */
    private data class ServerAck(val action: String? = null, val code: Int? = null)

    // ---- 应用层心跳：服务端按 heartbeat action 判活（60s 超时） ----

    private fun startHeartbeat(sn: String) {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(NodeConfig.HEARTBEAT_INTERVAL_MS)
                val ws = webSocket ?: break
                val ok = ws.send(gson.toJson(HeartbeatFrame(data = HeartbeatFrame.Data(sn = sn))))
                if (!ok) break
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 把 token 拼到 URL 的 query（MiniOrangeServer /ws 在握手阶段读取 ?token=）。 */
    private fun buildUrlWithToken(baseUrl: String, token: String): String {
        if (token.isBlank() || baseUrl.contains("token=")) return baseUrl
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${sep}token=$token"
    }

    private fun handleAuthOk() {
        log("AUTH_OK: authenticated")
        setState(ConnectionState.Authenticated)
    }

    private fun handleAuthFailed(reason: String) {
        log("AUTH_FAILED: $reason — halting reconnects")
        // 熔断：置位后断开，connectLoop 不再重试，清空退避队列
        authHalted = true
        reconnectAttempt = 0
        webSocket?.close(NORMAL_CLOSURE, "auth failed")
        webSocket = null
        connectJob?.cancel()
        connectJob = null
        setState(ConnectionState.AuthFailed(reason))
    }

    private fun log(msg: String) = android.util.Log.d(TAG, msg)

    companion object {
        private const val TAG = "WsManager"
        private const val NORMAL_CLOSURE = 1000
    }
}
