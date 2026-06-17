package com.clawnode.agent.core

/** WebSocket 连接状态，供 UI / 日志观察 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState

    /** TCP/WS 已连接，但尚未通过网关鉴权 */
    data object Connected : ConnectionState

    /** 已连接且 AUTH 握手通过——这是可以正常收发指令的终态 */
    data object Authenticated : ConnectionState

    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : ConnectionState
    data class Disconnected(val reason: String) : ConnectionState

    /** 鉴权失败：已熔断重连，需用户更新 Token */
    data class AuthFailed(val reason: String) : ConnectionState
}
