package com.clawnode.agent.discovery

import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.NodeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 对单个网关做短连接探测：Token 正确则 WS 握手成功（onOpen）。
 * Token 错误时服务端在 accept 前关闭（HTTP 403 / WS 1008）。
 */
object GatewayProbe {

    private const val TAG = "GatewayProbe"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    sealed class ProbeResult {
        data class Accepted(val wsUrl: String) : ProbeResult()
        data object AuthRejected : ProbeResult()
        data class Failed(val message: String) : ProbeResult()
    }

    suspend fun probe(
        wsUrl: String,
        token: String,
        nodeSn: String,
        timeoutMs: Long = NodeConfig.PROBE_TIMEOUT_MS
    ): ProbeResult = withTimeoutOrNull(timeoutMs) {
        probeOnce(wsUrl, token, nodeSn)
    } ?: ProbeResult.Failed("timeout ${timeoutMs}ms")

    private suspend fun probeOnce(
        wsUrl: String,
        token: String,
        nodeSn: String
    ): ProbeResult {
        val url = buildUrlWithToken(wsUrl, token)
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Node-Id", nodeSn)
            .apply {
                if (token.isNotBlank()) addHeader("Authorization", "Bearer $token")
            }
            .build()

        val done = CompletableDeferred<ProbeResult>()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ClawLog.bp(TAG, "probe_open", "url=$wsUrl code=${response.code}")
                ws.close(1000, "probe ok")
                if (!done.isCompleted) done.complete(ProbeResult.Accepted(wsUrl))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (done.isCompleted) return
                val code = response?.code
                ClawLog.bp(TAG, "probe_failure", "url=$wsUrl http=$code msg=${t.message}")
                done.complete(
                    when {
                        code == 401 || code == 403 -> ProbeResult.AuthRejected
                        isAuthClose(t.message) -> ProbeResult.AuthRejected
                        else -> ProbeResult.Failed(t.message ?: "connect failed")
                    }
                )
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (done.isCompleted) return
                ClawLog.bp(TAG, "probe_closed", "url=$wsUrl code=$code reason=$reason")
                if (code == 1008) {
                    done.complete(ProbeResult.AuthRejected)
                } else if (!done.isCompleted) {
                    done.complete(ProbeResult.Failed("closed[$code] $reason"))
                }
            }
        }

        val ws = client.newWebSocket(request, listener)
        return try {
            done.await()
        } finally {
            ws.cancel()
        }
    }

    private fun isAuthClose(message: String?): Boolean {
        val msg = message?.lowercase().orEmpty()
        return msg.contains("403") || msg.contains("401") ||
            msg.contains("policy violation") || msg.contains("1008")
    }

    private fun buildUrlWithToken(baseUrl: String, token: String): String {
        if (token.isBlank() || baseUrl.contains("token=")) return baseUrl
        val sep = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${sep}token=$token"
    }
}