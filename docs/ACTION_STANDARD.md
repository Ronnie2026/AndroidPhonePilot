# 对外动作实现标准（v1）

新增对外功能必须在 CommandDispatcher 的注册表中同时注册 `ActionSpec` 和执行函数。不要分别维护 HTTP 路由、WS 动作名、MCP 工具列表或界面功能清单。

## 必备定义

每项定义必须包含：唯一的 snake_case 名称、显示名称、分类、用途、Root 要求、是否修改设备状态、输入 JSON Schema、成功结果 JSON Schema、可校验的参数/结果示例、行为与失败注意事项。当前动作约定版本为 1.0；不兼容的参数或结果变更必须显式变更约定，不能只改示例。

注册阶段检查示例是否匹配输入/输出 Schema；调用阶段在执行前统一校验参数，在执行后统一校验结果。当前验证器支持 object / array / string / integer / number / boolean / null、required、additionalProperties、minimum / maximum、minLength / maxLength、pattern 和数组 items。未来使用其他 JSON Schema 关键字前，必须扩展验证器并添加独立 Schema 验证测试。字符串 pattern 应带明确的首尾边界；不能依赖调用方的类型强制转换。

所有执行函数返回 JSONObject。错误通过 ActionFailure 的稳定错误码与说明返回；其他异常统一映射 EXECUTION_FAILED。输入错误不能触发设备动作；输出不匹配应返回 OUTPUT_CONTRACT_VIOLATION。Root 命令保留 exitCode/output 格式，调用方必须检查 exitCode，非 0 不可当作成功。

## 一个定义驱动全部入口

- HTTP `GET /api/actions`：目录；`GET /api/actions/{name}`：单项；`GET /api/contract`：全协议说明与示例。全部要求有效 Bearer Token。
- HTTP / WS 的 `list_actions` 和 `describe_action` 直接读取同一注册表；普通动作使用 `{id, action, args}`。
- MCP `tools/list` 和 `tools/call` 使用同一注册表。2025-06-18 带 outputSchema/structuredContent；2025-03-26 保留 content 文本兼容路径。版本由 initialize 协商、后续 MCP-Protocol-Version 头指定。
- ApiContract 生成每个协议的参数说明、初始化、发现、请求响应、错误、限制与命令；ApiGuideActivity 读取实际注册项展示分组目录和参数详情。完整说明可复制，示例明确为非实时数据。
- 生成的公共文档只包含 `<TOKEN>` 占位符；只有用户主动复制可执行命令或客户端配置时才加入当前选中 Token。命令参数必须经 shellQuote，不能直接拼接单引号包裹的任意 JSON。

MCP 参考：[2025-06-18 Tools](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)、[Lifecycle](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle)。本项目当前使用 Streamable HTTP 的无状态 JSON 响应模式（GET /mcp 为 405，无 SSE；请求带 Origin 时校验同源），不承诺所有 MCP 客户端均已互操作验证。

## 验证要求

`scripts/contract_test.py` 使用独立 jsonschema 验证器校验目录和示例，并通过真实 HTTP / MCP / WS 请求验证目录一致性、协议封装、错误参数拒绝和文档不含密钥。新增动作必须为其关键行为补充验证；Root、安装包存在性、ROM、蜂窝权限等设备相关效果必须与构建/模拟器检查分开记录，不能只凭 Schema 示例断言真机能力。
