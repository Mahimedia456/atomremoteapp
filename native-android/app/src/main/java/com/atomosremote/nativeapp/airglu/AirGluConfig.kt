package com.atomosremote.nativeapp.airglu

import android.content.Context

enum class AirGluRole { SERVER, CLIENT }

data class AirGluConfig(
    val role: AirGluRole = AirGluRole.CLIENT,
    val rfChannel: Int = 1,
    val groupName: String = "ATOMOS",
    val gangRecord: Boolean = true,
    val timecodeSource: String = "AirGlu",
    val deviceVersion: String? = null
)

class AirGluConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("atomos_airglu", Context.MODE_PRIVATE)

    fun load(): AirGluConfig = AirGluConfig(
        role = runCatching { AirGluRole.valueOf(prefs.getString("role", AirGluRole.CLIENT.name)!!) }.getOrDefault(AirGluRole.CLIENT),
        rfChannel = prefs.getInt("channel", 1).coerceIn(1, 12),
        groupName = prefs.getString("group", "ATOMOS") ?: "ATOMOS",
        gangRecord = prefs.getBoolean("gangRecord", true),
        timecodeSource = prefs.getString("timecodeSource", "AirGlu") ?: "AirGlu",
        deviceVersion = prefs.getString("deviceVersion", null)
    )

    fun save(config: AirGluConfig) {
        prefs.edit()
            .putString("role", config.role.name)
            .putInt("channel", config.rfChannel.coerceIn(1, 12))
            .putString("group", config.groupName)
            .putBoolean("gangRecord", config.gangRecord)
            .putString("timecodeSource", config.timecodeSource)
            .putString("deviceVersion", config.deviceVersion)
            .apply()
    }
}
