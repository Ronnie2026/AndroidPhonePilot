# AndroidPhonePilot

原生 Kotlin Root Android 手机控制服务，独立实现，架构参考多入口共享动作层。

## 构建
JDK 17、Android SDK 34、Build Tools 36.0.0，执行 `./gradlew assembleDebug`。最低 Android 8；当前 target 32 是首版兼容策略，上架前需要升级并适配新的前台服务政策。

## 结构
- `Core.kt`: CommandDispatcher、App/Screen/Audio/Power/System/StatusController。
- `PilotService.kt`: 前台服务、WebSocket、HTTP MCP、开机接收器与设置页。
- BLE 按键、ESP32、小智尚未实现；新增适配器统一调用 `CommandDispatcher.execute`。

## 真机调试
1. 安装 `app/build/outputs/apk/debug/app-debug.apk`，首次手动打开应用。
2. 在 Root 管理器中授权 PhonePilot；Root shell 首次执行可能因授权等待超时，批准后重试。
3. 页面读取 Bearer token（请勿分享），执行 `adb forward tcp:8765 tcp:8765` 和 `adb forward tcp:8766 tcp:8766`。
4. WebSocket `ws://127.0.0.1:8765/ws`，握手 header `Authorization: Bearer <token>`。
5. 发送 `{"id":1,"action":"get_status"}`；音量 `{"id":2,"action":"volume_set","args":{"value":70}}`。
6. MCP：`http://127.0.0.1:8766/mcp`，相同认证头，JSON POST；支持 initialize、ping、tools/list、tools/call，HTTP JSON 响应模式，无 SSE。示例 `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_status","arguments":{}}}`。
7. 逐项验证应用启动、亮灭屏、音量、Root shell，然后重启验证服务通知与端口。豆包/Grok/ChatGPT 包名依据常用发行版配置，安装包不同可使用 open_app 指定 package。

## 动作
open_app(package)、open_grok、open_chatgpt、open_doubao、screen_on、screen_off、volume_up、volume_down、volume_set(value:0..100)、get_status、root_shell(command)。音量作用于媒体流。Root shell 返回 exitCode/output，非零退出码由调用方检查。screen_on 唤醒但不绕过锁屏。

## 常驻与边界
使用通知前台服务、START_STICKY 和 BOOT_COMPLETED。首次必须打开应用；系统强行停止、厂商省电政策会阻止自启，需在手机设置允许自启动并取消电池限制。并非 Magisk 独立守护进程，不能保证被杀或强停后恢复。当前状态包含型号、交互屏幕状态与媒体音量；Root 是否可用通过 root_shell 的 id 命令验证。

仅绑定 loopback，不直接开放局域网 Root 控制。以后 ESP32/小智接入需加入经认证的加密网关或可信隧道；不要直接将端口映射到公网。令牌允许全部动作（含 Root），目前没有分权限/轮换界面。BLE、远程网关、自动重连和完整 MCP 客户端互操作测试属于后续阶段。

本阶段构建验证与真机验证分开记录；尚未连接真机。

## 本次验证
`assembleDebug lintDebug` 通过。Lint 的私有分发 target SDK 过期规则明确豁免；仍有文案国际化、备份配置等警告。没有连接 ADB 设备，未执行真机功能或 MCP 客户端互操作测试。调试 APK 使用本机 debug 签名。
