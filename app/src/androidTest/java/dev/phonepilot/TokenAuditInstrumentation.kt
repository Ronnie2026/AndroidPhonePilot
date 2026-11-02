package dev.phonepilot

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Runs only on a disposable emulator: clears all audit records to verify deletion. */
class TokenAuditInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val c = targetContext
        val original = TokenStore.selected(c)?.id
        val created = mutableSetOf<String>()
        var activeSocket: java.net.Socket? = null
        var resultCode = ActivityResult.FAIL
        val result = Bundle()
        try {
            startActivitySync(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = System.currentTimeMillis() + 10000
            while(!RuntimeState.httpRunning || !RuntimeState.webSocketRunning) { check(System.currentTimeMillis() < deadline); Thread.sleep(100) }
            AuditStore.setEnabled(c, true); check(AuditStore.error.isEmpty()) { AuditStore.error }
            val t = TokenStore.create(c, "集成测试-365天", 365).also { created.add(it.id) }
            check(t.expires-t.created == 365L*86400000L)
            check(!t.valid(t.expires)); check(t.valid(t.expires-1))
            val forever = TokenStore.create(c, "集成测试-长期", null).also { created.add(it.id) }
            check(forever.expires == 0L && forever.valid(Long.MAX_VALUE))
            TokenStore.edit(c, forever.id, "集成测试-编辑", 7)
            check(TokenStore.all(c).first { it.id==forever.id }.expires > System.currentTimeMillis()+6L*86400000L)
            fun call(token: ApiToken, path: String = "/api/command", body: String = "{\"id\":1,\"action\":\"get_status\"}"): Int {
                return java.net.Socket("127.0.0.1",8766).use { socket ->
                    socket.soTimeout=5000
                    val payload=body.toByteArray()
                    val headers="POST $path HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer ${token.secret}\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(headers.toByteArray()+payload)
                    val response=socket.getInputStream().bufferedReader().use { it.readText() }
                    response.lineSequence().first().split(" ")[1].toInt()
                }
            }
            check(call(t)==200)
            check(call(forever)==200)
            check(AuditStore.stats(c,t.id).getLong("count")==1L)
            val entry=AuditStore.list(c,t.id).first()
            check(entry.getString("request").contains("get_status") && entry.getString("response").contains("batteryPercent"))
            check(call(t, body="{\"action\":\"get_status\",\"secret\":\"${t.secret}\",\"password\":\"dont-store-this\"}")==200)
            val masked=AuditStore.list(c,t.id).first().getString("request")
            check(!masked.contains(t.secret) && !masked.contains("dont-store-this"))
            AuditStore.setEnabled(c,false)
            val count=AuditStore.stats(c).getLong("count"); check(call(t)==200); check(AuditStore.stats(c).getLong("count")==count)
            AuditStore.setEnabled(c,true)
            TokenStore.enable(c,t.id,false); check(call(t)==401)
            TokenStore.enable(c,t.id,true)
            // A connected WebSocket must re-check current validity for every message.
            val socket=java.net.Socket("127.0.0.1",8765); activeSocket=socket; socket.soTimeout=5000
            val request="GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer ${t.secret}\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())
            val input=socket.getInputStream(); val header=StringBuilder()
            while(!header.endsWith("\r\n\r\n")) { val n=input.read(); check(n>=0); header.append(n.toChar()) }; check(header.startsWith("HTTP/1.1 101"))
            val prefs=c.getSharedPreferences("pilot",0)
            val values=TokenStore.all(c).map { if(it.id==t.id) it.copy(expires=System.currentTimeMillis()-1).json() else it.json() }
            prefs.edit().putString("tokens",JSONArray(values).toString()).commit()
            check(call(t)==401)
            check(call(t,"/mcp","{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}")==401)
            val payload="{\"action\":\"get_status\"}".toByteArray(); val mask=byteArrayOf(1,2,3,4)
            socket.getOutputStream().write(byteArrayOf(0x81.toByte(),(0x80 or payload.size).toByte())+mask+payload.mapIndexed { i,b -> (b.toInt() xor mask[i%4].toInt()).toByte() }.toByteArray())
            val opcode=input.read() and 15; val size=input.read() and 127
            check(opcode==1 && size<126)
            val answer=ByteArray(size); java.io.DataInputStream(input).readFully(answer)
            check(String(answer).contains("expired")); socket.close(); activeSocket=null
            val logs=AuditStore.list(c,t.id); check(logs.any { it.getInt("status")==401 })
            AuditStore.delete(c,id=logs.first().getString("id")); check(AuditStore.list(c,t.id).none { it.getString("id")==logs.first().getString("id") })
            AuditStore.delete(c,tokenId=t.id); check(AuditStore.stats(c,t.id).getLong("count")==0L)
            check(AuditStore.error.isEmpty()) { AuditStore.error }
            val dir=java.io.File(c.filesDir,"audit-xlog"); check(dir.listFiles()?.any { it.extension=="xlog" && it.length()>0 } == true)
            AuditStore.delete(c); check(AuditStore.stats(c).getLong("count")==0L)
            check(AuditStore.error.isEmpty())
            resultCode = ActivityResult.OK
            result.putString("stream","PASS: 365-day/default + perpetual expiry; edit; HTTP/MCP expiry & disable; connected WS expiry; per-token stats/details; secret masking; global off; single/token/all deletion; native XLog archive.\n")
        } catch(failure: Throwable) {
            result.putString("stream","FAIL: ${failure.javaClass.simpleName}: ${failure.message}\n${failure.stackTrace.take(5).joinToString("\n")}\n")
        } finally {
            activeSocket?.close()
            val prefs=c.getSharedPreferences("pilot",0)
            val retained=TokenStore.all(c).filter { it.id !in created }.map { it.json() }
            AuditStore.setEnabled(c,true)
            prefs.edit().putString("tokens",JSONArray(retained).toString()).putString("selectedToken",original ?: "").putBoolean("auditEnabled",true).commit()
        }
        finish(resultCode, result)
    }
    private object ActivityResult { const val OK=-1; const val FAIL=0 }
}
