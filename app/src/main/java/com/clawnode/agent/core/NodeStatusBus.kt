package com.clawnode.agent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内状态总线。
 *
 * WsManager 的 [ConnectionState] 属于 AccessibilityService 内部，UI（Activity）
 * 处于不同组件、无法直接持有该实例。服务在装配后把 WsManager.state 转发到这里，
 * UI 只订阅本总线即可，二者解耦。服务销毁时会复位为 [ConnectionState.Idle]。
 */
object NodeStatusBus {
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    fun publish(state: ConnectionState) {
        _connection.value = state
    }

    fun reset() {
        _connection.value = ConnectionState.Idle
    }
}
