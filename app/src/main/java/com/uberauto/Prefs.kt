package com.uberauto

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var minPerKm: Float
        get() = sp.getFloat("minPerKm", 20f)
        set(v) = sp.edit().putFloat("minPerKm", v).apply()

    /** 是否把「去接客的距離」也算進公里數 */
    var includePickup: Boolean
        get() = sp.getBoolean("includePickup", false)
        set(v) = sp.edit().putBoolean("includePickup", v).apply()

    var lastScreenText: String
        get() = sp.getString("lastScreen", "") ?: ""
        set(v) = sp.edit().putString("lastScreen", v).apply()

    val log: String get() = sp.getString("log", "") ?: ""

    fun addLog(line: String) {
        val time = SimpleDateFormat("MM/dd HH:mm:ss", Locale.TAIWAN).format(Date())
        val lines = (listOf("[$time] $line") + log.lines().filter { it.isNotBlank() }).take(100)
        sp.edit().putString("log", lines.joinToString("\n")).apply()
    }

    fun clearLog() = sp.edit().putString("log", "").apply()
}
