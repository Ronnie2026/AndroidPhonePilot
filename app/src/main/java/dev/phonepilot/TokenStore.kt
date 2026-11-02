package dev.phonepilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class ApiToken(val id: String, val name: String, val secret: String, val created: Long, val expires: Long, val enabled: Boolean) {
    fun valid(now: Long = System.currentTimeMillis()) = enabled && (expires == 0L || now < expires)
    fun json() = JSONObject().put("id", id).put("name", name).put("secret", secret).put("created", created).put("expires", expires).put("enabled", enabled)
}

object TokenStore {
    private fun prefs(c: Context) = c.getSharedPreferences("pilot", 0)
    @Synchronized fun all(c: Context): List<ApiToken> {
        val p = prefs(c)
        if (!p.contains("tokens")) {
            val now = System.currentTimeMillis()
            val initial = ApiToken(UUID.randomUUID().toString(), "默认 Token", p.getString("token", null) ?: UUID.randomUUID().toString(), now, now + 365L * 86400000L, true)
            p.edit().putString("tokens", JSONArray().put(initial.json()).toString()).putString("selectedToken", initial.id).apply()
        }
        val array = JSONArray(p.getString("tokens", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it).let { j -> ApiToken(j.getString("id"), j.getString("name"), j.getString("secret"), j.getLong("created"), j.getLong("expires"), j.getBoolean("enabled")) } }
    }
    private fun save(c: Context, values: List<ApiToken>) { prefs(c).edit().putString("tokens", JSONArray(values.map { it.json() }).toString()).apply() }
    @Synchronized fun create(c: Context, name: String, days: Long?): ApiToken {
        require(name.trim().isNotEmpty() && name.length <= 60)
        val now = System.currentTimeMillis()
        val expiry = expiry(now, days)
        val value = ApiToken(UUID.randomUUID().toString(), name.trim(), UUID.randomUUID().toString() + UUID.randomUUID().toString().replace("-", ""), now, expiry, true)
        save(c, all(c) + value); select(c, value.id); return value
    }
    private fun expiry(now: Long, days: Long?): Long {
        if (days == null) return 0L
        require(days in 1..36500) { "有效天数为 1 至 36500，或选择长期有效" }
        return now + days * 86400000L
    }
    @Synchronized fun edit(c: Context, id: String, name: String, days: Long?) {
        require(name.trim().isNotEmpty() && name.length <= 60)
        val expires = expiry(System.currentTimeMillis(), days)
        save(c, all(c).map { if(it.id == id) it.copy(name = name.trim(), expires = expires) else it })
    }
    @Synchronized fun enable(c: Context, id: String, enabled: Boolean) { save(c, all(c).map { if(it.id == id) it.copy(enabled = enabled) else it }) }
    fun select(c: Context, id: String) { prefs(c).edit().putString("selectedToken", id).apply() }
    fun selected(c: Context): ApiToken? {
        val values = all(c)
        return values.firstOrNull { it.id == prefs(c).getString("selectedToken", "") && it.valid() } ?: values.firstOrNull { it.valid() }
    }
    fun identify(c: Context, authorization: String?): ApiToken? {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length > 512) return null
        val supplied = authorization.removePrefix("Bearer ").toByteArray(Charsets.UTF_8)
        return all(c).firstOrNull { MessageDigest.isEqual(it.secret.toByteArray(Charsets.UTF_8), supplied) }
    }
    fun authenticate(c: Context, authorization: String?) = identify(c, authorization)?.takeIf { it.valid() }
}
