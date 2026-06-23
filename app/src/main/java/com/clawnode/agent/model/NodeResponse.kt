package com.clawnode.agent.model

import com.google.gson.annotations.SerializedName

/**
 * 节点回传给网关的统一信封。
 *
 * 提供工厂方法构造不同 type 的响应，避免在业务代码里手搓 JSON 结构。
 */
data class NodeResponse(
    @SerializedName("trace_id") val traceId: String,
    @SerializedName("type") val type: String,
    @SerializedName("data") val data: Any
) {
    /** 动作执行结果 data 体 */
    data class ActionResultData(
        @SerializedName("status") val status: String,
        @SerializedName("message") val message: String
    )

    /** 截图结果 data 体 */
    data class ScreenshotData(
        @SerializedName("format") val format: String,
        @SerializedName("base64_image") val base64Image: String
    )

    /** 推流状态/帧元信息 data 体 */
    data class StreamData(
        @SerializedName("status") val status: String,
        @SerializedName("message") val message: String? = null,
        @SerializedName("base64_image") val base64Image: String? = null,
        @SerializedName("width") val width: Int? = null,
        @SerializedName("height") val height: Int? = null
    )

    /** 安装/更新进度 data 体（自更新和其他 APK 安装共用） */
    data class InstallProgressData(
        @SerializedName("stage") val stage: String,
        @SerializedName("percent") val percent: Int? = null,
        @SerializedName("message") val message: String? = null,
        @SerializedName("version") val version: String? = null,
        @SerializedName("file_name") val fileName: String? = null
    )

    companion object {
        const val TYPE_ACTION_RESULT = "ACTION_RESULT"
        const val TYPE_SCREENSHOT_RESULT = "SCREENSHOT_RESULT"
        const val TYPE_STREAM_FRAME = "STREAM_FRAME"
        const val TYPE_STREAM_STATUS = "STREAM_STATUS"
        const val TYPE_INSTALL_PROGRESS = "INSTALL_PROGRESS"

        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"

        // Install / update stages (for progress bar on server/frontend)
        const val STAGE_DETECTED = "detected"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_DOWNLOADED = "downloaded"
        const val STAGE_INSTALLING = "installing"
        const val STAGE_AWAITING_CONFIRM = "awaiting_confirm"
        const val STAGE_INSTALLED = "installed"
        const val STAGE_RESTARTED = "restarted"
        const val STAGE_SUCCESS = "success"
        const val STAGE_FAILED = "failed"

        fun actionResult(traceId: String, success: Boolean, message: String) = NodeResponse(
            traceId = traceId,
            type = TYPE_ACTION_RESULT,
            data = ActionResultData(
                status = if (success) STATUS_SUCCESS else STATUS_FAILED,
                message = message
            )
        )

        fun shellResult(traceId: String, success: Boolean, stdout: String, stderr: String = "") = NodeResponse(
            traceId = traceId,
            type = TYPE_ACTION_RESULT,
            data = mapOf(
                "status" to if (success) STATUS_SUCCESS else STATUS_FAILED,
                "message" to stdout,
                "stdout" to stdout,
                "stderr" to stderr,
            )
        )

        fun screenshot(traceId: String, base64: String, format: String = "jpeg") = NodeResponse(
            traceId = traceId,
            type = TYPE_SCREENSHOT_RESULT,
            data = ScreenshotData(format = format, base64Image = base64)
        )

        fun streamFrame(traceId: String, base64: String, width: Int, height: Int) = NodeResponse(
            traceId = traceId,
            type = TYPE_STREAM_FRAME,
            data = StreamData(
                status = STATUS_SUCCESS,
                base64Image = base64,
                width = width,
                height = height
            )
        )

        fun streamStatus(traceId: String, success: Boolean, message: String) = NodeResponse(
            traceId = traceId,
            type = TYPE_STREAM_STATUS,
            data = StreamData(
                status = if (success) STATUS_SUCCESS else STATUS_FAILED,
                message = message
            )
        )

        fun installProgress(
            traceId: String? = null,
            stage: String,
            percent: Int? = null,
            message: String? = null,
            version: String? = null,
            fileName: String? = null
        ) = NodeResponse(
            traceId = traceId ?: "self-update",
            type = TYPE_INSTALL_PROGRESS,
            data = InstallProgressData(
                stage = stage,
                percent = percent,
                message = message,
                version = version,
                fileName = fileName
            )
        )
    }
}
