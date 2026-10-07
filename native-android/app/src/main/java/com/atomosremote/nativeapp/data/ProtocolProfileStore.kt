package com.atomosremote.nativeapp.data

import android.content.Context
import com.atomosremote.nativeapp.atomos.ProtocolProfile

class ProtocolProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("atomos_protocol", Context.MODE_PRIVATE)

    fun save(json: String) {
        val parsed = ProtocolProfile.fromJson(json)
        require(parsed.validated) { "Protocol profile must explicitly set validated=true" }
        prefs.edit().putString("profile_json", json).apply()
    }

    fun load(): ProtocolProfile? {
        val json = prefs.getString("profile_json", null) ?: return null
        return runCatching { ProtocolProfile.fromJson(json) }.getOrNull()
    }

    fun template(): String = context.assets.open("protocol_profile_template.json").bufferedReader().use { it.readText() }
}
