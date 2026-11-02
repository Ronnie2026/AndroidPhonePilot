package dev.phonepilot

import android.content.Context
import java.util.UUID

object RuntimeState {
    @Volatile var serviceRunning = false
    @Volatile var webSocketRunning = false
    @Volatile var httpRunning = false
    @Volatile var startedAt = 0L
    @Volatile var lastError = ""
    @Volatile var clients = 0
    @Volatile var revokeToken: ((String) -> Unit)? = null
    @Volatile var tokenConnections: ((String) -> Int)? = null
    fun token(context: Context): String = TokenStore.selected(context)?.secret ?: ""
    fun host(context: Context) = if (context.getSharedPreferences("pilot", 0).getBoolean("lan", false)) "0.0.0.0" else "127.0.0.1"
}
