package com.clawnode.agent.model

import com.google.gson.annotations.SerializedName

/**
 * 上行注册帧。与服务端 MiniOrangeServer 的 register 协议对齐：
 * 顶层 action="register_clawnode"，设备信息放在 data 里。
 *
 * 服务端据此把本节点写入设备表并标记为直连节点（direct_nodes），
 * 之后即出现在前端的 get_device_list 中。
 */
data class RegisterFrame(
    @SerializedName("action") val action: String = "register_clawnode",
    @SerializedName("data") val data: Data
) {
    data class Data(
        @SerializedName("sn") val sn: String,
        @SerializedName("type") val type: String = "android_direct",
        @SerializedName("role") val role: String = "node",
        @SerializedName("model") val model: String,
        @SerializedName("os_version") val osVersion: String,
        @SerializedName("resolution") val resolution: String,
        @SerializedName("app_version") val appVersion: String? = null
    )
}

/**
 * 上行心跳帧。服务端 monitor_heartbeats 以 60s 为阈值判离线，
 * 看的是应用层 heartbeat action（而非 WS 协议层 ping），故需周期上送。
 */
data class HeartbeatFrame(
    @SerializedName("action") val action: String = "heartbeat",
    @SerializedName("data") val data: Data
) {
    data class Data(
        @SerializedName("sn") val sn: String
    )
}
