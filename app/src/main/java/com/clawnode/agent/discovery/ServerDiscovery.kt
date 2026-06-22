package com.clawnode.agent.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.clawnode.agent.core.ClawLog
import com.clawnode.agent.core.NodeConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.coroutines.resume

/**
 * 与 MiniOrangeServer [register_mdns] 对齐：
 * - 类型：`_http._tcp`
 * - 实例名：`miniorange-{hostname}`
 * - 主机名：`miniorange-{hostname}.local`
 * - WebSocket 路径固定 `/ws`（mDNS 不带 path，由客户端补全）
 *
 * 多台 Server 同网段时：扫描全部 miniorange-*，逐个用 Token 探测，直到配对成功。
 */
object ServerDiscovery {

    private const val TAG = "ServerDiscovery"

    /** 与 MiniOrangeServer/main.py register_mdns 一致 */
    const val SERVICE_TYPE = "_http._tcp."

    const val INSTANCE_PREFIX = "miniorange-"
    const val DEFAULT_WS_PATH = "/ws"
    const val DEFAULT_PORT = 10104

    private const val COLLECT_MS = 3_500L

    data class Result(
        val wsUrl: String,
        val host: String,
        val port: Int,
        val path: String,
        val serviceName: String
    )

    private val mutex = Mutex()

    /**
     * mDNS 扫描所有 miniorange-*，按顺序用 [token] 探测 WS，Token 不匹配则试下一台。
     */
    suspend fun pairByToken(
        context: Context,
        token: String,
        nodeSn: String,
        timeoutMs: Long = NodeConfig.DISCOVERY_TIMEOUT_MS
    ): Result? = mutex.withLock {
        if (token.isBlank()) {
            ClawLog.w(TAG, "pair_skip", "empty token")
            return@withLock null
        }
        withTimeoutOrNull(timeoutMs) {
            val candidates = findAllInternal(context.applicationContext)
            if (candidates.isEmpty()) {
                ClawLog.w(TAG, "pair_no_candidates", "type=$SERVICE_TYPE prefix=$INSTANCE_PREFIX")
                return@withTimeoutOrNull null
            }
            ClawLog.bp(TAG, "pair_start", "candidates=${candidates.size}")
            for (candidate in candidates) {
                ClawLog.bp(TAG, "pair_try", "service=${candidate.serviceName} url=${candidate.wsUrl}")
                when (
                    val probe = GatewayProbe.probe(
                        wsUrl = candidate.wsUrl,
                        token = token,
                        nodeSn = nodeSn
                    )
                ) {
                    is GatewayProbe.ProbeResult.Accepted -> {
                        ClawLog.bp(TAG, "pair_ok", "service=${candidate.serviceName} url=${candidate.wsUrl}")
                        return@withTimeoutOrNull candidate
                    }
                    is GatewayProbe.ProbeResult.AuthRejected -> {
                        ClawLog.bp(TAG, "pair_token_mismatch", "service=${candidate.serviceName} try next")
                    }
                    is GatewayProbe.ProbeResult.Failed -> {
                        ClawLog.w(TAG, "pair_probe_fail", "service=${candidate.serviceName} msg=${probe.message}")
                    }
                }
            }
            ClawLog.w(TAG, "pair_exhausted", "no server accepted token")
            null
        }
    }

    suspend fun findAll(context: Context, timeoutMs: Long = NodeConfig.DISCOVERY_TIMEOUT_MS): List<Result> =
        mutex.withLock {
            withTimeoutOrNull(timeoutMs) {
                findAllInternal(context.applicationContext)
            } ?: emptyList()
        }

    fun buildWsUrl(host: String, port: Int, path: String = DEFAULT_WS_PATH): String {
        val normalized = path.ifBlank { DEFAULT_WS_PATH }.let { p ->
            if (p.startsWith("/")) p else "/$p"
        }
        return "ws://$host:$port$normalized"
    }

    private suspend fun findAllInternal(context: Context): List<Result> {
        val raw = discoverType(context, SERVICE_TYPE)
        val filtered = raw.filter { isMiniOrangeInstance(it.serviceName) }
            .sortedBy { it.serviceName.lowercase() }
        ClawLog.bp(TAG, "find_all", "total=${raw.size} miniorange=${filtered.size}")
        return filtered
    }

    private fun isMiniOrangeInstance(serviceName: String): Boolean {
        val base = serviceName.substringBefore('.').lowercase()
        return base.startsWith(INSTANCE_PREFIX)
    }

    private suspend fun discoverType(context: Context, serviceType: String): List<Result> =
        suspendCancellableCoroutine { cont ->
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsd == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            val results = Collections.synchronizedList(mutableListOf<Result>())
            var discoveryListener: NsdManager.DiscoveryListener? = null
            var finished = false
            val handler = Handler(Looper.getMainLooper())

            fun finish() {
                if (finished) return
                finished = true
                discoveryListener?.let { listener ->
                    runCatching { nsd.stopServiceDiscovery(listener) }
                }
                discoveryListener = null
                if (!cont.isCompleted) cont.resume(results.toList())
            }

            val finishRunnable = Runnable { finish() }
            cont.invokeOnCancellation {
                handler.removeCallbacks(finishRunnable)
                finish()
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    ClawLog.w(TAG, "discovery_start_fail", "type=$type code=$errorCode")
                    handler.removeCallbacks(finishRunnable)
                    finish()
                }

                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                    ClawLog.w(TAG, "discovery_stop_fail", "type=$type code=$errorCode")
                }

                override fun onDiscoveryStarted(type: String) {
                    ClawLog.bp(TAG, "discovery_started", "type=$type collectMs=$COLLECT_MS")
                }

                override fun onDiscoveryStopped(type: String) {
                    ClawLog.bp(TAG, "discovery_stopped", "type=$type")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.startsWith("_http._tcp")) return
                    if (!isMiniOrangeInstance(serviceInfo.serviceName)) return

                    ClawLog.bp(TAG, "service_found", "name=${serviceInfo.serviceName}")
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            ClawLog.w(TAG, "resolve_fail", "name=${info.serviceName} code=$errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress?.takeIf { it.isNotBlank() }
                                ?: info.host?.hostName?.takeIf { it.isNotBlank() }
                            val port = if (info.port > 0) info.port else DEFAULT_PORT
                            if (host.isNullOrBlank()) {
                                ClawLog.w(TAG, "resolve_invalid", "name=${info.serviceName}")
                                return
                            }
                            val path = info.attributes?.get("path")?.toString(Charsets.UTF_8)
                                ?.takeIf { it.isNotBlank() }
                                ?: DEFAULT_WS_PATH
                            results.add(
                                Result(
                                    wsUrl = buildWsUrl(host, port, path),
                                    host = host,
                                    port = port,
                                    path = path,
                                    serviceName = info.serviceName
                                )
                            )
                            ClawLog.bp(TAG, "service_resolved", "host=$host port=$port name=${info.serviceName}")
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    ClawLog.bp(TAG, "service_lost", "name=${serviceInfo.serviceName}")
                }
            }

            runCatching {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
                handler.postDelayed(finishRunnable, COLLECT_MS)
            }.onFailure { e ->
                ClawLog.e(TAG, "discover_services_fail", e.message ?: "unknown", e)
                handler.removeCallbacks(finishRunnable)
                finish()
            }
        }
}
