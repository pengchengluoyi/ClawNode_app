package com.clawnode.agent.model

import com.google.gson.annotations.SerializedName

/**
 * 网关下发的控制帧（非动作指令）信封。
 *
 * 上行消息有两类：
 *  - 动作指令：带 `action_type`（见 [Command]）；
 *  - 控制帧：带 `type`，如鉴权结果 AUTH_OK / AUTH_FAILED。
 *
 * WsManager 先用本类窥探 `type` 字段区分两类，避免误把控制帧当指令分发。
 */
data class GatewayControl(
    @SerializedName("type") val type: String? = null,
    @SerializedName("data") val data: Data? = null
) {
    data class Data(
        @SerializedName("message") val message: String? = null
    )

    companion object {
        const val TYPE_AUTH_OK = "AUTH_OK"
        const val TYPE_AUTH_FAILED = "AUTH_FAILED"
    }
}
