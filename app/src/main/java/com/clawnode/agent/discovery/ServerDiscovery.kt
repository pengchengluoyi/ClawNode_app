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
 * Gateway 发现（OpenClaw 风格：发现与鉴权分离）。
 *
 * 扫描 `_miniorange-gw._tcp`（主）与 legacy `_http._tcp` + `miniorange-*`。
 * 路由以 resolve 的 host/port 为准；TXT 仅用于展示 displayName / path。
 */
object ServerDiscovery {

    private const val TAG = "ServerDiscovery"

    const val GATEWAY_SERVICE_TYPE = "_miniorange-gw._tcp."
    const val LEGACY_HTTP_TYPE = "_http._tcp."
    const val NODE_SERVICE_TYPE = "_miniorange-node._tcp."

    const val INSTANCE_PREFIX = "miniorange-"
    const val DEFAULT_WS_PATH = "/ws"
    const val DEFAULT_PORT = 10104

    private const val COLLECT_MS = 3_500L

    data class Gateway(
        val wsUrl: String,
        val host: String,
        val port: Int,
        val path: String,
        val serviceName: String,
        val serviceType: String,
        val instanceId: String,
        val displayName: String,
        val lanHost: String?
    )

    data class LanNode(
        val serviceName: String,
        val sn: String,
        val model: String,
        val role: String,
        val host: String
    )

    private val mutex = Mutex()

    suspend fun findGateways(context: Context, timeoutMs: Long = NodeConfig.DISCOVERY_TIMEOUT_MS): List<Gateway> =
        mutex.withLock {
            withTimeoutOrNull(timeoutMs) { scanGateways(context.applicationContext) } ?: emptyList()
        }

    suspend fun findLanNodes(context: Context, timeoutMs: Long = NodeConfig.DISCOVERY_TIMEOUT_MS): List<LanNode> =
        mutex.withLock {
            withTimeoutOrNull(timeoutMs) { scanNodes(context.applicationContext) } ?: emptyList()
        }

    /** 已配对 gatewayId 在 mDNS 列表中刷新 IP（鉴权不在此阶段） */
    fun matchPaired(gateways: List<Gateway>, pairedGatewayId: String): Gateway? {
        if (pairedGatewayId.isBlank()) return null
        val key = pairedGatewayId.lowercase()
        return gateways.firstOrNull {
            it.instanceId.equals(key, ignoreCase = true) ||
                it.serviceName.lowercase().startsWith(key)
        }
    }

    fun buildWsUrl(host: String, port: Int, path: String = DEFAULT_WS_PATH): String {
        val normalized = path.ifBlank { DEFAULT_WS_PATH }.let { p ->
            if (p.startsWith("/")) p else "/$p"
        }
        return "ws://$host:$port$normalized"
    }

    private suspend fun scanGateways(context: Context): List<Gateway> {
        val merged = linkedMapOf<String, Gateway>()
        listOf(GATEWAY_SERVICE_TYPE, LEGACY_HTTP_TYPE).forEach { type ->
            discoverGatewaysOfType(context, type).forEach { g ->
                if (type == LEGACY_HTTP_TYPE && !g.instanceId.lowercase().startsWith(INSTANCE_PREFIX)) return@forEach
                merged[g.instanceId.ifBlank { g.serviceName }] = g
            }
        }
        val list = merged.values.sortedBy { it.displayName.lowercase() }
        ClawLog.bp(TAG, "find_gateways", "count=${list.size}")
        return list
    }

    private suspend fun scanNodes(context: Context): List<LanNode> {
        val raw = discoverNodes(context, NODE_SERVICE_TYPE)
        ClawLog.bp(TAG, "find_nodes", "count=${raw.size}")
        return raw.sortedBy { it.sn }
    }

    private fun parseGateway(info: NsdServiceInfo, serviceType: String): Gateway? {
        val lanHost = info.attributes?.get("lanHost")?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        // IMPORTANT: For the connect wsUrl we prefer the address that NSD just successfully resolved
        // (info.host.hostAddress). This is a numeric LAN IP that is guaranteed to be reachable right now.
        // We still record lanHost (the .local name) for display and potential future name-based reconnects.
        // Relying on the .local hostname for immediate connect has proven unreliable on some Android devices
        // (UnknownHostException even when mDNS service discovery itself succeeded).
        val resolvedIp = info.host?.hostAddress?.takeIf { it.isNotBlank() }
        val resolvedName = info.host?.hostName?.takeIf { it.isNotBlank() }
        val reliableHost = resolvedIp ?: lanHost ?: resolvedName
        val port = if (info.port > 0) info.port else DEFAULT_PORT
        if (reliableHost.isNullOrBlank()) return null

        val path = info.attributes?.get("path")?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_WS_PATH
        val displayName = info.attributes?.get("displayName")?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
            ?: info.serviceName.substringBefore('.')
        val instanceId = info.serviceName.substringBefore('.')

        return Gateway(
            wsUrl = buildWsUrl(reliableHost, port, path),
            host = resolvedIp ?: resolvedName ?: reliableHost,
            port = port,
            path = path,
            serviceName = info.serviceName,
            serviceType = serviceType,
            instanceId = instanceId,
            displayName = displayName,
            lanHost = lanHost
        )
    }

    private fun parseNode(info: NsdServiceInfo): LanNode? {
        val host = info.host?.hostAddress?.takeIf { it.isNotBlank() }
            ?: info.host?.hostName?.takeIf { it.isNotBlank() }
            ?: return null
        val sn = info.attributes?.get("sn")?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: info.serviceName.substringBefore('.').removePrefix("clawnode-")
        val model = info.attributes?.get("model")?.toString(Charsets.UTF_8).orEmpty()
        val role = info.attributes?.get("role")?.toString(Charsets.UTF_8) ?: "node"
        return LanNode(
            serviceName = info.serviceName,
            sn = sn,
            model = model.ifBlank { "Android Node" },
            role = role,
            host = host
        )
    }

    private suspend fun discoverGatewaysOfType(context: Context, serviceType: String): List<Gateway> =
        discoverList(context, serviceType) { info, type ->
            parseGateway(info, type)
        }

    private suspend fun discoverNodes(context: Context, serviceType: String): List<LanNode> =
        discoverList(context, serviceType) { info, _ ->
            parseNode(info)
        }

    private suspend fun <T> discoverList(
        context: Context,
        serviceType: String,
        mapResolved: (NsdServiceInfo, String) -> T?
    ): List<T> = suspendCancellableCoroutine { cont ->
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            cont.resume(emptyList())
            return@suspendCancellableCoroutine
        }

        val results = Collections.synchronizedList(mutableListOf<T>())
        var discoveryListener: NsdManager.DiscoveryListener? = null
        var finished = false
        val handler = Handler(Looper.getMainLooper())

        fun finish() {
            if (finished) return
            finished = true
            discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
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
                handler.removeCallbacks(finishRunnable)
                finish()
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        mapResolved(info, serviceType)?.let { results.add(it) }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        runCatching {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
            handler.postDelayed(finishRunnable, COLLECT_MS)
        }.onFailure {
            handler.removeCallbacks(finishRunnable)
            finish()
        }
    }
}
