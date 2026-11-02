package dev.phonepilot

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceStatus {
    private var lastWall = 0L
    private var lastCpu = 0L
    private var cpuPercent: Double? = null
    @Synchronized private fun cpu(): Any {
        val wall = SystemClock.elapsedRealtime()
        val used = android.os.Process.getElapsedCpuTime()
        if (lastWall == 0L) { lastWall = wall; lastCpu = used }
        else if (wall - lastWall >= 1000) {
            cpuPercent = kotlin.math.round((used-lastCpu) * 1000.0 / (wall-lastWall)) / 10.0
            lastWall = wall; lastCpu = used
        }
        return cpuPercent ?: JSONObject.NULL
    }
    fun read(context: Context): JSONObject {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val tm = context.getSystemService(TelephonyManager::class.java)
        val network = when {
            wifi -> "Wi-Fi"
            cellular -> try { when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE（可能含 5G NSA）"
                else -> "移动网络"
            } } catch (_: SecurityException) { "移动网络（4G / 5G 未授权识别）" }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "以太网"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            else -> "未连接"
        }
        val signal = try {
            if (wifi) {
                val rssi = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.rssi
                if (rssi in -126..-1) "$rssi dBm" else "暂不可用"
            } else if (cellular && android.os.Build.VERSION.SDK_INT >= 28) {
                tm.signalStrength?.let { "${it.level} / 4 格" } ?: "暂不可用"
            } else "—"
        } catch (_: SecurityException) { "需电话状态权限" }
        val addresses = JSONArray()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { iface ->
                iface.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.forEach {
                    addresses.put(JSONObject().put("interface", iface.name).put("ip", it.hostAddress).put("private", it.isSiteLocalAddress))
                }
            }
        } catch (_: Exception) { }
        val bm = context.getSystemService(BatteryManager::class.java)
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return JSONObject()
            .put("serviceRunning", RuntimeState.serviceRunning)
            .put("httpRunning", RuntimeState.httpRunning)
            .put("webSocketRunning", RuntimeState.webSocketRunning)
            .put("webSocketClients", RuntimeState.clients)
            .put("serviceUptimeSeconds", if (RuntimeState.serviceRunning) (SystemClock.elapsedRealtime() - RuntimeState.startedAt) / 1000 else 0)
            .put("appCpuPercent", cpu())
            .put("appMemoryMb", Debug.getPss() / 1024)
            .put("systemMemoryUsedMb", (mem.totalMem - mem.availMem) / 1048576)
            .put("systemMemoryTotalMb", mem.totalMem / 1048576)
            .put("lowMemory", mem.lowMemory)
            .put("batteryPercent", if (level >= 0 && scale > 0) level * 100 / scale else -1)
            .put("charging", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            .put("temperatureC", (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            .put("batteryCurrentMa", if (current == Int.MIN_VALUE) JSONObject.NULL else current / 1000)
            .put("network", network).put("signal", signal).put("addresses", addresses)
    }
}
