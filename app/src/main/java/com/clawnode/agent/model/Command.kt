package com.clawnode.agent.model

import com.google.gson.annotations.SerializedName

/**
 * ClawNode 与服务端统一使用的下发指令格式（与服务端 send_command 及直连下发完全一致）。
 *
 * 服务端下发示例：
 *   { "type": "command", "command": "INSTALL_APK", "params": { "url": "...", "file_name": "..." } }
 *   { "command": "TAP", "params": { "x": 100, "y": 200, "duration_ms": 100 } }
 *   { "type": "command", "command": "control", "params": { "action": "tap", "x": 100, "y": 200 } }
 *
 * trace_id / req_id 缺失时会自动兜底生成 safeTraceId。
 * 接收端对旧的 action_type/payload 格式有最小兼容转换（仅在边界做 key 重命名）。
 */
data class Command(
    @SerializedName("trace_id") val traceId: String? = null,
    @SerializedName("req_id") val reqId: String? = null,
    @SerializedName("type") val type: String? = null,
    /** 服务端使用的动作字段： "INSTALL_APK", "TAP", "control", "EXPORT_LOGS" 等 */
    @SerializedName("command") val command: String? = null,
    /** 服务端使用的参数对象（对应历史 payload） */
    @SerializedName("params") val params: Params? = null,
    @SerializedName("timestamp") val timestamp: String? = null
) {
    /** 永远非空的 traceId */
    val safeTraceId: String
        get() = traceId ?: reqId ?: "auto-${System.currentTimeMillis()}"

    /** action 别名，便于统一引用 */
    val action: String get() = command ?: ""

    data class Params(
        // TAP / SWIPE 起点
        @SerializedName("x") val x: Int? = null,
        @SerializedName("y") val y: Int? = null,
        // SWIPE 终点
        @SerializedName("x2") val x2: Int? = null,
        @SerializedName("y2") val y2: Int? = null,
        // 手势耗时
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
        @SerializedName("minutes") val minutes: Int? = null,
        /** RUN_SHELL：受限 shell 命令（注意与外层 command 区分） */
        @SerializedName("command") val command: String? = null,
        /** INSTALL_APK：APK 下载地址 */
        @SerializedName("url") val url: String? = null,
        /** INSTALL_APK：可选文件名 */
        @SerializedName("file_name") val fileName: String? = null,
        /** SET_CLIPBOARD / INPUT_TEXT：文本内容 */
        @SerializedName("text") val text: String? = null,
        /** EXEC_SCRIPT：脚本语言 dsl | js */
        @SerializedName("language") val language: String? = null,
        /** EXEC_SCRIPT：脚本正文（DSL JSON 或 JavaScript） */
        @SerializedName("script") val script: String? = null,
        /** EXEC_SCRIPT：执行超时毫秒 */
        @SerializedName("timeout_ms") val scriptTimeoutMs: Long? = null,
        /** 当 command == "control" 时，子动作如 "tap"、"swipe" */
        @SerializedName("action") val action: String? = null
    )

    companion object ActionType {
        const val TAP = "TAP"
        const val SWIPE = "SWIPE"
        const val WAKE_UP = "WAKE_UP"
        const val GET_SCREENSHOT = "GET_SCREENSHOT"
        const val START_STREAM = "START_STREAM"
        const val STOP_STREAM = "STOP_STREAM"
        const val GET_FOREGROUND_APP = "GET_FOREGROUND_APP"
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
        const val RUN_SHELL = "RUN_SHELL"
        const val INSTALL_APK = "INSTALL_APK"
        const val SET_CLIPBOARD = "SET_CLIPBOARD"
        const val INPUT_TEXT = "INPUT_TEXT"
        const val EXEC_SCRIPT = "EXEC_SCRIPT"
        const val CONTROL = "CONTROL"
        const val GET_INSTALLED_APPS = "GET_INSTALLED_APPS"
    }
}
