package dev.phonepilot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("pilot", 0) }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var column: LinearLayout
    private val fields = mutableMapOf<String, TextView>()
    private var settings = false
    private var dark = false
    private var fg = 0
    private var muted = 0
    private var card = 0
    private var accent = 0
    private var publicIp = "尚未查询"
    private var querying = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!settings) refresh()
            handler.postDelayed(this, 2000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        RuntimeState.token(this)
        startForegroundService(Intent(this, PilotService::class.java))
        settings = state?.getBoolean("settings") ?: false
        publicIp = state?.getString("publicIp") ?: "尚未查询"
        render()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("settings", settings); out.putString("publicIp", publicIp)
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume(); if(settings) render(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    @Deprecated("Platform back navigation")
    override fun onBackPressed() { if (settings) { settings = false; render() } else super.onBackPressed() }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun text(value: String, size: Float = 15f, color: Int = fg) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(3), 0, dp(3))
    }
    private fun surface(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(17), dp(20), dp(17))
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(20).toFloat() }
        column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label; isAllCaps = false; setTextColor(accent); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun field(parent: LinearLayout, key: String, label: String) {
        parent.addView(text(label, 12f, muted))
        val view = text("—", 20f); fields[key] = view; parent.addView(view)
    }
    private fun render() {
        dark = when (prefs.getString("theme", "system")) {
            "dark" -> true; "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        fg = Color.parseColor(if (dark) "#ECF3F0" else "#172C28")
        muted = Color.parseColor(if (dark) "#9DB1AA" else "#62776F")
        card = Color.parseColor(if (dark) "#192C27" else "#FFFFFF")
        accent = Color.parseColor(if (dark) "#73E2BD" else "#116B53")
        val bg = Color.parseColor(if (dark) "#0D1B17" else "#F1F5F2")
        window.statusBarColor = bg; window.navigationBarColor = bg
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        fields.clear()
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(24)) }
        scroll.addView(column); setContentView(scroll)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(if (settings) "连接与设置" else "PhonePilot", 28f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = if (settings) "返回" else "⚙"; textSize = 22f; setTextColor(accent); contentDescription = if (settings) "返回首页" else "打开连接与设置"; setOnClickListener { settings = !settings; render() } }, LinearLayout.LayoutParams(dp(72), dp(56)))
        column.addView(header)
        column.addView(text(if (settings) "统一令牌 · 多种入口 · 相同核心动作" else "手机控制中心  /  每 2 秒更新", 13f, muted))
        column.addView(Space(this), LinearLayout.LayoutParams(1, dp(20)))
        if (settings) buildSettings() else buildHome()
    }
    private fun buildHome() {
        val service = surface()
        service.addView(text("服务概览", 13f, accent))
        field(service, "service", "前台控制服务")
        field(service, "ports", "协议入口")
        field(service, "uptime", "服务运行时长 · WebSocket 连接")
        val load = surface()
        load.addView(text("设备负载", 13f, accent))
        field(load, "cpu", "应用 CPU · 单核占用")
        field(load, "appMemory", "应用内存 PSS")
        field(load, "memory", "系统内存使用")
        field(load, "battery", "电量 · 充电状态")
        field(load, "thermal", "电池温度 · 电流（设备报告值）")
        val network = surface()
        network.addView(text("网络与地址", 13f, accent))
        field(network, "network", "当前网络 · 信号")
        field(network, "ips", "本机 IPv4 · 接口")
        network.addView(text("私网地址用于局域网；移动数据接口地址可能属于运营商内网。", 12f, muted))
        field(network, "publicIp", "移动网络临时出口 IP")
        network.addView(text("按需经移动网络访问 api.ipify.org 查询；Wi-Fi 地址不会被当作移动出口。出口地址可能变化，也不代表可从公网直连。", 12f, muted))
        button(network, "查询移动网络出口 IP") { lookupPublicIp() }
        button(network, "授权识别移动网络与信号") { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 11) }
        column.addView(text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", 12f, muted))
        refresh()
    }
    private fun refresh() {
        val s = DeviceStatus.read(this)
        fields["service"]?.text = if (s.getBoolean("serviceRunning")) "● 运行中" else "○ 未运行"
        fields["service"]?.setTextColor(if (s.getBoolean("serviceRunning")) accent else muted)
        fields["ports"]?.text = "HTTP / MCP ${if(s.getBoolean("httpRunning")) "在线" else "离线"}  ·  WS ${if(s.getBoolean("webSocketRunning")) "在线" else "离线"}" + if(RuntimeState.lastError.isNotEmpty()) "\n${RuntimeState.lastError}" else ""
        val seconds = s.getLong("serviceUptimeSeconds")
        fields["uptime"]?.text = "${seconds / 3600}时 ${seconds / 60 % 60}分 ${seconds % 60}秒  ·  ${s.getInt("webSocketClients")} 个连接"
        fields["cpu"]?.text = if(s.isNull("appCpuPercent")) "采样中…" else "${s.getDouble("appCpuPercent")}%"
        fields["appMemory"]?.text = "${s.getInt("appMemoryMb")} MB"
        fields["memory"]?.text = "${s.getLong("systemMemoryUsedMb")} / ${s.getLong("systemMemoryTotalMb")} MB" + if(s.getBoolean("lowMemory")) " · 内存紧张" else ""
        fields["battery"]?.text = "${s.getInt("batteryPercent").let { if(it<0) "未知" else "$it%" }}  ·  ${if(s.getBoolean("charging")) "正在充电" else "使用电池"}"
        fields["thermal"]?.text = "${s.getDouble("temperatureC")} °C  ·  ${if(s.isNull("batteryCurrentMa")) "电流不可用" else "${s.getInt("batteryCurrentMa")} mA"}"
        fields["network"]?.text = "${s.getString("network")}\n${s.getString("signal")}"
        val ips = s.getJSONArray("addresses")
        fields["ips"]?.text = (0 until ips.length()).joinToString("\n") { i -> ips.getJSONObject(i).let { "${it.getString("ip")}  ·  ${it.getString("interface")}${if(it.getBoolean("private")) " · 私网" else ""}" } }.ifEmpty { "暂无 IPv4 地址" }
        fields["publicIp"]?.text = publicIp
    }
    private fun lookupPublicIp() {
        if (querying) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val mobile = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true }
        if (mobile == null) {
            publicIp = "移动数据未连接；请开启移动数据，必要时关闭 Wi-Fi 后重试"
            refresh(); return
        }
        querying = true; publicIp = "正在通过移动网络查询…"; refresh()
        worker.execute {
            val result = try {
                val connection = mobile.openConnection(java.net.URL("https://api.ipify.org?format=json")) as HttpURLConnection
                try {
                    connection.connectTimeout = 8000; connection.readTimeout = 8000
                    require(connection.responseCode == 200) { "查询服务暂不可用" }
                    val ip = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("ip")
                    "$ip\n临时出口 · ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} 查询"
                } finally { connection.disconnect() }
            } catch (_: Exception) { "查询失败；移动数据可能不可用，请稍后重试" }
            handler.post { if (!isDestroyed) { publicIp = result; querying = false; if (!settings) refresh() } }
        }
    }
    private fun copy(value: String) {
        if(value.isEmpty()) { Toast.makeText(this, "没有有效 Token，请先新建或启用", Toast.LENGTH_SHORT).show(); return }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhonePilot", value))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
    private fun snippet(title: String, description: String, preview: String, content: () -> String) {
        val box = surface(); box.addView(text(title, 18f).apply { setTypeface(null, Typeface.BOLD) })
        box.addView(text(description, 13f, muted))
        box.addView(text(preview, 13f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
        button(box, "一键复制 $title") { if(title != "ADB 端口转发" && TokenStore.selected(this) == null) Toast.makeText(this, "没有有效 Token，请先新建或启用", Toast.LENGTH_SHORT).show() else copy(content()) }
    }
    private fun buildSettings() {
        val theme = surface(); theme.addView(text("外观", 18f))
        val modes = arrayOf("跟随系统", "亮色", "暗黑")
        button(theme, "主题：${when(prefs.getString("theme", "system")){"light"->"亮色";"dark"->"暗黑";else->"跟随系统"}}") {
            AlertDialog.Builder(this).setTitle("选择主题").setItems(modes) { _, which -> prefs.edit().putString("theme", arrayOf("system", "light", "dark")[which]).apply(); render() }.show()
        }
        val access = surface(); access.addView(text("访问范围与 Token", 18f))
        access.addView(text("每个 Token 通用于 MCP、HTTP 和 WebSocket。可新建、设置有效期和查看调用日志；复制示例包含当前选中的密钥。", 13f, muted))
        button(access, "Token 管理 / 调用统计与日志") { startActivity(Intent(this, TokenActivity::class.java)) }
        val selected = TokenStore.selected(this)
        access.addView(text("当前示例：${selected?.name ?: "没有有效 Token，请新建或启用"}", 13f, muted))
        val tokenView = text("••••••••  点击下方显示", 14f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }; access.addView(tokenView)
        button(access, "显示 / 隐藏密钥") { tokenView.text = if(tokenView.text.startsWith("••")) RuntimeState.token(this) else "••••••••  点击下方显示" }
        button(access, "复制当前 Token") { copy(RuntimeState.token(this)) }
        val lan = Switch(this).apply {
            text = "允许局域网调用"; setTextColor(fg); isChecked = prefs.getBoolean("lan", false)
            setOnCheckedChangeListener { _, checked ->
                if (checked) AlertDialog.Builder(this@MainActivity).setTitle("允许局域网调用")
                    .setMessage("将监听全部本机接口。普通 HTTP / WS 不加密，请仅在可信局域网使用；公网访问请使用加密隧道。")
                    .setPositiveButton("开启") { _, _ -> changeLan(true) }
                    .setNegativeButton("取消") { _, _ -> isChecked = false }.setOnCancelListener { isChecked = false }.show()
                else changeLan(false)
            }
        }; access.addView(lan)
        access.addView(text("当前监听：${RuntimeState.host(this)}。本机模式通过 ADB 转发；局域网模式会在复制示例中填入手机私网 IP。临时公网 IP 仅供识别出口，未提供公网穿透。", 13f, muted))
        val addresses = DeviceStatus.read(this).getJSONArray("addresses")
        val privateIps = (0 until addresses.length()).map { addresses.getJSONObject(it) }.filter { it.getBoolean("private") }
        val localIp = (privateIps.firstOrNull { it.getString("interface").startsWith("wlan") } ?: privateIps.firstOrNull())?.getString("ip")
        val callHost = if (prefs.getBoolean("lan", false)) localIp ?: "127.0.0.1" else "127.0.0.1"
        access.addView(text("示例调用地址：$callHost（局域网模式自动使用当前私网 IP）", 13f, muted))
        val token = { RuntimeState.token(this) }
        val header = { "-H 'Authorization: Bearer ${token()}' -H 'Content-Type: application/json'" }
        val command = "{\"id\":1,\"action\":\"get_status\",\"args\":{}}"
        snippet("MCP 调用", "HTTP JSON 模式；以下为 get_status 工具调用示例。", "POST http://$callHost:8766/mcp") {
            "curl http://$callHost:8766/mcp ${header()} -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_status\",\"arguments\":{}}}'"
        }
        snippet("MCP 客户端配置", "客户端需支持 HTTP MCP 与自定义认证头；不同客户端配置格式可能不同。", "URL + Authorization 请求头") {
            JSONObject().put("mcpServers", JSONObject().put("phonepilot", JSONObject().put("url", "http://$callHost:8766/mcp").put("headers", JSONObject().put("Authorization", "Bearer ${token()}")))).toString(2)
        }
        snippet("HTTP 调用", "普通 JSON 动作接口，与 MCP、WebSocket 共用核心。", "POST http://$callHost:8766/api/command") { "curl http://$callHost:8766/api/command ${header()} -d '$command'" }
        snippet("WebSocket 调用", "握手时携带 Authorization；连接成功后发送 JSON。电脑需安装 websocat。", "ws://$callHost:8765/ws\n$command") {
            "# 先建立连接，再粘贴下一行 JSON\nwebsocat -H='Authorization: Bearer ${token()}' ws://$callHost:8765/ws\n$command"
        }
        snippet("ADB 端口转发", "在已授权 USB 调试的电脑执行；ADB 自身沿用系统授权机制。", "电脑端 8765 / 8766 → 手机相同端口") { "adb forward tcp:8765 tcp:8765\nadb forward tcp:8766 tcp:8766" }
        snippet("Root Shell 调用", "通过统一 HTTP 动作执行 su -c；需要手机 Root 授权。直接 adb shell 仍按系统原有权限执行，不受本应用 Token 管理。", "root_shell(command) · 示例 id") {
            "curl http://$callHost:8766/api/command ${header()} -d '{\"id\":2,\"action\":\"root_shell\",\"args\":{\"command\":\"id\"}}'"
        }
        val actions = surface(); actions.addView(text("可用动作", 18f)); actions.addView(text(CommandDispatcher(this).actions.joinToString("  ·  "), 13f, muted))
    }
    private fun changeLan(enabled: Boolean) {
        if (prefs.getBoolean("lan", false) == enabled) return
        prefs.edit().putBoolean("lan", enabled).apply()
        stopService(Intent(this, PilotService::class.java))
        startForegroundService(Intent(this, PilotService::class.java))
        Toast.makeText(this, "正在应用访问范围", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ if (!isDestroyed) render() }, 500)
    }
}
