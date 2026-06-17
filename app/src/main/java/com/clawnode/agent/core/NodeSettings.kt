package com.clawnode.agent.core

/**
 * 节点可持久化配置的不可变快照。
 */
data class NodeSettings(
    val wsUrl: String,
    val authToken: String,
    val nodeSn: String
) {
    /** 是否具备发起连接的最低条件：URL 必须是合法的 ws/wss 地址 */
    val isConnectable: Boolean
        get() = wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://")

    companion object {
        val EMPTY = NodeSettings(wsUrl = "", authToken = "", nodeSn = "")
    }
}
