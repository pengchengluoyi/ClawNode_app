package com.clawnode.agent.model

import com.google.gson.annotations.SerializedName

/**
 * 连接建立后发送的首帧鉴权握手。
 *
 * 与网关约定：节点在 onOpen 后立刻上送此帧，网关校验 token 通过后
 * 才会下发后续指令。token 同时也通过 HTTP Header 携带（双保险）。
 */
data class AuthHandshake(
    @SerializedName("type") val type: String = TYPE,
    @SerializedName("data") val data: Data
) {
    data class Data(
        @SerializedName("node_id") val nodeId: String,
        @SerializedName("auth_token") val authToken: String
    )

    companion object {
        const val TYPE = "AUTH"
    }
}
