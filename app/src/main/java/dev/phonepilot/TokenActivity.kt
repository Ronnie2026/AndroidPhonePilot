package dev.phonepilot

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TokenActivity : Activity() {
    private lateinit var column: LinearLayout
    private var fg = 0; private var muted = 0; private var card = 0; private var accent = 0
    private var showingLogs = false
    private var filter: String? = null
    private var offset = 0
    private val worker = Executors.newSingleThreadExecutor()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun date(v: Long) = if(v == 0L) "长期有效（最大）" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(v))
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); AuditStore.init(this)
        showingLogs = state?.getBoolean("logs") ?: false; filter = state?.getString("filter"); offset = state?.getInt("offset") ?: 0
        render()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("logs", showingLogs); out.putString("filter", filter); out.putInt("offset", offset); super.onSaveInstanceState(out) }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    @Deprecated("Platform navigation") override fun onBackPressed() { if(showingLogs) { showingLogs = false; render() } else super.onBackPressed() }
    private fun text(value: String, size: Float = 14f, color: Int = fg) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4)) }
    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(18).toFloat() }
        column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit) { parent.addView(Button(this).apply { text = label; isAllCaps = false; setTextColor(accent); setOnClickListener { action() } }) }
    private fun copy(value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhonePilot Token", value))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
    private fun render() {
        val p = getSharedPreferences("pilot", 0)
        val dark = when(p.getString("theme", "system")) { "dark" -> true; "light" -> false; else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
        setTheme(if(dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        fg = Color.parseColor(if(dark) "#ECF3F0" else "#172C28"); muted = Color.parseColor(if(dark) "#9DB1AA" else "#62776F")
        card = Color.parseColor(if(dark) "#192C27" else "#FFFFFF"); accent = Color.parseColor(if(dark) "#73E2BD" else "#116B53")
        val bg = Color.parseColor(if(dark) "#0D1B17" else "#F1F5F2")
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.decorView.systemUiVisibility = if(dark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)) }; scroll.addView(column); setContentView(scroll)
        column.addView(text(if(showingLogs) "调用统计与日志" else "Token 管理", 26f).apply { setTypeface(null, Typeface.BOLD) })
        button(column, if(showingLogs) "返回 Token 管理" else "返回连接设置") { if(showingLogs) { showingLogs = false; render() } else finish() }
        if(showingLogs) logList() else tokenList()
    }
    private fun tokenList() {
        val global = box()
        global.addView(text("日志存储", 18f))
        global.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val on = RadioButton(this@TokenActivity).apply { id = android.view.View.generateViewId(); text = "开启日志"; setTextColor(fg) }
            val off = RadioButton(this@TokenActivity).apply { id = android.view.View.generateViewId(); text = "关闭日志"; setTextColor(fg) }
            addView(on); addView(off)
            check(if(AuditStore.enabled(this@TokenActivity)) on.id else off.id)
            setOnCheckedChangeListener { _, checked -> AuditStore.setEnabled(this@TokenActivity, checked == on.id); render() }
        })
        val retentionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        retentionRow.addView(text("日志保留 "))
        val days = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(AuditStore.retentionDays(this@TokenActivity).toString()); setTextColor(fg); setSingleLine(true); contentDescription = "日志保留天数" }
        retentionRow.addView(days, LinearLayout.LayoutParams(dp(90), -2)); retentionRow.addView(text(" 天")); global.addView(retentionRow)
        val automatic = CheckBox(this).apply { text = "自动清理 ${AuditStore.retentionDays(this@TokenActivity)} 天以前的数据"; setTextColor(fg); isChecked = AuditStore.autoCleanup(this@TokenActivity) }
        global.addView(automatic)
        days.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { automatic.text = "自动清理 ${s.toString().ifEmpty { "—" }} 天以前的数据" }
            override fun afterTextChanged(s: android.text.Editable?) { }
        })
        fun saveRetention() {
            val value = days.text.toString().toIntOrNull()
            if(value == null || value !in 1..36500) { Toast.makeText(this, "请输入 1 至 36500 天", Toast.LENGTH_SHORT).show(); return }
            val clean = automatic.isChecked
            worker.execute {
                try {
                    AuditStore.setRetention(this, value, clean)
                    runOnUiThread { if(!isDestroyed) { render(); Toast.makeText(this, if(AuditStore.error.isEmpty()) "日志设置已保存" else AuditStore.error, Toast.LENGTH_LONG).show() } }
                } catch (_: Exception) { runOnUiThread { if(!isDestroyed) Toast.makeText(this, "设置保存失败，请重试", Toast.LENGTH_SHORT).show() } }
            }
        }
        automatic.setOnCheckedChangeListener { _, _ -> saveRetention() }
        button(global, "保存保留天数") { saveRetention() }
        global.addView(text(if(AuditStore.autoCleanup(this)) "服务运行期间每小时检查，到期数据同步清理明细与 XLog；下次启动也会检查。" else "自动清理已关闭，历史数据保留至手动删除。", 12f, muted))
        global.addView(text(if(AuditStore.enabled(this)) "记录已开启 · 腾讯 Mars XLog 本地存储" else "记录已关闭 · 新请求不写日志，也不累计统计", 13f, muted))
        if(AuditStore.error.isNotEmpty()) global.addView(text(AuditStore.error, 14f, Color.parseColor("#D96855")))
        global.addView(text("统计基于保留的日志；清理后相应统计同步减少。按保留天数清理；单条请求/响应各保留前 16K 字符。密钥脱敏，不上传腾讯云。", 12f, muted))
        val summary = AuditStore.stats(this)
        global.addView(text("保留 ${summary.getLong("count")} 次 · 成功 ${summary.getLong("success")} 次 · 平均 ${summary.getLong("avgMs")} ms"))
        button(global, "查看全部调用日志") { openLogs(null) }
        button(global, "删除全部日志") { confirmDelete("删除全部请求和响应日志？统计同时清零。") { AuditStore.delete(this) } }
        button(column, "＋ 新建 Token") { editToken(null) }
        button(column, "刷新统计") { render() }
        val selected = TokenStore.selected(this)?.id
        TokenStore.all(this).forEach { token ->
            val b = box(); b.addView(text(token.name + if(token.id == selected) "  · 当前示例" else "", 18f).apply { setTypeface(null, Typeface.BOLD) })
            val state = when { !token.enabled -> "已停用"; !token.valid() -> "已过期"; else -> "有效" }
            b.addView(text("$state  ·  ${token.id.take(8)}\n创建：${date(token.created)}\n到期：${date(token.expires)}", 13f, muted))
            val s = AuditStore.stats(this, token.id); val count = s.getLong("count"); val success = s.getLong("success")
            b.addView(text("调用 $count 次 · 成功 $success · 失败 ${count-success}\n平均 ${s.getLong("avgMs")} ms · 成功率 ${if(count==0L) "—" else "${success*100/count}%"}"))
            if(s.getLong("lastTime") > 0) b.addView(text("最近：${date(s.getLong("lastTime"))}", 12f, muted))
            button(b, "复制 Token") { copy(token.secret) }
            if(token.valid()) button(b, "用于调用示例") { TokenStore.select(this, token.id); render() }
            button(b, "编辑名称 / 有效期") { editToken(token) }
            button(b, if(token.enabled) "停用 Token" else "启用 Token") { TokenStore.enable(this, token.id, !token.enabled); render() }
            button(b, "查看此 Token 调用明细") { openLogs(token.id) }
        }
    }
    private fun editToken(token: ApiToken?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply { hint = "Token 名称"; setText(token?.name ?: "外部调用") }; form.addView(name)
        form.addView(text("有效期从保存时间起算；默认 365 天。", 13f, muted))
        val days = EditText(this).apply { hint = "有效天数（1–36500）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(if(token != null && token.expires > System.currentTimeMillis()) ((token.expires-System.currentTimeMillis()+86399999L)/86400000L).toString() else "365") }; form.addView(days)
        val forever = CheckBox(this).apply { text = "长期有效（最大）"; isChecked = token?.expires == 0L; setOnCheckedChangeListener { _, checked -> days.isEnabled = !checked } }; form.addView(forever); days.isEnabled = !forever.isChecked
        val dialog = AlertDialog.Builder(this).setTitle(if(token==null) "新建 Token" else "编辑 Token").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val validity = if(forever.isChecked) null else days.text.toString().toLongOrNull() ?: error("请输入有效天数")
                if(token==null) TokenStore.create(this, name.text.toString(), validity) else TokenStore.edit(this, token.id, name.text.toString(), validity)
                dialog.dismiss(); render()
            } catch (e: Exception) { Toast.makeText(this, e.message ?: "请输入名称和有效天数", Toast.LENGTH_LONG).show() }
        } }; dialog.show()
    }
    private fun openLogs(id: String?) { filter = id; offset = 0; showingLogs = true; render() }
    private fun logList() {
        val currentFilter = filter
        val name = filter?.let { id -> TokenStore.all(this).firstOrNull { it.id == id }?.name } ?: "全部 Token（含认证失败）"
        val info = box(); info.addView(text(name, 18f))
        val s = AuditStore.stats(this, filter)
        info.addView(text("保留 ${s.getLong("count")} 次 · 成功 ${s.getLong("success")} 次\n平均 ${s.getLong("avgMs")} ms", 14f, muted))
        button(info, "刷新明细") { render() }
        button(info, "删除当前范围全部日志") { confirmDelete("删除当前范围日志及对应 XLog 记录？") { AuditStore.delete(this, tokenId = currentFilter) } }
        val logs = AuditStore.list(this, filter, offset)
        logs.forEach { log ->
            val b = box()
            b.addView(text("${log.getString("transport")} · ${log.getString("action")}", 17f))
            b.addView(text("${date(log.getLong("time"))}\n${log.getString("token_name")} · ${if(log.getInt("success")==1) "成功" else "失败"} · ${log.getInt("status")} · ${log.getLong("duration")} ms", 13f, muted))
            button(b, "查看请求 / 响应") { detail(log) }
            button(b, "删除此条") { confirmDelete("删除此条请求、响应及 XLog 记录？") { AuditStore.delete(this, id = log.getString("id")) } }
        }
        if(logs.isEmpty()) column.addView(text("暂无日志；关闭全局记录后不会新增。", 14f, muted))
        if(offset > 0) button(column, "上一页") { offset = (offset-30).coerceAtLeast(0); render() }
        if(offset + logs.size < s.getLong("count")) button(column, "下一页") { offset += 30; render() }
        column.addView(text("第 ${offset/30+1} 页 · 每页 30 条", 12f, muted))
    }
    private fun detail(log: JSONObject) {
        val body = "时间：${date(log.getLong("time"))}\nToken：${log.getString("token_name")} (${log.getString("token_id").take(8)})\n入口：${log.getString("transport")}\n来源：${log.getString("peer")}\n状态：${log.getInt("status")} · ${log.getLong("duration")} ms\n\n请求\n${log.getString("request")}\n\n响应\n${log.getString("response")}"
        val view = text(body, 13f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(18), dp(10), dp(18), dp(10)) }
        AlertDialog.Builder(this).setTitle("调用明细").setView(ScrollView(this).apply { addView(view) }).setPositiveButton("关闭", null).setNeutralButton("复制明细") { _, _ -> copy(body) }.show()
    }
    private fun confirmDelete(message: String, task: () -> Unit) {
        AlertDialog.Builder(this).setTitle("删除日志").setMessage(message).setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
            worker.execute {
                try { task(); runOnUiThread { if(!isDestroyed) { offset = 0; render(); Toast.makeText(this, if(AuditStore.error.isEmpty()) "日志已删除" else AuditStore.error, Toast.LENGTH_LONG).show() } } }
                catch (_: Exception) { runOnUiThread { if(!isDestroyed) Toast.makeText(this, "删除失败，请重试", Toast.LENGTH_LONG).show() } }
            }
        }.show()
    }
}
