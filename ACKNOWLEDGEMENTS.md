# 致谢与开源来源

感谢开源项目的作者、维护者与贡献者。AndroidPhonePilot 的设计和实现受益于以下项目与生态。

## 架构参考

- **[DroidBridge · zephyr7030 及贡献者](https://github.com/zephyr7030/DroidBridge)**：为 Android 设备端 MCP、Root 控制和后台服务的项目方向提供参考。本项目当前代码独立实现，未复制或移植 DroidBridge 源码，也未继承其完整功能；本致谢不表示上游对本项目的背书。后续若引入上游代码，将保留相应版权与许可声明并标明来源。

## 直接运行依赖

| 项目 | 使用版本 | 用途 | 上游许可文件 |
| --- | --- | --- | --- |
| [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) | 1.5.7 | WebSocket 服务端 | [LICENSE](https://github.com/TooTallNate/Java-WebSocket/blob/v1.5.7/LICENSE) |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 2.3.1 | HTTP MCP 入口 | [LICENSE.md](https://github.com/NanoHttpd/nanohttpd/blob/nanohttpd-project-2.3.1/LICENSE.md) |

版本以 app/build.gradle.kts 为准；第三方依赖保留各自许可，不因本项目采用 MIT 许可证而改变。

## 语言、平台与协议

感谢 [Kotlin](https://github.com/JetBrains/kotlin)、[Android Open Source Project](https://source.android.com/)、[Gradle](https://github.com/gradle/gradle) 和 [Model Context Protocol](https://github.com/modelcontextprotocol/modelcontextprotocol) 社区提供语言、平台、构建工具与协议基础。MCP 当前由本项目直接实现所需 JSON-RPC 方法，未使用官方 MCP SDK。

AndroidPhonePilot 自有代码的许可证见 [LICENSE](LICENSE)。本文件区分设计参考和实际依赖，后续新增或移植开源组件时应同步维护来源说明。

## 腾讯 Mars XLog

感谢 **[Tencent/Mars](https://github.com/Tencent/mars)** 作者和贡献者提供 XLog 本地日志组件。本项目从 0.3.0 起实际集成 `com.tencent.mars:mars-xlog:1.2.6`，用于保存调用请求与响应归档；不是仅参考其设计。保留 [上游许可与第三方声明](third_party/mars-xlog/LICENSE)，依赖来源、版本、校验值及 native 平台边界见 [组件说明](third_party/mars-xlog/README.md)。SQLite 查询索引使用 Android 系统 API 实现。

## 设置图标

感谢 [Google Material Design Icons](https://github.com/google/material-design-icons) 提供设置齿轮图标。本项目将 settings 24px 图标转换为 Android VectorDrawable，保留 [Apache-2.0 许可证](third_party/material-design-icons/LICENSE)；来源见 [组件说明](third_party/material-design-icons/README.md)。界面参考 iOS 的分组列表和信息层级，使用 Android 原生组件独立实现，未使用 Apple 字体、SF Symbols 或 Apple 图像资源。

## 协议集成验证

感谢 [官方 MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk) 与 [python-jsonschema](https://github.com/python-jsonschema/jsonschema) 社区。测试脚本使用 MCP SDK 2.3.0 进行实际 Streamable HTTP 客户端调用，并使用 jsonschema 独立验证对外 Schema 与示例；它们是开发测试依赖，不随 Android APK 打包。
