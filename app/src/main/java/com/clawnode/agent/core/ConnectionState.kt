package com.clawnode.agent.core

/** WebSocket 连接状态，供 UI / 日志观察 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : ConnectionState
    data class Disconnected(val reason: String) : ConnectionState
}
