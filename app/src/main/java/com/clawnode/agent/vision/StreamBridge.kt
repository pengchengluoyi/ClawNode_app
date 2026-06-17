package com.clawnode.agent.vision

import com.clawnode.agent.ws.WsManager

/**
 * 推流回传桥。
 *
 * 推流前台服务与 WsManager 处于不同组件（Service vs AccessibilityService），
 * 为避免强引用耦合，由 AccessibilityService 在装配完成后把 WsManager
 * 注册到此处，前台服务通过它回传帧数据。服务退出/连接销毁时置空。
 */
object StreamBridge {
    @Volatile
    var wsRef: WsManager? = null
}
