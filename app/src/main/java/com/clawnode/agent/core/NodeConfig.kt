package com.clawnode.agent.core

/**
 * 全局运行常量（重连/心跳等）。可配置项（URL、token、SN）已迁移到 [ConfigManager]。
 */
object NodeConfig {
    // ---- 重连退避参数 ----
    const val RECONNECT_BASE_DELAY_MS = 1_000L      // 初始退避 1s
    const val RECONNECT_MAX_DELAY_MS = 60_000L      // 上限 60s
    const val RECONNECT_FACTOR = 2.0                // 指数因子
    const val RECONNECT_JITTER_MS = 500L            // 抖动，避免惊群

    // ---- 心跳 ----
    const val PING_INTERVAL_MS = 20_000L            // OkHttp 协议层 ping
    // 应用层心跳：服务端按 heartbeat action 判活（60s 超时），故 20s 上送一次
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    // ---- mDNS 自动发现 ----
    /** 连续连接失败达到此次数后，尝试 LAN mDNS 发现并更新 URL */
    const val DISCOVERY_AFTER_FAILURES = 2
    const val DISCOVERY_TIMEOUT_MS = 12_000L
    /** 单台网关 Token 探测超时 */
    const val PROBE_TIMEOUT_MS = 5_000L
}
