package dev.phonepilot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.tencent.mars.xlog.Log
import com.tencent.mars.xlog.Xlog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** XLog is the local archive; SQLite is its bounded, readable query index. All mutation is serialized. */
object AuditStore {
    private lateinit var context: Context
    private lateinit var db: SQLiteDatabase
    private var initialized = false
    private var nativeLoaded = false
    private var opened = false
    @Volatile var error = ""
        private set
    private val logDir get() = File(context.filesDir, "audit-xlog")
    private val cacheDir get() = File(context.cacheDir, "audit-xlog")
    @Synchronized fun init(c: Context) {
        if (initialized) return
        context = c.applicationContext
        db = context.openOrCreateDatabase("audit.db", Context.MODE_PRIVATE, null)
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
        db.execSQL("CREATE TABLE IF NOT EXISTS audit (id TEXT PRIMARY KEY, token_id TEXT, token_name TEXT, time INTEGER, transport TEXT, action TEXT, peer TEXT, status INTEGER, success INTEGER, duration INTEGER, request TEXT, response TEXT)")
        initialized = true
        rebuild()
    }
    fun enabled(c: Context) = c.getSharedPreferences("pilot", 0).getBoolean("auditEnabled", true)
    @Synchronized fun setEnabled(c: Context, enabled: Boolean) {
        init(c); c.getSharedPreferences("pilot", 0).edit().putBoolean("auditEnabled", enabled).apply()
        if (enabled && !opened) rebuild()
        if (!enabled && opened) Log.appenderFlushSync(true)
    }
    private fun openNative() {
        if (!nativeLoaded) {
            System.loadLibrary("c++_shared"); System.loadLibrary("marsxlog")
            Log.setLogImp(Xlog()); nativeLoaded = true
        }
        logDir.mkdirs(); cacheDir.mkdirs()
        Log.appenderOpen(Xlog.LEVEL_INFO, Xlog.AppednerModeAsync, cacheDir.absolutePath, logDir.absolutePath, "phonepilot", 0)
        Log.setConsoleLogOpen(false)
        opened = true; error = ""
    }
    private fun archive(row: JSONObject) {
        val value = android.util.Base64.encodeToString(row.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        // XLog has a bounded native message buffer. Preserve large records using numbered fragments.
        val chunks = value.chunked(1800)
        chunks.forEachIndexed { i, part -> Log.i("PhonePilotAudit", "${row.getString("id")} ${i + 1}/${chunks.size} base64:$part") }
    }
    private fun rebuild() {
        try {
            if (opened) { Log.appenderFlushSync(true); Log.appenderClose(); opened = false }
            logDir.deleteRecursively(); cacheDir.deleteRecursively()
            openNative()
            db.rawQuery("SELECT * FROM audit ORDER BY time, rowid", null).use { cursor ->
                while (cursor.moveToNext()) archive(row(cursor))
            }
            Log.appenderFlushSync(true)
        } catch (failure: LinkageError) { opened = false; error = "XLog 加载失败：${failure.javaClass.simpleName}" }
        catch (failure: Exception) { opened = false; error = "XLog 写入失败：${failure.javaClass.simpleName}" }
    }
    private fun row(cursor: android.database.Cursor): JSONObject = JSONObject().also { result ->
        for (i in 0 until cursor.columnCount) result.put(cursor.getColumnName(i), when(cursor.getType(i)) {
            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
            else -> cursor.getString(i)
        })
    }
    private fun sanitize(raw: String): String {
        var value = raw
        TokenStore.all(context).forEach { value = value.replace(it.secret, "[TOKEN REDACTED]") }
        value = value.replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+"), "Bearer [REDACTED]")
        value = value.replace(Regex("(?i)(\"(?:authorization|token|secret|password|api_key|apikey|cookie)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\""), "$1\"[REDACTED]\"")
        return if(value.length > 16384) value.take(16384) + "\n[日志已截断，原响应不受影响]" else value
    }
    @Synchronized fun record(c: Context, token: ApiToken?, transport: String, request: String, response: String, status: Int, success: Boolean, started: Long, duration: Long, peer: String = "") {
        init(c)
        if (!enabled(c)) return
        val action = try { val j = JSONObject(request); j.optString("action").ifEmpty { if(j.optString("method") == "tools/call") j.optJSONObject("params")?.optString("name") ?: "tools/call" else j.optString("method", "request") } } catch (_: Exception) { "invalid_request" }
        val record = JSONObject().put("id", UUID.randomUUID().toString()).put("token_id", token?.id ?: "unrecognized")
            .put("token_name", token?.name ?: "未识别 Token").put("time", started).put("transport", transport)
            .put("action", action).put("peer", peer).put("status", status).put("success", if(success) 1 else 0)
            .put("duration", duration).put("request", sanitize(request)).put("response", sanitize(response))
        val values = ContentValues()
        record.keys().forEach { key -> val v = record.get(key); if(v is Number) values.put(key, v.toLong()) else values.put(key, v.toString()) }
        db.insertOrThrow("audit", null, values)
        try { if (opened) { archive(record); Log.appenderFlushSync(true) } } catch (_: Exception) { error = "XLog 写入失败" }
        val count = db.rawQuery("SELECT COUNT(*) FROM audit", null).use { it.moveToFirst(); it.getInt(0) }
        if(count > 2000) { db.execSQL("DELETE FROM audit WHERE id IN (SELECT id FROM audit ORDER BY time, rowid LIMIT 100)"); rebuild() }
    }
    @Synchronized fun stats(c: Context, tokenId: String? = null): JSONObject {
        init(c)
        val filter = if(tokenId == null) "" else " WHERE token_id=?"
        val args = tokenId?.let { arrayOf(it) }
        return db.rawQuery("SELECT COUNT(*), COALESCE(SUM(success),0), COALESCE(AVG(duration),0), COALESCE(MAX(time),0) FROM audit$filter", args).use {
            it.moveToFirst(); JSONObject().put("count", it.getLong(0)).put("success", it.getLong(1)).put("avgMs", it.getDouble(2).toLong()).put("lastTime", it.getLong(3))
        }
    }
    @Synchronized fun list(c: Context, tokenId: String?, offset: Int = 0): List<JSONObject> {
        init(c)
        val filter = if(tokenId == null) "" else " WHERE token_id=?"
        return db.rawQuery("SELECT * FROM audit$filter ORDER BY time DESC, rowid DESC LIMIT 30 OFFSET ${offset.coerceAtLeast(0)}", tokenId?.let { arrayOf(it) }).use {
            val result = mutableListOf<JSONObject>(); while(it.moveToNext()) result.add(row(it)); result
        }
    }
    @Synchronized fun delete(c: Context, id: String? = null, tokenId: String? = null) {
        init(c)
        when { id != null -> db.delete("audit", "id=?", arrayOf(id)); tokenId != null -> db.delete("audit", "token_id=?", arrayOf(tokenId)); else -> db.delete("audit", null, null) }
        // Rebuild the dedicated XLog archive so deleted payloads do not remain in .xlog / mmap caches.
        rebuild(); db.execSQL("VACUUM")
    }
}
