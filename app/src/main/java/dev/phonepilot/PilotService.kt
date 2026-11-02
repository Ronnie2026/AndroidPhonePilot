package dev.phonepilot
import android.app.*
import android.content.*
import android.os.IBinder
import org.json.*
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import fi.iki.elonen.NanoHTTPD
import java.net.InetSocketAddress
import java.util.UUID

class PilotService:Service() {
 private lateinit var ws:WebSocketServer; private lateinit var http:NanoHTTPD
 override fun onBind(i:Intent?):IBinder?=null
 override fun onCreate(){ super.onCreate()
 val nm=getSystemService(NotificationManager::class.java); nm.createNotificationChannel(NotificationChannel("pilot","Phone control",NotificationManager.IMPORTANCE_LOW))
 startForeground(1,Notification.Builder(this,"pilot").setContentTitle("PhonePilot running").setSmallIcon(android.R.drawable.ic_menu_manage).build())
 val prefs=getSharedPreferences("pilot",0); val token=prefs.getString("token",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("token",it).apply()}
 val core=CommandDispatcher(this)
 ws=object:WebSocketServer(InetSocketAddress("127.0.0.1",8765)) {
 override fun onOpen(c:WebSocket,h:ClientHandshake){if(h.getFieldValue("Authorization")!="Bearer $token" || h.resourceDescriptor!="/ws") c.close(1008,"Unauthorized")}
 override fun onClose(c:WebSocket,code:Int,reason:String,remote:Boolean){}
 override fun onError(c:WebSocket?,e:Exception){}
 override fun onStart(){}
 override fun onMessage(c:WebSocket,s:String){if(c.isClosing || c.isClosed)return;try{val q=JSONObject(s); c.send(JSONObject().put("id",q.opt("id")).put("result",core.execute(q.getString("action"),q.optJSONObject("args")?:q)).toString())}catch(e:Exception){c.send(JSONObject().put("error",e.message).toString())}}
 }; ws.start()
 http=object:NanoHTTPD("127.0.0.1",8766){override fun serve(s:IHTTPSession):Response {
 if(s.headers["authorization"]!="Bearer $token")return newFixedLengthResponse(Response.Status.UNAUTHORIZED,"text/plain","Unauthorized")
 if(s.uri!="/mcp" || s.method!=Method.POST)return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","POST /mcp")
 return try {val body=HashMap<String,String>();s.parseBody(body);val q=JSONObject(body["postData"]?:"{}"); if(!q.has("id")) return newFixedLengthResponse(Response.Status.ACCEPTED,"application/json","")
 val result=when(q.getString("method")) {
 "initialize"->JSONObject("{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"PhonePilot\",\"version\":\"0.1.0\"}}")
 "ping"->JSONObject()
 "tools/list"->JSONObject().put("tools",JSONArray(core.actions.map { JSONObject().put("name",it).put("description","Phone control: $it").put("inputSchema",JSONObject("{\"type\":\"object\",\"properties\":{\"package\":{\"type\":\"string\"},\"value\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},\"command\":{\"type\":\"string\"}}}")) }))
 "tools/call"->{val a=q.getJSONObject("params"); try {JSONObject().put("content",JSONArray().put(JSONObject().put("type","text").put("text",core.execute(a.getString("name"),a.optJSONObject("arguments")?:JSONObject()).toString())))}catch(e:Exception){JSONObject().put("isError",true).put("content",JSONArray().put(JSONObject().put("type","text").put("text",e.message)))}}
 else->return newFixedLengthResponse(Response.Status.OK,"application/json",JSONObject().put("jsonrpc","2.0").put("id",q.get("id")).put("error",JSONObject().put("code",-32601).put("message","Method not found")).toString())
 };newFixedLengthResponse(Response.Status.OK,"application/json",JSONObject().put("jsonrpc","2.0").put("id",q.get("id")).put("result",result).toString())
 }catch(e:Exception){newFixedLengthResponse(Response.Status.BAD_REQUEST,"application/json",JSONObject().put("error",e.message).toString())}
 }};http.start()
 }
 override fun onStartCommand(i:Intent?,flags:Int,id:Int)=START_STICKY
 override fun onDestroy(){ws.stop(1000);http.stop();super.onDestroy()}
}
class BootReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){if(i.action==Intent.ACTION_BOOT_COMPLETED)c.startForegroundService(Intent(c,PilotService::class.java))}}
class MainActivity:Activity(){override fun onCreate(b:android.os.Bundle?){super.onCreate(b);val prefs=getSharedPreferences("pilot",0);if(!prefs.contains("token"))prefs.edit().putString("token",UUID.randomUUID().toString()).commit();startForegroundService(Intent(this,PilotService::class.java));setContentView(android.widget.TextView(this).apply{setPadding(32,64,32,32);text="PhonePilot\nLocal control service\n\nBearer token:\n"+getSharedPreferences("pilot",0).getString("token","Restart app to view token");setTextIsSelectable(true)})}}
