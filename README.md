# AndroidPhonePilot

原生 Kotlin Root Android 手机控制服务。架构方向参考 [DroidBridge](https://github.com/zephyr7030/DroidBridge)，当前代码独立实现，以多个协议入口共享同一套核心动作。

## 架构与开源致谢

- [架构说明](docs/ARCHITECTURE.md)：模块职责、调用关系、常驻设计与未来接入边界。
- [致谢与开源来源](ACKNOWLEDGEMENTS.md)：DroidBridge 的设计参考关系、实际依赖与上游许可链接。

感谢 DroidBridge、Java-WebSocket、NanoHTTPD 及 Kotlin、Android、Gradle、MCP 社区的作者与贡献者。

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

默认绑定 loopback；0.2.0 可手动开启可信局域网访问，详见下文。以后 ESP32/小智公网接入需加入经认证的加密网关或可信隧道；不要直接将端口映射到公网。令牌允许全部动作（含 Root），目前没有分权限/轮换界面。BLE、远程网关、自动重连和完整 MCP 客户端互操作测试属于后续阶段。

本阶段构建验证与真机验证分开记录；尚未连接真机。

## 本次验证
`assembleDebug lintDebug` 通过。Lint 的私有分发 target SDK 过期规则明确豁免；仍有文案国际化、备份配置等警告。没有连接 ADB 设备，未执行真机功能或 MCP 客户端互操作测试。调试 APK 使用本机 debug 签名。

## 0.2.0 状态首页与连接设置

- 首页每 2 秒采样：前台服务与端口状态、连接数和运行时长、应用 CPU（单核基准）与 PSS 内存、系统内存、电量/充电/电池温度/设备报告电流、当前网络及信号、本机 IPv4 与接口。
- 外观支持跟随系统、亮色、暗黑；在右上角齿轮内切换，保存后生效。
- 移动出口 IP 通过按钮按需查询，绑定已连接的蜂窝网络，经 HTTPS 访问 api.ipify.org。显示查询时间；不持续后台查询，未连接蜂窝网络时提示开启移动数据。该地址是临时出口，可能经运营商 NAT，不能据此假定可入站访问。模拟器数据不代表真实 4G/5G。
- 蜂窝网络识别和信号可能需要电话状态权限，可在首页主动授权；未授权也可使用其他功能。LTE 无法仅凭当前 API 区分所有 5G NSA 情况，页面如实标注。部分设备无法提供信号或电流，将显示不可用。
- 设置页提供 MCP、普通 HTTP、WebSocket、ADB 转发与 Root Shell 的调用说明和一键复制。统一使用 `Authorization: Bearer <token>`；密钥默认隐藏，复制的示例包含真实密钥。
- 普通动作入口：`POST http://127.0.0.1:8766/api/command`，请求体与 WebSocket 相同。HTTP、WebSocket、MCP 调用同一个 CommandDispatcher；`get_status` 同时返回首页采样字段。
- 默认仅本机监听。在设置中主动开启“允许局域网调用”后重启服务、监听 0.0.0.0；示例自动填入当前私网 IP。普通 HTTP/WS 未加密，仅限可信局域网；远程公网使用加密隧道。没有自动公网穿透功能。ADB 自身仍使用原有 USB 调试授权，直接 adb shell 不由应用 Token 拦截。

### 本版验证

`assembleDebug lintDebug` 通过。模拟器已验证亮/暗主题、首页刷新、设置页面；`scripts/smoke_test.py` 验证三个入口状态查询、MCP 工具列表、HTTP/MCP 缺失 Token 拒绝、WebSocket 缺失 Token 关闭及无效动作参数拒绝。运行测试需先将 adb 加入 PATH、安装打开 debug APK 并完成两个端口转发。

真实蜂窝出口、信号、Root 动作、厂商后台策略仍需真机验证；MCP 尚未完成各客户端完整互操作验证。

已在模拟器验证局域网开关开启/关闭后的服务恢复和三入口调用，WebSocket 启用端口复用以支持访问范围切换。新文件：`MainActivity.kt` 为首页与设置、`DeviceStatus.kt` 为统一状态采样、`RuntimeState.kt` 为服务状态和统一配置。
