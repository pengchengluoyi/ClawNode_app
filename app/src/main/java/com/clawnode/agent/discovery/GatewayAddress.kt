package com.clawnode.agent.discovery

import java.net.InetAddress

/**
 * 网关连接地址选择：局域网直连优先于 Tailscale/CGNAT 等 overlay（如 100.64.0.0/10）。
 *
 * 服务端开系统代理 / VPN 时，mDNS 可能解析到 overlay IP，而手机与 Mac 仍在同一 Wi-Fi，
 * 此时应优先 RFC1918 私网地址或配对 HTTP 请求来源 IP。
 */
object GatewayAddress {

    fun extractHostFromWsUrl(wsUrl: String): String? {
        val trimmed = wsUrl.trim()
        val match = WS_HOST_REGEX.find(trimmed) ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
    }

    fun rewriteWsUrlHost(wsUrl: String, newHost: String): String {
        val host = newHost.trim()
        if (host.isBlank()) return wsUrl
        return WS_HOST_REGEX.replace(wsUrl.trim(), "${'$'}1$host")
    }

    fun isPrivateLanIp(host: String): Boolean {
        val normalized = host.trim()
        if (normalized.isBlank()) return false
        return runCatching {
            val bytes = InetAddress.getByName(normalized).address
            if (bytes.size != 4) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            when {
                b0 == 10 -> true
                b0 == 172 && b1 in 16..31 -> true
                b0 == 192 && b1 == 168 -> true
                else -> false
            }
        }.getOrDefault(false)
    }

    /** RFC6598 CGNAT / Tailscale 等 overlay，手机在普通 Wi-Fi 上通常不可达。 */
    fun isOverlayOrCgnatIp(host: String): Boolean {
        val normalized = host.trim()
        if (normalized.isBlank()) return false
        return runCatching {
            val bytes = InetAddress.getByName(normalized).address
            if (bytes.size != 4) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            b0 == 100 && b1 in 64..127
        }.getOrDefault(false)
    }

    /**
     * 从 mDNS resolve 结果挑选更可能可达的连接 host。
     * overlay IP 时回退到 lanHost（私网 IP 或 .local 名称）。
     */
    fun pickConnectHost(resolvedIp: String?, lanHost: String?, resolvedName: String?): String? {
        if (!resolvedIp.isNullOrBlank() && isPrivateLanIp(resolvedIp)) return resolvedIp
        if (!resolvedIp.isNullOrBlank() && !isOverlayOrCgnatIp(resolvedIp)) return resolvedIp

        if (!lanHost.isNullOrBlank()) {
            if (isPrivateLanIp(lanHost)) return lanHost
            if (lanHost.contains(".local", ignoreCase = true)) return lanHost
        }

        return resolvedIp?.takeIf { it.isNotBlank() }
            ?: lanHost?.takeIf { it.isNotBlank() }
            ?: resolvedName?.takeIf { it.isNotBlank() }
    }

    /** 若 ws_url 指向 overlay，而 remote 是同一局域网的私网 IP，则改用 remote。 */
    fun preferLanRemoteWsUrl(wsUrl: String, remoteHost: String): String {
        val urlHost = extractHostFromWsUrl(wsUrl) ?: return wsUrl
        if (!isOverlayOrCgnatIp(urlHost)) return wsUrl
        if (!isPrivateLanIp(remoteHost)) return wsUrl
        return rewriteWsUrlHost(wsUrl, remoteHost)
    }

    fun buildCandidateWsUrls(gateway: ServerDiscovery.Gateway): List<String> {
        val ordered = linkedSetOf<String>()
        ordered.add(gateway.wsUrl)

        val lanHost = gateway.lanHost?.trim().orEmpty()
        if (lanHost.isNotBlank() && lanHost != gateway.host) {
            ordered.add(ServerDiscovery.buildWsUrl(lanHost, gateway.port, gateway.path))
        }

        val host = gateway.host.trim()
        if (host.isNotBlank()) {
            val hostUrl = ServerDiscovery.buildWsUrl(host, gateway.port, gateway.path)
            ordered.add(hostUrl)
            if (isOverlayOrCgnatIp(host) && !lanHost.isNullOrBlank()) {
                // overlay 主地址时，把 lanHost 候选提前
                val lanUrl = ServerDiscovery.buildWsUrl(lanHost, gateway.port, gateway.path)
                return listOf(lanUrl, hostUrl, gateway.wsUrl)
                    .distinct()
                    .filter { it.isNotBlank() }
            }
        }
        return ordered.filter { it.isNotBlank() }
    }

    private val WS_HOST_REGEX = Regex("^(wss?://)([^:/]+)")
}
