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

## 0.3.0 多 Token、有效期与调用日志

入口：连接设置 → **Token 管理 / 调用统计与日志**。

- 新建具名 Token，默认 365 天，可指定 1–36500 天，或选“长期有效（最大）”。编辑有效期从保存时间重新起算；密钥不变。已有的旧版单一 Token 自动迁移为默认 Token，自升级起有效 365 天，原调用方无需立即更换密钥。
- 每个有效 Token 通用于 HTTP、MCP 和 WebSocket。可选择用于复制调用示例的 Token，可随时停用/启用；没有有效 Token 时不会自动创建旁路密钥。
- 每次 HTTP/MCP 请求和每条 WebSocket 动作均检查有效期和启用状态；已连接的 WebSocket 在下次请求时重新检查，失效则拒绝并关闭连接。
- 全局调用日志默认开启，关闭后所有 Token 的新请求不写日志、不累计统计，已有记录保留。重新开启从后续调用开始记录，不补录关闭期间的调用。
- 记录 Token 标识/名称、时间、入口、动作、来源、状态、成功/失败、耗时、请求及响应；WebSocket 握手也作为记录。未知 Token 的认证失败归入“未识别 Token”，不记录 Authorization 密钥。
- 按 Token 查看保留调用总数、成功/失败、成功率、平均耗时、最近调用时间；明细每页 30 条，可查看/复制请求响应。统计基于保留日志，不是不可变的终身累计值。
- 支持单条、当前 Token 和全部日志删除；同步重建 XLog 归档及缓存，清理查询索引。删除会减少相应统计。并发发生的新调用仍会按开关正常记录；如需清空后保持零记录，先关闭全局记录。
- 0.3.0 初版曾按约 2000 条淘汰；从 0.3.1 起改为按天数保留，见下文。请求、响应各保留前 16K 字符，超过部分明确标注截断，不影响发送给调用方的原始响应。
- 已知 Token、Bearer 密钥及常用敏感字段脱敏；请求和 Shell 响应仍可能含业务资料。日志保存在应用私有目录，不自动上传。

### 日志存储与组件来源

实际使用腾讯 **Mars XLog 1.2.6** native 库，归档位置 `files/audit-xlog/*.xlog`；对应缓存位于 `cache/audit-xlog/`。`databases/audit.db` 是可查询索引，含脱敏后的请求响应，便于明细、统计与删除后重建。XLog 目录由本组件独占。它是本地日志组件，不是腾讯云日志服务。

依赖已固定归档并记录哈希和上游许可，见 [第三方组件说明](third_party/mars-xlog/README.md)。当前 AAR 为 ARM 库，16KB 页设备兼容性尚需真机确认；加载/写入失败在管理页显示，不能把索引成功视为 XLog 写入成功。

### 自动验证

现有 `scripts/smoke_test.py` 验证三入口基础调用。新增原生仪器测试覆盖 Token 365 天/长期有效/编辑/停用、HTTP/MCP 过期拦截、已有 WebSocket 过期后拒绝、日志明细/脱敏/全局开关/单条及按 Token 和全部删除、XLog native 归档。

**仪器测试仅在可清理日志的模拟器运行，它会删除全部调用日志。** 构建 `./gradlew assembleDebug assembleDebugAndroidTest lintDebug`；安装两份 debug/test APK 后执行 `adb shell am instrument -w dev.phonepilot.test/dev.phonepilot.TokenAuditInstrumentation`。真机 Root、真实蜂窝和厂商常驻策略验证边界仍沿用前述说明。

XLog 中每条审计记录按 UTF-8 JSON → Base64 → 编号分片写入，避免原生日志行长度限制及 Unicode 分片损失。Base64 仅是编码，不是加密；SQLite 明细直接显示脱敏后的 JSON。


## 0.3.1 日志存储与自动清理

“Token 管理 → 日志存储”提供明确的 **开启日志 / 关闭日志** 单选项；关闭只影响新请求记录，不删除已保存的日志。

默认设置：
- 日志保留 **30 天**，可修改为 1–36500 天，点击“保存保留天数”生效。
- 默认勾选 **自动清理 30 天以前的数据**，输入天数时文案同步变化。
- 勾选/取消自动清理会立即保存。开启时立即检查，以后服务运行期间每小时检查；进程停止期间不运行，下次启动时补做清理。
- 按请求发生时间清理严格早于截止时间的记录（一天按 24 小时计算）；删除同步作用于 SQLite 明细/统计和 XLog 归档/缓存。
- 自动清理开关独立于日志记录开关。关闭自动清理时，不再按天数删除，历史数据保留至手动删除。
- 取消原来的约 2000 条数量上限；保留单条请求/响应各前 16K 字符的限制。

新增模拟器测试验证 31 天前记录被默认 30 天策略删除、29 天前记录保留、自定义 7 天策略、关闭自动清理时保留旧记录，以及无效天数拒绝。原有 Token、认证和审计测试继续通过。

## 0.4.0 分组界面

采用参考 iOS 信息层级的 Android 原生界面，支持亮色、暗黑和跟随系统：

- 首页“概览”：服务在线状态、运行时长、CPU、内存、电池、调用统计、负载与网络数据；右上角齿轮进入设置。
- 设置按常规、访问控制、连接方式、日志、系统工具分组。MCP/HTTP/WebSocket 各自进入接口详情，一键复制地址、当前 Token 和调用示例。
- Token 列表为紧凑行：名称、到期时间、调用次数，以及右侧启用开关；右上角“＋”新建。点击列表行进入有效期、密钥复制、调用统计和日志详情。
- 日志存储为独立页面，包含开启/关闭分段选择、30 天保留策略和自动清理开关。调用统计支持按 Token 筛选、分页和查看/删除请求响应。
- `IosActivity.kt` 统一实现原生导航栏、分组行、圆角背景、主题和控件；原有 API、Token 和日志组件通过原有入口调用。

