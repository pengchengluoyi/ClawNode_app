package com.clawnode.agent.pairing

/**
 * 前台服务 HTTP 配对入口与 [WsManager] 之间的桥接。
 */
object PairingBridge {

    data class PairPayload(
        val wsUrl: String,
        val authToken: String,
        val gatewayId: String,
    )

    @Volatile
    var onPairPush: (suspend (PairPayload) -> Boolean)? = null
}
