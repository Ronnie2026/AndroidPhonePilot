package dev.phonepilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Discovery and copyable integration instructions generated from the executable registry. */
class ApiContract(private val core:CommandDispatcher, private val host:String) {
    companion object {
        const val MCP_VERSION="2025-06-18"
        val MCP_VERSIONS=listOf(MCP_VERSION,"2025-03-26")
        fun host(c:Context):String {
            if(RuntimeState.host(c)=="127.0.0.1") return "127.0.0.1"
            val array=DeviceStatus.read(c).getJSONArray("addresses")
            val list=(0 until array.length()).map {array.getJSONObject(it)}.filter {it.getBoolean("private")}
            return (list.firstOrNull {it.getString("interface").startsWith("wlan")} ?: list.firstOrNull())?.getString("ip") ?: "127.0.0.1"
        }
    }
    fun endpoint(protocol:String)=if(protocol=="ws") "ws://$host:8765/ws" else "http://$host:8766/${if(protocol=="mcp") "mcp" else "api/command"}"
    fun request(protocol:String,spec:ActionSpec):JSONObject = if(protocol=="mcp") JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call")
        .put("params",JSONObject().put("name",spec.name).put("arguments",JSONObject(spec.exampleArgs.toString())))
        else JSONObject().put("id",1).put("action",spec.name).put("args",JSONObject(spec.exampleArgs.toString()))
    fun response(protocol:String,spec:ActionSpec):JSONObject = if(protocol=="mcp") JSONObject().put("jsonrpc","2.0").put("id",1).put("result",JSONObject()
        .put("content",JSONArray().put(JSONObject().put("type","text").put("text",spec.exampleResult.toString())))
        .put("structuredContent",JSONObject(spec.exampleResult.toString())).put("isError",false))
        else JSONObject().put("id",1).put("result",JSONObject(spec.exampleResult.toString()))
    private fun shellQuote(value:String) = "'" + value.replace("'", "'\"'\"'") + "'"
    fun command(protocol:String,spec:ActionSpec,token:String="<TOKEN>"):String {
        val request=request(protocol,spec).toString()
        val authorization=shellQuote("Authorization: Bearer $token")
        return if(protocol=="ws") "# 需要安装 websocat；先建立连接，再粘贴下一行 JSON。\nwebsocat -H=$authorization ${shellQuote(endpoint(protocol))}\n$request"
        else "curl -X POST ${shellQuote(endpoint(protocol))} -H $authorization -H 'Content-Type: application/json'${if(protocol=="mcp") " -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: $MCP_VERSION'" else ""} --data ${shellQuote(request)}"
    }
    fun initialize()=JSONObject().put("jsonrpc","2.0").put("id",1).put("method","initialize").put("params",JSONObject().put("protocolVersion",MCP_VERSION)
        .put("capabilities",JSONObject()).put("clientInfo",JSONObject().put("name","external-client").put("version","1.0")))
    fun discovery(protocol:String)=when(protocol) {
        "mcp"->JSONObject().put("jsonrpc","2.0").put("id",2).put("method","tools/list")
        else->request(protocol,core.describe("list_actions"))
    }
    fun json():JSONObject=JSONObject().put("schemaVersion","1.0").put("server",JSONObject().put("name","PhonePilot").put("version",BuildConfig.VERSION_NAME))
        .put("authentication",JSONObject().put("type","bearer").put("header","Authorization: Bearer <TOKEN>").put("notes","Token 必须存在、启用且未过期；每次动作均检查。文档不包含密钥。"))
        .put("discovery",JSONObject().put("list","GET http://$host:8766/api/actions").put("detail","GET http://$host:8766/api/actions/{name}").put("contract","GET http://$host:8766/api/contract"))
        .put("protocols",JSONArray(listOf("mcp","http","ws").map { protocol->JSONObject().put("name",protocol).put("endpoint",endpoint(protocol)).put("guide",guide(protocol)) }))
        .put("actions",JSONArray(core.definitions.map { spec->spec.json().put("transports",JSONObject().also { j->listOf("mcp","http","ws").forEach { protocol->j.put(protocol,JSONObject().put("request",request(protocol,spec)).put("responseExample",response(protocol,spec)).put("commandExample",command(protocol,spec))) } }) }))
    fun guide(protocol:String):String=buildString {
        append("# PhonePilot ${BuildConfig.VERSION_NAME} · ${protocol.uppercase()} 接入\n\n")
        append("接口：${endpoint(protocol)}\n动作目录版本：1.0\n认证：Authorization: Bearer <TOKEN>\n\n")
        append("## 网络与认证\n默认仅手机本机监听。电脑通过 adb forward tcp:8766 tcp:8766 与 adb forward tcp:8765 tcp:8765 连接；允许局域网时改用手机局域网 IP。移动网络出口 IP 不代表公网可直连。HTTP/WS 是明文，仅限可信网络；公网通过加密隧道。\n")
        append("Token 为各入口共用的访问凭证，必须有效且已启用。停用/过期会拒绝后续动作；删除会关闭该 Token 已建立的 WS 连接。MCP 对带 Origin 的请求校验同源地址，非受信源返回 403；不提供跨域浏览器调用。不要将 Token 放入 URL。文档中 <TOKEN> 需替换为你的实际 Token。\n\n")
        append("## 动态发现\n所有发现接口同样要求认证。\nGET http://$host:8766/api/actions：返回 schemaVersion 与 actions 列表。\nGET http://$host:8766/api/actions/{name}：返回一个动作的完整定义。\nGET http://$host:8766/api/contract：返回三协议接入说明、所有 Schema 和请求响应示例（无密钥）。\n")
        append("本协议发现请求：\n```json\n${discovery(protocol).toString(2)}\n```\n")
        if(protocol=="mcp") {
            append("\n## MCP 初始化顺序\nStreamable HTTP 的无状态 JSON 响应模式：POST 返回 application/json，GET /mcp 返回 405，不提供 SSE 事件流或服务端主动通知；不分配 session ID。支持 $MCP_VERSION 和 2025-03-26。\n1. POST initialize：\n```json\n${initialize().toString(2)}\n```\n")
            append("2. 读取 result.protocolVersion。后续请求带 MCP-Protocol-Version: <协商版本>，并使用 Authorization、Content-Type: application/json、Accept: application/json, text/event-stream 请求头。\n")
            append("3. POST {\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}，响应 HTTP 202 空体。\n4. tools/list 发现动作，tools/call 调用；例子需在初始化完成后使用。\n")
            append("$MCP_VERSION 的 tools/list 包含 inputSchema 和 outputSchema，成功结果同时返回 content 文本和 structuredContent。2025-03-26 使用兼容的 content 文本结构；未传版本头按 2025-03-26 处理。客户端配置语法可能不同，请以客户端支持为准。\n")
        } else {
            append("\n## 请求与响应约定\n${if(protocol=="http") "POST /api/command，Content-Type: application/json。" else "连接 /ws，握手携带 Authorization 头；成功后发送文本 JSON，一次请求对应一次响应。"}\n")
            append("请求对象：id（可选，推荐字符串或整数；原样回显）、action（必填动作名称）、args（参数对象，无参数用 {}）。兼容旧版将动作参数放在外层，但推荐统一放在 args。\n成功：{\"id\":1,\"result\":{...}}。失败：{\"id\":1,\"error\":\"错误说明\",\"errorCode\":\"INVALID_ARGUMENT\"}。不要只根据 HTTP 200 判断动作成功。\n")
        }
        append("\n## 错误与限制\n认证失败 HTTP 401；WS 未认证/过期/停用使用关闭码 1008，删除会主动断开。未知 URL HTTP 404，方法不支持 HTTP 405，错误 JSON/长度 HTTP 400；MCP 未知方法 JSON-RPC -32601。\n")
        append("动作错误码：INVALID_ARGUMENT（缺少、类型、范围或额外参数错误）、UNKNOWN_ACTION、EXECUTION_FAILED、OUTPUT_CONTRACT_VIOLATION。MCP tools/call 执行失败在 result.isError=true，content 文本包含错误说明与 errorCode。\n")
        append("HTTP 请求体 Content-Length 必须为 1–65536 字节；WS 文本消息最多 65536 UTF-8 字节。参数不做字符串到数字的隐式转换。Schema 定义 required、type、范围、长度、pattern 和额外字段限制，所有入口共用验证。\n")
        append("Root 相关动作最长等待 10 秒；返回 exitCode 非 0 为系统命令失败，即使 HTTP 为 200。外部建议至少 15 秒请求超时。设置音量等动作会改变手机状态，调用方应自行决定是否重试；本服务没有执行去重保证。\n")
        append("日志可全局关闭；开启时按 Token 记录请求与响应，密钥脱敏。日志中请求/响应各截断至 16K 字符，但传给调用方的响应不因此截断。\n\n所有下方响应示例都是结构示例，非实时设备数据。\n")
    }
    fun detail(protocol:String,spec:ActionSpec,token:String="<TOKEN>"):String=buildString {
        append("# ${spec.title} · ${spec.name}\n\n${spec.description}\n\n分类：${spec.category}；动作版本：1.0；Root：${spec.root}；修改设备状态：${spec.mutating}\n${spec.notes}\n\n")
        append("## 输入参数 Schema\n```json\n${spec.input.toString(2)}\n```\n## 成功结果 Schema\n```json\n${spec.output.toString(2)}\n```\n")
        append("## ${protocol.uppercase()} 请求示例\n```json\n${request(protocol,spec).toString(2)}\n```\n## 响应示例（非实时）\n```json\n${response(protocol,spec).toString(2)}\n```\n")
        append("## 可复制调用\n```sh\n${command(protocol,spec,token)}\n```\n")
        append("\n失败时遵循本协议统一错误约定；系统命令还需检查 exitCode，非 0 不可视为成功。\n")
    }
    fun full(protocol:String)=guide(protocol)+core.definitions.joinToString("\n\n---\n\n",prefix="\n\n") {detail(protocol,it)}
}
