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
    private val authenticated = ConcurrentHashMap.newKeySet<WebSocket>()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("pilot", "手机控制服务", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "pilot").setContentTitle("PhonePilot 正在运行")
            .setContentText("点击查看服务状态与连接方式").setContentIntent(launch)
            .setSmallIcon(android.R.drawable.ic_menu_manage).build())
        RuntimeState.token(this)
        RuntimeState.serviceRunning = true
        RuntimeState.startedAt = SystemClock.elapsedRealtime()
        RuntimeState.lastError = ""
        val core = CommandDispatcher(this)
        val host = RuntimeState.host(this)
        ws = object : WebSocketServer(InetSocketAddress(host, 8765)) {
            override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
                if (!authorized(handshake.getFieldValue("Authorization")) || handshake.resourceDescriptor != "/ws") {
                    connection.close(1008, "Unauthorized"); return
                }
                authenticated.add(connection); RuntimeState.clients = authenticated.size
            }
            override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
                authenticated.remove(connection); RuntimeState.clients = authenticated.size
            }
            override fun onError(connection: WebSocket?, error: Exception) {
                if (connection == null) { android.util.Log.e("PhonePilot", "WebSocket server error", error); RuntimeState.webSocketRunning = false; RuntimeState.lastError = "WebSocket 监听失败：${error.javaClass.simpleName}" }
            }
            override fun onStart() { RuntimeState.webSocketRunning = true }
            override fun onMessage(connection: WebSocket, message: String) {
                if (!authenticated.contains(connection) || !connection.isOpen) return
                if (message.length > 65536) { connection.close(1009, "Request too large"); return }
                connection.send(actionResponse(core, message).toString())
            }
        }.apply { setReuseAddr(true); start() }
        http = object : NanoHTTPD(host, 8766) {
            override fun serve(session: IHTTPSession): Response {
                if (!authorized(session.headers["authorization"])) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Unauthorized"))
                if (session.method != Method.POST || session.uri !in listOf("/mcp", "/api/command"))
                    return json(Response.Status.NOT_FOUND, JSONObject().put("error", "POST /mcp or /api/command"))
                val length = session.headers["content-length"]?.toLongOrNull()
                if (length == null || length !in 1..65536) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "Content-Length must be 1..65536"))
                return try {
                    val body = HashMap<String, String>(); session.parseBody(body)
                    val payload = body["postData"] ?: "{}"
                    if (session.uri == "/api/command") return json(Response.Status.OK, actionResponse(core, payload))
                    val request = JSONObject(payload)
                    if (!request.has("id")) return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
                    val result = when (request.getString("method")) {
                        "initialize" -> JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject().put("tools", JSONObject()))
                            .put("serverInfo", JSONObject().put("name", "PhonePilot").put("version", "0.2.0"))
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
                        else -> return json(Response.Status.OK, JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                            .put("error", JSONObject().put("code", -32601).put("message", "Method not found")))
                    }
                    json(Response.Status.OK, JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result))
                } catch (error: Exception) {
                    json(Response.Status.BAD_REQUEST, JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                        .put("error", JSONObject().put("code", -32700).put("message", "Invalid request")))
                }
            }
            private fun json(status: Response.Status, value: JSONObject) = newFixedLengthResponse(status, "application/json", value.toString())
        }.also {
            try { it.start(); RuntimeState.httpRunning = true }
            catch (_: Exception) { RuntimeState.httpRunning = false; RuntimeState.lastError = "HTTP 监听失败" }
        }
    }
    private fun authorized(value: String?) = value == "Bearer ${RuntimeState.token(this)}"
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
