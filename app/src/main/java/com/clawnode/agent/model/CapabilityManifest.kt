package com.clawnode.agent.model

import com.clawnode.agent.BuildConfig
import com.google.gson.annotations.SerializedName

/**
 * 能力清单：向服务端声明本节点支持的全部指令、调用方式、参数与示例，便于
 * server 端可视化展示与表单化调用。
 *
 * 通过两种方式提供给服务端：
 * 1. 鉴权成功后主动上报一次（CAPABILITIES 帧）；
 * 2. 服务端可随时下发 GET_CAPABILITIES 指令拉取最新清单。
 */
object CapabilityManifest {

    /** 参数说明。type: string|int|long|float|bool；required 是否必填。 */
    data class ParamSpec(
        @SerializedName("name") val name: String,
        @SerializedName("type") val type: String,
        @SerializedName("required") val required: Boolean,
        @SerializedName("description") val description: String,
        @SerializedName("default") val default: Any? = null,
        @SerializedName("example") val example: Any? = null,
    )

    /** 单个指令能力。 */
    data class CapabilitySpec(
        @SerializedName("command") val command: String,
        @SerializedName("title") val title: String,
        @SerializedName("category") val category: String,
        @SerializedName("description") val description: String,
        @SerializedName("params") val params: List<ParamSpec>,
        /** 一个可直接下发的完整 params 示例 */
        @SerializedName("example") val example: Map<String, Any?>,
        @SerializedName("requires_accessibility") val requiresAccessibility: Boolean = false,
        @SerializedName("requires_shizuku") val requiresShizuku: Boolean = false,
    )

    /** 完整清单（含版本号），即上报/返回给服务端的结构。 */
    data class Manifest(
        @SerializedName("version_name") val versionName: String,
        @SerializedName("version_code") val versionCode: Int,
        @SerializedName("protocol") val protocol: String,
        @SerializedName("capabilities") val capabilities: List<CapabilitySpec>,
    )

    fun build(): Manifest = Manifest(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        protocol = "command+params (type:\"command\")",
        capabilities = CAPABILITIES,
    )

