package com.clawnode.agent.ws

import com.clawnode.agent.core.ConnectionState
import com.clawnode.agent.core.NodeConfig
import com.clawnode.agent.model.Command
import com.clawnode.agent.model.NodeResponse
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

    private var connectJob: Job? = null
    private var reconnectAttempt = 0

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** 上行解析后的指令流；replay=0，extraBufferCapacity 防止快速下发时丢包 */
    private val _incomingCommands = MutableSharedFlow<Command>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingCommands: SharedFlow<Command> = _incomingCommands.asSharedFlow()

    /** 启动连接（幂等）。会一直存活，断线自动重连，直到 [stop]。 */
    fun start() {
        if (connectJob?.isActive == true) return
        manualClosed = false
        connectJob = scope.launch { connectLoop() }
    }

    /** 主动停止，不再重连 */
    fun stop() {
        manualClosed = true
        connectJob?.cancel()
        connectJob = null
        webSocket?.close(NORMAL_CLOSURE, "client stop")
        webSocket = null
        _state.value = ConnectionState.Idle
    }

    /** 回传文本帧（JSON）。线程安全：OkHttp WebSocket.send 自身线程安全。 */
    fun send(response: NodeResponse): Boolean {
        val ws = webSocket ?: return false
        return ws.send(gson.toJson(response))
    }

    fun sendRaw(json: String): Boolean = webSocket?.send(json) ?: false

    // ----------------------------------------------------------------

    private suspend fun connectLoop() {
        while (scope.isActive && !manualClosed) {
            // CompletableJob 风格：用一个挂起点等待本次连接结束
            val closedReason = openOnce()

            if (manualClosed) break

            // 计算指数退避：base * factor^attempt，封顶 + 抖动
            reconnectAttempt += 1
            val backoff = (NodeConfig.RECONNECT_BASE_DELAY_MS *
                NodeConfig.RECONNECT_FACTOR.pow(reconnectAttempt - 1)).toLong()
            val capped = min(backoff, NodeConfig.RECONNECT_MAX_DELAY_MS)
            // 抖动：用 attempt 派生的伪随机，避免依赖被禁用的 Math.random()
            val jitter = (reconnectAttempt * 137L) % NodeConfig.RECONNECT_JITTER_MS
            val delayMs = capped + jitter

            _state.value = ConnectionState.Reconnecting(reconnectAttempt, delayMs)
            log("connection closed ($closedReason); reconnect #$reconnectAttempt in ${delayMs}ms")
            delay(delayMs)
        }
    }

    /**
     * 打开一次连接并挂起，直到该连接关闭或失败。
     * 返回关闭原因字符串。用回调 → 挂起的桥接，避免回调地狱。
     */
    private suspend fun openOnce(): String {
        _state.value = ConnectionState.Connecting
        val request = Request.Builder()
            .url(NodeConfig.gatewayUrl)
            .addHeader("X-Node-Id", NodeConfig.nodeId)
            .build()

        val done = kotlinx.coroutines.CompletableDeferred<String>()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                reconnectAttempt = 0
                _state.value = ConnectionState.Connected
                log("connected to ${NodeConfig.gatewayUrl}")
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
                _state.value = ConnectionState.Disconnected("closed[$code] $reason")
                if (!done.isCompleted) done.complete("closed[$code] $reason")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                webSocket = null
                _state.value = ConnectionState.Disconnected(t.message ?: "failure")
                log("ws failure: ${t.message}")
                if (!done.isCompleted) done.complete("failure: ${t.message}")
            }
        }

        client.newWebSocket(request, listener)
        return done.await()
    }

    private fun dispatchText(text: String) {
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

    private fun log(msg: String) = android.util.Log.d(TAG, msg)

    companion object {
        private const val TAG = "WsManager"
        private const val NORMAL_CLOSURE = 1000
    }
}
