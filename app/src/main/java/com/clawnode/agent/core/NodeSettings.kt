package com.clawnode.agent.core

/**
 * 节点可持久化配置的不可变快照。
 */
data class NodeSettings(
    val wsUrl: String,
    val authToken: String,
    val nodeSn: String,
    /** 用户确认配对后的 gateway 实例 ID（miniorange-xxx） */
    val pairedGatewayId: String = "",
    /** 桌面端解绑后为 true，禁止自动发现/重连直至重新配对 */
    val userUnpaired: Boolean = false,
) {
    /** 使用 mDNS 自动发现，无需手填 IP */
    val usesAutoDiscovery: Boolean
        get() = !userUnpaired && (
            wsUrl.isBlank() ||
                wsUrl.equals("auto", ignoreCase = true) ||
                wsUrl.equals(AUTO_DISCOVERY_URL, ignoreCase = true)
            )

    /** 是否具备发起连接的最低条件 */
    val isConnectable: Boolean
        get() = when {
            userUnpaired -> false
            usesAutoDiscovery -> true
            else -> wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://")
        }

    companion object {
        const val AUTO_DISCOVERY_URL = "ws://auto"

        val EMPTY = NodeSettings(wsUrl = "", authToken = "", nodeSn = "")

        fun isValidUrlInput(raw: String): Boolean {
            val url = raw.trim()
            if (url.isBlank() || url.equals("auto", ignoreCase = true)) return true
            if (url.equals(AUTO_DISCOVERY_URL, ignoreCase = true)) return true
            return url.startsWith("ws://") || url.startsWith("wss://")
        }
    }
}
