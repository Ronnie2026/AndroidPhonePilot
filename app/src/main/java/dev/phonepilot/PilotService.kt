package dev.phonepilot

import android.app.*
import android.content.*
import android.os.IBinder
import android.os.SystemClock
import org.json.*
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import fi.iki.elonen.NanoHTTPD
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class PilotService : Service() {
    private var ws: WebSocketServer? = null
    private var http: NanoHTTPD? = null
    private val authenticated = ConcurrentHashMap<WebSocket, String>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("pilot", "手机控制服务", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "pilot").setContentTitle("PhonePilot 正在运行")
            .setContentText("点击查看服务状态与连接方式").setContentIntent(launch)
            .setSmallIcon(android.R.drawable.ic_menu_manage).build())
        TokenStore.all(this); AuditStore.init(this)
        RuntimeState.serviceRunning = true
        RuntimeState.startedAt = SystemClock.elapsedRealtime()
        RuntimeState.lastError = ""
        val core = CommandDispatcher(this)
        val host = RuntimeState.host(this)
        ws = object : WebSocketServer(InetSocketAddress(host, 8765)) {
            override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
                val known = TokenStore.identify(this@PilotService, handshake.getFieldValue("Authorization"))
                val valid = known?.valid() == true && handshake.resourceDescriptor == "/ws"
                audit(known, "WebSocket", "{\"action\":\"handshake\"}", if(valid) "{\"connected\":true}" else "{\"error\":\"Unauthorized\"}", if(valid) 101 else 401, System.currentTimeMillis(), 0, connection.remoteSocketAddress?.toString() ?: "")
                if (!valid) { connection.close(1008, "Unauthorized or expired"); return }
                authenticated[connection] = known!!.id; RuntimeState.clients = authenticated.size
            }
            override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
                authenticated.remove(connection); RuntimeState.clients = authenticated.size
            }
            override fun onError(connection: WebSocket?, error: Exception) {
                if (connection == null) { android.util.Log.e("PhonePilot", "WebSocket server error", error); RuntimeState.webSocketRunning = false; RuntimeState.lastError = "WebSocket 监听失败：${error.javaClass.simpleName}" }
            }
            override fun onStart() { RuntimeState.webSocketRunning = true }
            override fun onMessage(connection: WebSocket, message: String) {
                val tokenId = authenticated[connection] ?: return
                if (!connection.isOpen) return
                val start = System.currentTimeMillis(); val clock = SystemClock.elapsedRealtime()
                val known = TokenStore.all(this@PilotService).firstOrNull { it.id == tokenId }
                val peer = connection.remoteSocketAddress?.toString() ?: ""
                if (known?.valid() != true) {
                    val response = "{\"error\":\"Token expired or disabled\"}"
                    audit(known, "WebSocket", message.take(65536), response, 401, start, 0, peer)
                    connection.send(response); connection.close(1008, "Token expired or disabled"); return
                }
                if (message.length > 65536) {
                    audit(known, "WebSocket", "[request too large]", "{\"error\":\"Request too large\"}", 413, start, 0, peer)
                    connection.close(1009, "Request too large"); return
                }
                val response = actionResponse(core, message).toString()
                audit(known, "WebSocket", message, response, 200, start, SystemClock.elapsedRealtime()-clock, peer)
                connection.send(response)
            }
        }.apply { setReuseAddr(true); start() }
        http = object : NanoHTTPD(host, 8766) {
            override fun serve(session: IHTTPSession): Response {
                val start = System.currentTimeMillis(); val clock = SystemClock.elapsedRealtime()
                val token = TokenStore.identify(this@PilotService, session.headers["authorization"])
                var payload = JSONObject().put("method", session.method.toString()).put("path", session.uri).toString()
                fun reply(status: Response.Status, value: String): Response {
                    audit(token, if(session.uri == "/mcp") "MCP" else "HTTP", payload, value, status.requestStatus, start, SystemClock.elapsedRealtime()-clock, session.remoteIpAddress ?: "")
                    return newFixedLengthResponse(status, "application/json", value)
                }
                if (token?.valid() != true) return reply(Response.Status.UNAUTHORIZED, "{\"error\":\"Unauthorized or expired\"}")
                if (session.method != Method.POST || session.uri !in listOf("/mcp", "/api/command"))
                    return reply(Response.Status.NOT_FOUND, "{\"error\":\"POST /mcp or /api/command\"}")
                val length = session.headers["content-length"]?.toLongOrNull()
                if (length == null || length !in 1..65536) return reply(Response.Status.BAD_REQUEST, "{\"error\":\"Content-Length must be 1..65536\"}")
                return try {
                    val body = HashMap<String, String>(); session.parseBody(body); payload = body["postData"] ?: "{}"
                    if (session.uri == "/api/command") return reply(Response.Status.OK, actionResponse(core, payload).toString())
                    val request = JSONObject(payload)
                    if (!request.has("id")) return reply(Response.Status.ACCEPTED, "")
                    val result = when (request.getString("method")) {
                        "initialize" -> JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("serverInfo", JSONObject().put("name", "PhonePilot").put("version", "0.3.0"))
                        "ping" -> JSONObject()
                        "tools/list" -> JSONObject().put("tools", JSONArray(core.actions.map { name ->
                            val schema = JSONObject("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},\"value\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},\"command\":{\"type\":\"string\"}}}")
                            val required = when(name) { "open_app" -> "package"; "volume_set" -> "value"; "root_shell" -> "command"; else -> null }
                            if (required != null) schema.put("required", JSONArray().put(required))
                            JSONObject().put("name", name).put("description", "Phone control: $name").put("inputSchema", schema)
                        }))
                        "tools/call" -> {
                            val params = request.getJSONObject("params")
                            try { content(core.execute(params.getString("name"), params.optJSONObject("arguments") ?: JSONObject()).toString()) }
                            catch (error: Exception) { content(error.message ?: "Action failed").put("isError", true) }
                        }
                        else -> return reply(Response.Status.OK, JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                            .put("error", JSONObject().put("code", -32601).put("message", "Method not found")).toString())
                    }
                    reply(Response.Status.OK, JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString())
                } catch (_: Exception) {
                    reply(Response.Status.BAD_REQUEST, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Invalid request\"}}")
                }
            }
        }.also {
            try { it.start(); RuntimeState.httpRunning = true }
            catch (_: Exception) { RuntimeState.httpRunning = false; RuntimeState.lastError = "HTTP 监听失败" }
        }
    }
    private fun audit(token: ApiToken?, protocol: String, request: String, response: String, status: Int, started: Long, duration: Long, peer: String) {
        val success = status in 100..299 && try {
            if(response.isEmpty()) true else JSONObject(response).let { j ->
                val result = j.optJSONObject("result")
                val toolResult = result?.optJSONArray("content")?.optJSONObject(0)?.optString("text")?.let { try { JSONObject(it) } catch (_: Exception) { null } }
                !j.has("error") && result?.optBoolean("isError", false) != true && (result?.optInt("exitCode", 0) ?: 0) == 0 && (toolResult?.optInt("exitCode", 0) ?: 0) == 0
            }
        } catch (_: Exception) { false }
        try { AuditStore.record(this, token, protocol, request, response, status, success, started, duration, peer) }
        catch (_: Exception) { android.util.Log.e("PhonePilot", "Audit storage unavailable") }
    }
    private fun content(value: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value)))
    private fun actionResponse(core: CommandDispatcher, payload: String): JSONObject {
        var id: Any = JSONObject.NULL
        return try {
            val request = JSONObject(payload); id = request.opt("id") ?: JSONObject.NULL
            JSONObject().put("id", id).put("result", core.execute(request.getString("action"), request.optJSONObject("args") ?: request))
        } catch (error: Exception) { JSONObject().put("id", id).put("error", error.message ?: "Action failed") }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() {
        RuntimeState.serviceRunning = false; RuntimeState.webSocketRunning = false; RuntimeState.httpRunning = false
        RuntimeState.clients = 0; authenticated.clear()
        try { ws?.stop(1000) } catch (_: Exception) { }
        http?.stop()
        super.onDestroy()
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) context.startForegroundService(Intent(context, PilotService::class.java))
    }
}