本版通过 `assembleDebug lintDebug` 和三协议入口 smoke 检查；在 ARM64 模拟器检查亮/暗主题、页面导航、Token 行开关的实际认证效果，以及日志记录分段选择。真机 Root、蜂窝网络和设备 XLog 兼容性的验证边界不变。

## 0.5.0 Token 删除与统一动作约定

- Token 详情增加删除入口：先警告检查 MCP/HTTP/WS 等服务引用，显示已知 WS 连接数和最近调用；必须勾选“已更换 Token 或不再引用”才可确认。应用不能自动发现全部外部引用。删除持久化后旧密钥失效，并主动关闭该 Token 的 WS 连接；已开始执行的动作无法撤回。历史日志保留，日志筛选中可选择“已删除”的 Token。最后一个 Token 删除后不会自动生成新凭证。
- 日志存储改为原生开关，明确显示已开启/已关闭状态；30 天保留与自动清理策略继续独立设置。
- 新增统一 ActionSpec 注册表：功能说明、分类、Root/修改状态标记、输入/输出 Schema、参数及结果示例、行为限制与执行函数一起注册。输入统一校验，输出按声明检查；三个入口、动态目录和页面共用定义。
- 原有 11 个动作之外新增 `list_actions`、`describe_action(name)`，三个入口均可调用。MCP tools/list 动态返回描述和 Schema，HTTP/WS 不再另维护清单。
- 带 Bearer Token 的动态发现：`GET /api/actions`、`GET /api/actions/{name}`、`GET /api/contract`。最后一个返回全部协议说明、Schema、请求/响应与命令示例，不包含实际密钥。
- 设置 → MCP / HTTP / WebSocket 进入动态接入指南。按分类查看功能，逐项查看/复制参数类型、必填、范围、权限、请求/响应、命令和完整说明。复制公共说明使用 `<TOKEN>`；主动复制可执行命令或客户端配置才加入当前所选密钥。所有响应示例明确为非实时。
- MCP 支持 2025-06-18（outputSchema、structuredContent）和 2025-03-26 兼容路径。initialize 协商版本；后续使用 MCP-Protocol-Version 头。仍采用 HTTP JSON 响应，不提供 SSE，不代表所有 MCP 客户端都已完成互操作验证。
- 动作错误保留可读 error，并增加 errorCode；MCP 工具失败为 isError=true。系统命令需继续检查 exitCode。示例命令经过 shell 引号转义，避免参数中的引号破坏可复制命令。

新增功能必须遵循 [动作实现标准](docs/ACTION_STANDARD.md)。动态接入文档由 ApiContract 和 ApiGuideActivity 读取实际执行注册表生成，不得手工维护另一份功能表。

### 本版检查

`assembleDebug assembleDebugAndroidTest lintDebug`；Token 仪器测试增加删除后拒绝、即刻断开已有 WS、历史日志保留和不自动重建凭证的覆盖。独立 JSON Schema 验证及真实三入口集成测试：安装 `jsonschema` 后，设置 `ANDROID_SERIAL=emulator-5554`（多设备时必须选择模拟器）、两个端口转发和 adb PATH，运行 `python scripts/contract_test.py`。可设置 `PHONEPILOT_EXPORT_DIR` 导出无密钥的 JSON 与三协议 Markdown 接入说明。真机 Root、蜂窝和跨 MCP 客户端验证仍分开记录。


### 实际 Streamable HTTP 客户端调用（模拟器）

当前 MCP 为 Streamable HTTP 的无状态 JSON 响应模式：POST /mcp 返回 JSON；GET /mcp 返回 405，未提供 SSE 推送。Origin 存在时校验同源，未知来源 403。官方 MCP Python SDK 2.3.0 的 streamable_http_client 已实测：initialize 协商 2025-06-18、列举 13 个工具、get_status、volume_up、volume_set、参数错误和 describe_action 均完成；媒体音量实际 7 → 8 → 7 并恢复。Root id 返回 Cannot run program su: Permission denied，模拟器内应用未获得 su 权限，不能据此确认 Root 真机动作。

复现：安装 scripts/requirements-test.txt，以 ANDROID_SERIAL 指定模拟器并转发端口后运行 scripts/mcp_client_test.py。脚本只允许模拟器，音量测试完成会恢复原档位；PHONEPILOT_MCP_REPORT 可保存无密钥的调用记录。此为官方 SDK 单项互操作证据，不表示所有客户端和 Root/ROM 行为都通过验证。


### 首轮真机结果

[NX888J / Android 16 验证记录](docs/device-validation/2026-11-02-NX888J.md)：三入口、Schema、Token 拦截、官方 SDK 状态与音量实际调用、XLog 写入通过；用户确认无 Root，因此 Root 动作不可用。

多设备调试时使用 ANDROID_SERIAL 选择目标，PHONEPILOT_HTTP_PORT / PHONEPILOT_WS_PORT 指定电脑转发端口，避免与模拟器冲突。真实设备运行 mcp_client_test.py 时还需 PHONEPILOT_ALLOW_DEVICE=1，明确允许短暂调整并恢复媒体音量。
