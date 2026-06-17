package com.clawnode.agent.core

/**
 * 全局运行配置。Gateway 地址等可在 MainActivity 中持久化后注入。
 * 这里用单一可变对象，避免到处传参；真实生产可换成 DataStore。
 */
object NodeConfig {
    /** 网关 WebSocket 地址 */
    @Volatile
    var gatewayUrl: String = "wss://gateway.example.com/node"

    /** 节点标识，握手时可带上（示例用，可选） */
    @Volatile
    var nodeId: String = "claw-node-001"

    // ---- 重连退避参数 ----
    const val RECONNECT_BASE_DELAY_MS = 1_000L      // 初始退避 1s
    const val RECONNECT_MAX_DELAY_MS = 60_000L      // 上限 60s
    const val RECONNECT_FACTOR = 2.0                // 指数因子
    const val RECONNECT_JITTER_MS = 500L            // 抖动，避免惊群

    // ---- 心跳 ----
    const val PING_INTERVAL_MS = 20_000L
}
