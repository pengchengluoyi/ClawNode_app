package com.clawnode.agent.model

import com.google.gson.annotations.SerializedName

/**
 * 网关下发指令的统一信封。
 *
 * 由于不同 action_type 的 payload 字段不同，这里用一个宽松的 [Payload]
 * 承载所有可能字段（均为可空），由分发器按 action_type 取用对应字段。
 */
data class Command(
    @SerializedName("trace_id") val traceId: String,
    @SerializedName("action_type") val actionType: String,
    @SerializedName("payload") val payload: Payload? = null
) {
    data class Payload(
        // TAP / SWIPE 起点
        @SerializedName("x") val x: Int? = null,
        @SerializedName("y") val y: Int? = null,
        // SWIPE 终点（协议补充字段：原始协议仅给出 x/y，滑动必须有终点）
        @SerializedName("x2") val x2: Int? = null,
        @SerializedName("y2") val y2: Int? = null,
        // 手势耗时（TAP 的按压时长 / SWIPE 的滑动时长）
        @SerializedName("duration_ms") val durationMs: Long? = null,
        // GET_SCREENSHOT 的 JPEG 质量 0-100
        @SerializedName("quality") val quality: Int? = null,
        // START_STREAM 的目标帧率
        @SerializedName("fps") val fps: Int? = null,
        // KEY_EVENT 的按键：back / home（或 Android keycode）
        @SerializedName("keyevent") val keyevent: String? = null,
        // STOP_APP / CLOSE_APP 的目标包名
        @SerializedName("package") val packageName: String? = null,
        // OPEN_APP 可选 Activity 全类名
        @SerializedName("activity") val activity: String? = null,
        /** EXPORT_LOGS：最近 N 分钟，默认 5 */
        @SerializedName("minutes") val minutes: Int? = null
    )

    companion object ActionType {
        const val TAP = "TAP"
        const val SWIPE = "SWIPE"
        const val WAKE_UP = "WAKE_UP"
        const val GET_SCREENSHOT = "GET_SCREENSHOT"
        const val START_STREAM = "START_STREAM"
        const val STOP_STREAM = "STOP_STREAM"
        const val KEY_EVENT = "KEY_EVENT"
        const val OPEN_APP = "OPEN_APP"
        const val CLOSE_APP = "CLOSE_APP"
        /** @deprecated 请用 [CLOSE_APP]；保留兼容旧服务端 */
        const val STOP_APP = "STOP_APP"
        /** 部分服务端别名 */
        const val START_APP = "START_APP"
        const val KILL_APP = "KILL_APP"
        const val CLEAR_APP_CACHE = "CLEAR_APP_CACHE"
        const val EXPORT_LOGS = "EXPORT_LOGS"
    }
}
