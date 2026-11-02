package dev.phonepilot

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : IosActivity() {
    private val prefs by lazy { getSharedPreferences("pilot",0) }
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val fields=mutableMapOf<String,TextView>()
    private var screen="home"
    private var publicIp="未查询"
    private var querying=false
    private var memoryBar: ProgressBar?=null
    private val ticker=object:Runnable { override fun run() { if(screen=="home") refresh(); handler.postDelayed(this,2000) } }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state); TokenStore.all(this)
        startForegroundService(Intent(this,PilotService::class.java))
        screen=(state?.getString("screen") ?: "home").takeIf {it in listOf("home","settings","adb")} ?: "settings"; publicIp=state?.getString("ip") ?: "未查询"; render()
    }
    override fun onSaveInstanceState(out:Bundle) { out.putString("screen",screen); out.putString("ip",publicIp); super.onSaveInstanceState(out) }
    override fun onResume() { super.onResume(); if(screen!="home") render(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    @Deprecated("Platform navigation") override fun onBackPressed() { if(screen!="home") go(if(screen=="settings") "home" else "settings") else super.onBackPressed() }
    private fun go(value:String) { screen=value; render() }
    private fun render() {
        fields.clear(); memoryBar=null
        when(screen) {
            "home" -> { page("概览",gear=true,rightAction={go("settings")}); home() }
            "settings" -> { page("设置","概览",{go("home")}); settings() }
            else -> { page("ADB","设置",{go("settings")}); adbGuide() }
        }
    }
    private fun metric(parent:LinearLayout,key:String,title:String,subtitle:String,margin:Boolean) {
        val tile=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=rounded(surface); setPadding(dp(16),dp(14),dp(16),dp(14)) }
        tile.addView(label(title,12f,secondary))
        fields[key]=label("—",27f,ink,true).also { tile.addView(it,LinearLayout.LayoutParams(-1,0,1f)) }
        fields["${key}Note"]=label(subtitle,11f,secondary).also { tile.addView(it) }
        parent.addView(tile,LinearLayout.LayoutParams(0,dp(110),1f).apply { if(margin) rightMargin=dp(12) })
    }
    private fun home() {
        val health=group()
        val line=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)) }
        fields["serviceDot"]=label("●",18f,green).apply { setPadding(0,0,dp(12),0) }.also {line.addView(it)}
        val state=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fields["service"]=label("正在连接服务",17f,ink,true).also { state.addView(it) }
        fields["ports"]=label("",12f,secondary).also { state.addView(it,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(5) }) }
        line.addView(state,LinearLayout.LayoutParams(0,-2,1f))
        fields["uptime"]=label("00:00",14f,secondary).also { line.addView(it) }; health.addView(line)
        gap(8)
        val first=LinearLayout(this); content.addView(first); metric(first,"cpu","应用 CPU","单核占用",true); metric(first,"appMemory","应用内存","PSS 实时采样",false)
        gap(12)
        val second=LinearLayout(this); content.addView(second); metric(second,"battery","电池","",true); metric(second,"calls","调用次数","当前保留日志",false)
        gap(24)
        val device=group("设备负载")
        fields["memory"]=row(device,"系统内存")
        memoryBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progressTintList=android.content.res.ColorStateList.valueOf(blue); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(if(dark) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()) }
        device.addView(memoryBar,LinearLayout.LayoutParams(-1,dp(4)).apply { leftMargin=dp(16);rightMargin=dp(16);bottomMargin=dp(12) })
        fields["thermal"]=row(device,"电池温度 / 电流")
        fields["clients"]=row(device,"WebSocket 连接")
        gap(20)
        val network=group("网络")
        fields["network"]=row(network,"当前连接")
        fields["signal"]=row(network,"信号强度")
        fields["ips"]=row(network,"局域网 IP")
        fields["publicIp"]=row(network,"移动网络出口",action={lookupPublicIp()})
        footnote("点击移动网络出口查询临时 IP。出口地址不代表公网可直连。")
        gap(14)
        footnote("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n每 2 秒更新")
        refresh()
    }
    private fun refresh() {
        val s=DeviceStatus.read(this)
        fields["serviceDot"]?.setTextColor(if(s.getBoolean("serviceRunning")) green else secondary)
        fields["service"]?.text=if(s.getBoolean("serviceRunning")) "服务运行中" else "服务未运行"
        fields["ports"]?.text="HTTP / MCP ${if(s.getBoolean("httpRunning")) "在线" else "离线"} · WS ${if(s.getBoolean("webSocketRunning")) "在线" else "离线"}" + if(RuntimeState.lastError.isNotEmpty()) "\n${RuntimeState.lastError}" else ""
        val seconds=s.getLong("serviceUptimeSeconds")
        fields["uptime"]?.text=String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        fields["cpu"]?.text=if(s.isNull("appCpuPercent")) "—" else "${s.getDouble("appCpuPercent")}%"
        fields["appMemory"]?.text="${s.getInt("appMemoryMb")} MB"
        fields["battery"]?.text=if(s.getInt("batteryPercent")<0) "—" else "${s.getInt("batteryPercent")}%"
        fields["batteryNote"]?.text=if(s.getBoolean("charging")) "正在充电" else "使用电池"
        fields["memory"]?.text="${s.getLong("systemMemoryUsedMb")} / ${s.getLong("systemMemoryTotalMb")} MB"
        memoryBar?.progress=(s.getLong("systemMemoryUsedMb")*100/s.getLong("systemMemoryTotalMb").coerceAtLeast(1)).toInt()
        fields["thermal"]?.text="${s.getDouble("temperatureC")}°C · ${if(s.isNull("batteryCurrentMa")) "—" else "${s.getInt("batteryCurrentMa")} mA"}"
        fields["clients"]?.text="${s.getInt("webSocketClients")} 个"
        fields["network"]?.text=s.getString("network")
        fields["signal"]?.text=s.getString("signal")
        val ips=s.getJSONArray("addresses")
        fields["ips"]?.text=(0 until ips.length()).map { ips.getJSONObject(it) }.joinToString("\n") { "${it.getString("ip")} · ${it.getString("interface")}" }.ifEmpty { "未分配" }
        fields["publicIp"]?.text=publicIp
        // Query aggregation off the UI thread so archiving/cleanup does not block scrolling.
        worker.execute {
            val stats=AuditStore.stats(this)
            handler.post { if(!isDestroyed && screen=="home") { fields["calls"]?.text=stats.getLong("count").toString(); fields["callsNote"]?.text="成功 ${stats.getLong("success")} · 均耗 ${stats.getLong("avgMs")} ms" } }
        }
    }
    private fun manager(page:String) { startActivity(Intent(this,TokenActivity::class.java).putExtra("page",page)) }
    private fun settings() {
        val general=group("常规")
        row(general,"外观",when(prefs.getString("theme","system")){"dark"->"暗黑";"light"->"亮色";else->"跟随系统"},action={
            AlertDialog.Builder(this).setTitle("外观").setSingleChoiceItems(arrayOf("跟随系统","亮色","暗黑"),when(prefs.getString("theme","system")){"light"->1;"dark"->2;else->0}) { dialog,index -> prefs.edit().putString("theme",arrayOf("system","light","dark")[index]).apply();dialog.dismiss();render() }.setNegativeButton("取消",null).show()
        })
        gap()
        val access=group("访问控制")
        val tokens=TokenStore.all(this)
        row(access,"Token 管理","${tokens.count { it.valid() }} 个有效",action={manager("tokens")})
        row(access,"调用示例使用",TokenStore.selected(this)?.name ?: "未选择",action={chooseToken()})
        row(access,"允许局域网调用",control=toggle(prefs.getBoolean("lan",false),"允许局域网调用") { checked ->
            if(checked) AlertDialog.Builder(this).setTitle("允许局域网调用？").setMessage("HTTP / WS 不加密，请在可信局域网使用。公网调用请使用加密隧道。")
                .setPositiveButton("开启") { _,_->changeLan(true) }.setNegativeButton("取消") { _,_->render() }.setOnCancelListener { render() }.show()
            else changeLan(false)
        })
        footnote(if(prefs.getBoolean("lan",false)) "局域网可访问，所有入口均需有效 Token。" else "仅本机可访问，可通过 ADB 转发连接。")
        gap(14)
        val protocols=group("连接方式")
        row(protocols,"MCP","HTTP JSON",action={guide("mcp")})
        row(protocols,"HTTP","/api/command",action={guide("http")})
        row(protocols,"WebSocket","/ws",action={guide("ws")})
        gap()
        val logs=group("日志")
        row(logs,"调用统计与明细",action={manager("logs")})
        row(logs,"日志存储",if(AuditStore.enabled(this)) "已开启 · ${AuditStore.retentionDays(this)} 天" else "已关闭",action={manager("storage")})
        gap()
        val tools=group("系统工具")
        row(tools,"ADB","端口转发",action={go("adb")})
        row(tools,"Root Shell","调用说明",action={guide("http","root_shell")})
        row(tools,"网络识别权限",action={requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE),11)})
        footnote("PhonePilot ${BuildConfig.VERSION_NAME} · 原生 Android 控制服务")
    }
    private fun chooseToken() {
        val values=TokenStore.all(this).filter { it.valid() }
        if(values.isEmpty()) { manager("tokens");return }
        AlertDialog.Builder(this).setTitle("选择调用 Token").setItems(values.map { it.name }.toTypedArray()) { _,index ->TokenStore.select(this,values[index].id);render() }.setNegativeButton("取消",null).show()
    }
    private fun guide(protocol:String,action:String?=null) {startActivity(Intent(this,ApiGuideActivity::class.java).putExtra("protocol",protocol).putExtra("action",action))}
    private fun adbGuide() {
        val g=group("USB 调试")
        row(g,"复制端口转发命令",action={copy("adb forward tcp:8765 tcp:8765\nadb forward tcp:8766 tcp:8766")})
        footnote("在已授权 USB 调试的电脑执行。ADB 和直接 adb shell 沿用系统原有授权。")
    }
    private fun changeLan(enabled:Boolean) {
        prefs.edit().putBoolean("lan",enabled).apply();stopService(Intent(this,PilotService::class.java));startForegroundService(Intent(this,PilotService::class.java));render()
    }
    private fun lookupPublicIp() {
        if(querying) return
        val cm=getSystemService(ConnectivityManager::class.java)
        val mobile=cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true }
        if(mobile==null) { toast("请开启移动数据后重试");return }
        querying=true;publicIp="查询中…";fields["publicIp"]?.text=publicIp
        worker.execute {
            val result=try {
                val connection=mobile.openConnection(java.net.URL("https://api.ipify.org?format=json")) as HttpURLConnection
                try { connection.connectTimeout=8000;connection.readTimeout=8000;require(connection.responseCode==200)
                    val ip=JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("ip")
                    "$ip\n${SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date())} · 临时"
                } finally { connection.disconnect() }
            } catch (_:Exception) { "查询失败，点击重试" }
            handler.post { if(!isDestroyed) { publicIp=result;querying=false;fields["publicIp"]?.text=publicIp } }
        }
    }
}