    private val CAPABILITIES: List<CapabilitySpec> = listOf(
        CapabilitySpec(
            command = "TAP",
            title = "点击",
            category = "手势",
            description = "在屏幕坐标 (x,y) 点击一次。",
            params = listOf(
                ParamSpec("x", "int", true, "横坐标(px)", example = 540),
                ParamSpec("y", "int", true, "纵坐标(px)", example = 1200),
                ParamSpec("duration_ms", "long", false, "按压时长(ms)", default = 80, example = 80),
            ),
            example = mapOf("x" to 540, "y" to 1200, "duration_ms" to 80),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "SWIPE",
            title = "滑动",
            category = "手势",
            description = "从 (x,y) 滑动到 (x2,y2)。",
            params = listOf(
                ParamSpec("x", "int", true, "起点横坐标", example = 540),
                ParamSpec("y", "int", true, "起点纵坐标", example = 1600),
                ParamSpec("x2", "int", true, "终点横坐标", example = 540),
                ParamSpec("y2", "int", true, "终点纵坐标", example = 600),
                ParamSpec("duration_ms", "long", false, "滑动时长(ms)", default = 300, example = 300),
            ),
            example = mapOf("x" to 540, "y" to 1600, "x2" to 540, "y2" to 600, "duration_ms" to 300),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "KEY_EVENT",
            title = "按键",
            category = "手势",
            description = "全局按键：back / home / recents / notifications / paste。",
            params = listOf(
                ParamSpec("keyevent", "string", true, "按键名：back|home|recents|notifications|paste", example = "back"),
            ),
            example = mapOf("keyevent" to "home"),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "INPUT_TEXT",
            title = "输入文字",
            category = "输入",
            description = "向当前焦点输入框输入文本。Shizuku 可用时经 ClawNode 输入法注入(支持中文)，否则回退无障碍。可选 x,y 先点击聚焦输入框。",
            params = listOf(
                ParamSpec("text", "string", true, "要输入的文本", example = "你好"),
                ParamSpec("x", "int", false, "可选：先点击该横坐标聚焦输入框"),
                ParamSpec("y", "int", false, "可选：先点击该纵坐标聚焦输入框"),
            ),
            example = mapOf("text" to "hello world"),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "SET_CLIPBOARD",
            title = "设置剪贴板",
            category = "输入",
            description = "把文本写入系统剪贴板。",
            params = listOf(
                ParamSpec("text", "string", true, "剪贴板内容", example = "copied text"),
            ),
            example = mapOf("text" to "copied text"),
        ),
        CapabilitySpec(
            command = "OPEN_APP",
            title = "打开应用",
            category = "应用",
            description = "按包名启动应用，可选指定 Activity 全类名。",
            params = listOf(
                ParamSpec("package", "string", true, "应用包名", example = "com.android.settings"),
                ParamSpec("activity", "string", false, "可选 Activity 全类名"),
            ),
            example = mapOf("package" to "com.android.settings"),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "CLOSE_APP",
            title = "关闭应用(回到桌面)",
            category = "应用",
            description = "将指定应用切到后台(performGlobalAction HOME)。",
            params = listOf(
                ParamSpec("package", "string", true, "应用包名", example = "com.mathmagic.zaohaowu"),
            ),
            example = mapOf("package" to "com.mathmagic.zaohaowu"),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "KILL_APP",
            title = "杀应用进程",
            category = "应用",
            description = "回桌面 + killBackgroundProcesses。",
            params = listOf(
                ParamSpec("package", "string", true, "应用包名", example = "com.mathmagic.zaohaowu"),
            ),
            example = mapOf("package" to "com.mathmagic.zaohaowu"),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "CLEAR_APP_CACHE",
            title = "清应用缓存",
            category = "应用",
            description = "杀进程后打开应用详情页(便于清缓存)。",
            params = listOf(
                ParamSpec("package", "string", true, "应用包名", example = "com.mathmagic.zaohaowu"),
            ),
            example = mapOf("package" to "com.mathmagic.zaohaowu"),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "GET_FOREGROUND_APP",
            title = "获取前台应用",
            category = "应用",
            description = "返回当前前台应用包名。",
            params = emptyList(),
            example = emptyMap(),
        ),
        CapabilitySpec(
            command = "GET_INSTALLED_APPS",
            title = "获取已安装应用",
            category = "应用",
            description = "返回可启动应用的包名与名称列表。",
            params = emptyList(),
            example = emptyMap(),
        ),
        CapabilitySpec(
            command = "GET_SCREENSHOT",
            title = "截图",
            category = "视觉",
            description = "返回一帧 JPEG(base64)。优先走无障碍 takeScreenshot，必要时回退 MediaProjection。",
            params = listOf(
                ParamSpec("quality", "int", false, "JPEG 质量 0-100", default = 80, example = 80),
            ),
            example = mapOf("quality" to 80),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "START_STREAM",
            title = "开始投屏",
            category = "视觉",
            description = "以指定帧率持续推送屏幕帧(需屏幕捕获授权)。",
            params = listOf(
                ParamSpec("fps", "int", false, "目标帧率 1-30", default = 15, example = 15),
            ),
            example = mapOf("fps" to 15),
        ),
        CapabilitySpec(
            command = "STOP_STREAM",
            title = "停止投屏",
            category = "视觉",
            description = "停止屏幕推流。",
            params = emptyList(),
            example = emptyMap(),
        ),
        CapabilitySpec(
            command = "WAKE_UP",
            title = "唤醒屏幕",
            category = "系统",
            description = "点亮屏幕并尝试解锁(已亮且未锁时跳过)。",
            params = emptyList(),
            example = emptyMap(),
        ),
        CapabilitySpec(
            command = "RUN_SHELL",
            title = "执行 Shell",
            category = "系统",
            description = "执行 shell 命令。Shizuku 可用时以 shell uid 执行(能力完整，无白名单)，否则以应用 uid 执行。",
            params = listOf(
                ParamSpec("command", "string", true, "shell 命令", example = "pm list packages"),
            ),
            example = mapOf("command" to "getprop ro.product.model"),
            requiresShizuku = false,
        ),
        CapabilitySpec(
            command = "EXEC_SCRIPT",
            title = "执行脚本",
            category = "系统",
            description = "执行 DSL(JSON 步骤) 或 JavaScript(Rhino) 脚本，可组合 claw.* API(tap/swipe/openApp/inputText/shell 等)。",
            params = listOf(
                ParamSpec("script", "string", true, "脚本正文(DSL JSON 或 JS)", example = "claw.tap(540,1200);"),
                ParamSpec("language", "string", false, "dsl | js", default = "dsl", example = "js"),
                ParamSpec("timeout_ms", "long", false, "执行超时(ms)", default = 60000, example = 60000),
            ),
            example = mapOf(
                "language" to "js",
                "script" to "claw.wake();claw.sleep(500);claw.openApp(\"com.android.settings\");",
                "timeout_ms" to 60000,
            ),
            requiresAccessibility = true,
        ),
        CapabilitySpec(
            command = "INSTALL_APK",
            title = "安装 APK",
            category = "系统",
            description = "下载并安装 APK。Shizuku 可用时静默安装(pm install)，否则走系统安装确认。",
            params = listOf(
                ParamSpec("url", "string", true, "APK 下载地址", example = "https://example.com/app.apk"),
                ParamSpec("file_name", "string", false, "可选保存文件名"),
            ),
            example = mapOf("url" to "https://example.com/app.apk", "file_name" to "app.apk"),
        ),
        CapabilitySpec(
            command = "EXPORT_LOGS",
            title = "导出日志",
            category = "系统",
            description = "上传最近 N 分钟节点日志到网关。",
            params = listOf(
                ParamSpec("minutes", "int", false, "最近 N 分钟", default = 5, example = 5),
            ),
            example = mapOf("minutes" to 5),
        ),
        CapabilitySpec(
            command = "GET_CAPABILITIES",
            title = "获取能力清单",
            category = "元信息",
            description = "返回本节点支持的全部指令、参数与版本号(即本清单)。",
            params = emptyList(),
            example = emptyMap(),
        ),
    )
}
