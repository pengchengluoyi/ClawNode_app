package com.clawnode.agent.pairing

import com.clawnode.agent.core.ClawLog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 局域网被动配对 HTTP 服务（蓝牙式：仅广播 + 等待 Server 推送 PAIR_CONFIG）。
 */
object PairingHttpServer {

    const val PORT = 10105
    private const val TAG = "PairingHttp"

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var acceptJob: Job? = null
    @Volatile
    private var listenSocket: ServerSocket? = null

    private data class PairRequestBody(
        @SerializedName("ws_url") val wsUrl: String? = null,
        @SerializedName("auth_token") val authToken: String? = null,
        @SerializedName("gateway_id") val gatewayId: String? = null,
    )

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            runCatching {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                listenSocket = server
                ClawLog.bp(TAG, "listen", "port=$PORT awaiting server pair push")
                while (isActive) {
                    val socket = withContext(Dispatchers.IO) { server.accept() }
                    launch { handleConnection(socket) }
                }
            }.onFailure { e ->
                if (acceptJob?.isActive == true) {
                    ClawLog.e(TAG, "listen_fail", e.message ?: "", e)
                }
            }.also {
                runCatching { listenSocket?.close() }
                listenSocket = null
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { listenSocket?.close() }
        listenSocket = null
        ClawLog.bp(TAG, "stop", "pairing http server stopped")
    }

    private suspend fun handleConnection(socket: Socket) {
        val remote = socket.inetAddress?.hostAddress ?: "?"
        runCatching {
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream())
            val (meta, body) = readRequest(reader)
            val parts = meta.split(" ")
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "" }

            if (method != "POST" || path != "/pair") {
                ClawLog.w(TAG, "reject", "remote=$remote meta=$meta")
                writeResponse(writer, 404, """{"ok":false,"msg":"not found"}""")
                return
            }

            val payload = gson.fromJson(body, PairRequestBody::class.java)
            val wsUrl = payload.wsUrl?.trim().orEmpty()
            val token = payload.authToken?.trim().orEmpty()
            val gatewayId = payload.gatewayId?.trim().orEmpty()
            if (wsUrl.isBlank() || token.isBlank()) {
                ClawLog.w(TAG, "invalid_body", "remote=$remote")
                writeResponse(writer, 400, """{"ok":false,"msg":"missing ws_url/auth_token"}""")
                return
            }

            ClawLog.bp(TAG, "pair_push_rx", "remote=$remote gateway=$gatewayId url=$wsUrl")
            val handler = PairingBridge.onPairPush
            val ok = handler?.invoke(PairingBridge.PairPayload(wsUrl, token, gatewayId)) == true
            if (ok) {
                writeResponse(writer, 200, """{"ok":true,"msg":"pair accepted"}""")
                ClawLog.bp(TAG, "pair_push_ok", "remote=$remote")
            } else {
                writeResponse(writer, 503, """{"ok":false,"msg":"pair handler unavailable"}""")
                ClawLog.w(TAG, "pair_push_fail", "remote=$remote handler=${handler != null}")
            }
        }.onFailure { e ->
            ClawLog.e(TAG, "connection_error", "remote=$remote ${e.message}", e)
        }
        runCatching { socket.close() }
    }

    private fun readRequest(reader: BufferedReader): Pair<String, String> {
        val requestLine = reader.readLine()?.trim().orEmpty()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read)
        } else {
            ""
        }
        return requestLine to body
    }

    private fun writeResponse(writer: PrintWriter, code: Int, json: String) {
        val text = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        writer.print(
            "HTTP/1.1 $code $text\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${json.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                json,
        )
        writer.flush()
    }
}
