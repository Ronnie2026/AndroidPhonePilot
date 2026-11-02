# AndroidPhonePilot 开发交接

更新时间：2026-11-02。本文可作为新聊天的项目上下文；详细事实以当前代码、Git、实际设备和验证记录为准。

## 项目与当前基线

- 正式目录：`/Users/ronnie_mac/Documents/ChatGPT/AndroidPhonePilot`，已加入 Codex 左侧同名项目，当前聊天也已归入该项目。
- GitHub：`https://github.com/Ronnie2026/AndroidPhonePilot.git`，分支 `main`。
- 应用版本 0.5.0（versionCode 6）。本次交接前最新代码/验证提交为 `9ab140a`；恢复时执行 `git log -5 --oneline`、`git status --short` 查看最新状态，不把本文基线当成永远的 HEAD。
- 从旧聊天 work 子目录迁入，旧路径保留兼容链接；日常开发使用正式目录。
- Kotlin 原生单 app 模块；最低 API 26、compileSdk 34、targetSdk 32、Build Tools 36.0.0。target 32 是当前兼容策略，需要后续适配，不能视作已符合商店最新要求。

## 用户确认的需求和已实现功能

目标是在手机上提供统一控制服务。架构方向参考 DroidBridge，当前代码独立实现；不要简单复制上游。保留架构说明、开源致谢和依赖许可。

已实现：
- App/Screen/Audio/Power/System/StatusController、共享 CommandDispatcher，支持常用 App 别名、包名启动、亮灭屏、媒体音量、状态和 Root shell。
- 13 项注册动作：原有 11 个控制/状态动作，加 list_actions、describe_action。功能、权限、参数、输入输出 Schema、示例与执行函数一起注册。
- MCP 为 Streamable HTTP 的无状态 JSON 响应模式：POST /mcp；GET /mcp 为 405；没有 SSE 推送或 session ID。支持 2025-06-18 和 2025-03-26，带 Origin 时校验同源。
- HTTP POST /api/command；WS /ws。默认本机监听，用户可手动启用可信局域网访问；公网加密隧道、BLE、ESP32、小智适配器未实现。
- HTTP 动态发现 GET /api/actions、GET /api/actions/{name}、GET /api/contract，均要求有效 Token。
- Token 新建、默认 365 天、可改天数或长期有效、停用、复制、删除。删除前必须确认外部服务引用已处理，展示已知 WS 数和最近调用；删除主动断开关联 WS，历史日志保留。应用无法自动检测所有外部引用。
- 腾讯 Mars XLog 本地归档，SQLite 明细查询索引；按 Token 统计、请求响应、单条/按 Token/全部删除，删除会同步重建归档和缓存。日志记录为明确开关；默认自动清理 30 天前数据，天数可改；已取消原约 2000 条上限。单条请求/响应日志各最多 16K 字符，不限制发送给调用方的原响应。
- 首页只展示实时服务与设备统计；齿轮进入分组设置。亮/暗/跟随系统，iOS 风格紧凑列表；Token 开关在行右侧。协议指南逐项列出并复制 Schema、请求/响应示例、命令和完整说明。

## 当前真机事实（重要）

用户最终明确：**NX888J 没有 Root**。更早“使用 Magisk”的回答已被用户纠正，不能沿用。

已在 NX888J / Android 16 / API 36 / 4KB 页设备验证：
- 安装和首页正常；先前黑屏截图来自锁屏，解锁后已成功截图，不能再当作页面黑屏缺陷。
- 官方 MCP Python SDK 2.3.0：初始化协商 2025-06-18，发现 13 工具，状态读取通过；媒体音量实际 4 → 5 → 4，已恢复。
- HTTP/MCP/WS 三入口、动态目录、独立 JSON Schema、Token/参数/Origin 拦截检查通过。
- XLog 实际生成并增长。
- Root id 明确失败：su 不存在。当前实现的应用启动、亮灭屏、Root shell 均依赖 Root，不能宣称在此手机可用。
- 未验证：重启自启、厂商长期保活、真实蜂窝出口、16KB 页 XLog、全部 MCP 客户端。

详细记录：`docs/device-validation/2026-11-02-NX888J.md`。设备可能已断开或切换，继续调试必须重新枚举。

## 关键文件

| 文件 | 职责 |
| --- | --- |
| app/src/main/java/dev/phonepilot/Core.kt | 控制器、动作注册与统一执行 |
| ActionSpec.kt（同目录） | 元数据与 Schema 子集校验 |
| ApiContract.kt / ApiGuideActivity.kt | 动态协议说明、请求响应和复制 |
| PilotService.kt | 前台服务、HTTP/MCP/WS、认证、审计、开机接收器 |
| MainActivity.kt / IosActivity.kt | 首页、设置、共享原生分组样式 |
| TokenStore.kt / TokenActivity.kt | Token 生命周期、管理/统计界面 |
| AuditStore.kt | XLog、SQLite、保留清理和删除 |
| DeviceStatus.kt / RuntimeState.kt | 设备采样与进程运行状态 |
| docs/ACTION_STANDARD.md | 新增对外动作的强制实现约定 |
| ACKNOWLEDGEMENTS.md / third_party/ | 致谢、实际依赖来源和许可 |

## 构建和安全复测

macOS 本机已安装 JDK 17 和 Android SDK：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew assembleDebug lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

脚本依赖：`scripts/requirements-test.txt`。可在 work 下建立 venv，避免依赖旧聊天的临时环境。

- scripts/smoke_test.py：基础三入口测试。
- scripts/contract_test.py：独立 jsonschema 校验与真实协议一致性测试；PHONEPILOT_EXPORT_DIR 可导出不含密钥的 JSON/三协议文档。
- scripts/mcp_client_test.py：官方 SDK 调用，短暂改变并恢复媒体音量；Root id 只读。真机必须显式 PHONEPILOT_ALLOW_DEVICE=1。
- TokenAuditInstrumentation：会清空日志，**仅在可清理日志的模拟器运行**。

多设备通过 ANDROID_SERIAL 指定。为避免与模拟器冲突，真机曾使用电脑 18766 → 手机 8766、18765 → 手机 8765；脚本支持 PHONEPILOT_HTTP_PORT、PHONEPILOT_WS_PORT。不要把电脑转发端口当成手机服务实际端口。

## 下一步状态

本轮用户尚未提出下一项具体修改，也尚未授权新的“免 Root 适配”方案。不要将建议当成既定需求。

已知后续候选：
- 若继续使用这台无 Root 手机，需按新需求讨论哪些动作改为原生免 Root、哪些需要其他授权后端；不能把 ADB 调试权限冒充 App 自身能力。
- 真实蜂窝出口、后台保活/自启和更多客户端/设备兼容测试，按用户下一步要求开展。

当前聊天上下文过长并不代表源码或 Git 丢失。新聊天先读 AGENTS.md 和本文，再结合用户这次的新需求继续；不需要重新构建整个项目或重新实现已经完成的功能。
