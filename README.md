# ClawNode — 具身智能节点（Headless Agent Node）

ClawNode 是一个运行在 Android 上的**无界面后台守护节点**。它通过 WebSocket 与远端 AI 网关（Gateway）保持长连接，只做两件事：

- **当眼睛**：按需捕获屏幕单帧截图，或开启实时视频流回传。
- **当手**：接收网关下发的绝对像素坐标，模拟真实的物理点击 / 滑动。

它不是给普通用户用的 App，UI 仅作权限引导与状态查看；真正的常驻逻辑挂在 `AccessibilityService` 上。

---

## 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Kotlin |
| 并发 | Kotlin Coroutines & Flow |
| 网络 | OkHttp WebSocket |
| 视觉 | `AccessibilityService.takeScreenshot()`(API 30+) + `MediaProjection` |
| 交互 | `AccessibilityService.dispatchGesture()` |
| JSON | Gson |
| minSdk | **30**（受 `takeScreenshot()` 约束） |

---

## 模块架构

```
com.clawnode.agent
├── ClawNodeApp                     Application 入口
├── core/
│   ├── NodeConfig                  全局配置（网关地址、退避参数、心跳）
│   └── ConnectionState             连接状态模型
├── model/
│   ├── Command                     网关下发指令信封
│   └── NodeResponse                节点回传信封 + 工厂方法
├── ws/
│   └── WsManager                   ★ WebSocket 长连接 + 指数退避重连
├── action/
│   ├── ActionExecutorService       ★ AccessibilityService，常驻枢纽 / 依赖装配
│   ├── CommandDispatcher           指令路由（手/眼/系统）
│   └── GestureController           dispatchGesture 封装：TAP / SWIPE
├── vision/
│   ├── VisionManager               ★ 两种视觉模式统一入口
│   ├── StreamForegroundService     模式B：MediaProjection + VirtualDisplay 推流
│   ├── ImageCodec                  Bitmap → JPEG → Base64
│   ├── MediaProjectionHolder       录屏授权结果中转
│   └── StreamBridge                推流服务 → WsManager 回传桥（解耦）
├── system/
│   ├── SystemController            权限检测 / 跳转 / 唤醒入口
│   ├── WakeUpActivity              透明 Activity：点亮+解锁屏幕
│   └── MediaProjectionRequestActivity  透明 Activity：拉起录屏授权
└── ui/
    └── MainActivity                控制台（权限引导 / 配置 / 状态）
```

★ 标记为核心模块。

### 为什么核心枢纽是 AccessibilityService？
它的实例由系统创建并保活，生命周期在本应用中最长，且本身就是手势注入与 `takeScreenshot` 的能力提供者。因此把 `WsManager`、`CommandDispatcher`、`VisionManager` 都装配在它的 `onServiceConnected()` 中，进程随服务存活。

### 为什么推流单独开前台服务？
Android 14 强制要求 `MediaProjection` 运行在 `foregroundServiceType="mediaProjection"` 的前台服务中。`StreamForegroundService` 独立承载 `VirtualDisplay` 生命周期，`STOP_STREAM` 时彻底释放 `VirtualDisplay / ImageReader / MediaProjection`。

---

## 关键实现说明

- **指数退避重连**：`WsManager.connectLoop()` 中 `base * factor^attempt`，封顶 60s 并加入抖动避免惊群；连接成功后 `attempt` 归零。
- **回调→挂起桥接**：`dispatchGesture`、`takeScreenshot`、WebSocket 回调均用 `suspendCancellableCoroutine` / `CompletableDeferred` 包装为挂起函数，避免回调地狱。
- **单帧优先 takeScreenshot**：模式 A 完全不需要录屏授权，轻量；只有模式 B 才拉起 `MediaProjection`。
- **推流限流**：`ImageReader.acquireLatestImage()` 自动丢弃积压帧，再按 `fps` 做时间节流；画面按比例缩放到 ≤720 宽以省带宽。

---

## 协议

### 下发（Gateway → Node）
```json
{ "trace_id": "req-1", "action_type": "TAP",  "payload": { "x": 450, "y": 800, "duration_ms": 100 } }
{ "trace_id": "req-2", "action_type": "SWIPE","payload": { "x": 100, "y": 900, "x2": 100, "y2": 300, "duration_ms": 300 } }
{ "trace_id": "req-3", "action_type": "WAKE_UP", "payload": {} }
{ "trace_id": "req-4", "action_type": "GET_SCREENSHOT", "payload": { "quality": 80 } }
{ "trace_id": "req-5", "action_type": "START_STREAM",   "payload": { "fps": 15 } }
{ "trace_id": "req-6", "action_type": "STOP_STREAM",    "payload": {} }
{ "trace_id": "req-7", "action_type": "INSTALL_APK",   "payload": { "url": "https://example.com/app.apk", "file_name": "app.apk" } }
{ "trace_id": "req-8", "action_type": "SET_CLIPBOARD", "payload": { "text": "hello from server" } }
```

> ⚠️ **协议补充**：原始 SWIPE payload 仅给出 `x/y`，但滑动逻辑上必须有终点，已追加可选字段 `x2/y2` 作为滑动终点。

### 回传（Node → Gateway）
```json
{ "trace_id": "req-1", "type": "ACTION_RESULT",     "data": { "status": "success", "message": "completed" } }
{ "trace_id": "req-4", "type": "SCREENSHOT_RESULT", "data": { "format": "jpeg", "base64_image": "/9j/..." } }
{ "trace_id": "req-5", "type": "STREAM_STATUS",     "data": { "status": "success", "message": "stream started 720x1560 @ 15fps" } }
{ "trace_id": "req-5", "type": "STREAM_FRAME",      "data": { "status": "success", "base64_image": "/9j/...", "width": 720, "height": 1560 } }
```

---

## 运行步骤

1. Android Studio 打开工程，或 `./gradlew :app:assembleDebug`（需先放入 gradle-wrapper.jar）。
2. 安装后打开 App → 「保存 Gateway 地址」。
3. 点「开启无障碍服务」，在系统设置里启用 **ClawNode Agent**。一旦启用，节点即自动连接网关并开始接收指令。
4. 推流首次会弹出系统录屏授权框（由 `MediaProjectionRequestActivity` 拉起）。

---

## 已知边界 / 生产前 TODO

- `NodeConfig` 用内存单例，重启丢失；生产应换 `DataStore` 持久化。
- `MainActivity` 仅简单展示「服务在线」；要实时显示 `WsManager.state`，需用 bound service 或全局事件总线把状态流暴露出来。
- 推流目前逐帧 JPEG（MJPEG 思路），实现简单但带宽较高；高帧率/低延迟场景应换 `MediaCodec` H.264 硬编码 + 分片传输。
- 未加鉴权握手；生产需在连接时带 token 并校验。
