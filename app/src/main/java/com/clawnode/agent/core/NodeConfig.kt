package com.clawnode.agent.core

/**
 * 全局运行常量（重连/心跳等）。可配置项（URL、token）已迁移到 [ConfigManager]。
 */
object NodeConfig {
    /** 节点标识，握手时带上 */
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
