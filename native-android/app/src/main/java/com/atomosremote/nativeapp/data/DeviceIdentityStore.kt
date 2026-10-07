package com.atomosremote.nativeapp.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedAtomosDevice(
    val address: String,
    val name: String,
    val profileId: String,
    val did: String? = null,
    val aid: String? = null,
    val bid: String? = null,
    val atomOs: String? = null,
    val airGlu: String? = null,
    val preferred: Boolean = false
)

class DeviceIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("atomos_devices", Context.MODE_PRIVATE)

    fun save(device: SavedAtomosDevice) {
        val current = loadAll().associateBy { it.address }.toMutableMap()
        if (device.preferred) {
            current.replaceAll { _, existing -> existing.copy(preferred = false) }
        }
        current[device.address] = device
        writeAll(current.values.toList())
        prefs.edit().putString("primary_address", device.address).apply()
    }

    fun load(): SavedAtomosDevice? {
        val all = loadAll()
        val primary = prefs.getString("primary_address", null)
        return all.firstOrNull { it.address == primary }
            ?: all.firstOrNull { it.preferred }
            ?: all.firstOrNull()
            ?: loadLegacy()
    }

    fun loadAll(): List<SavedAtomosDevice> {
        val raw = prefs.getString("devices_json", null) ?: return loadLegacy()?.let(::listOf).orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        SavedAtomosDevice(
                            address = o.getString("address"),
                            name = o.optString("name", "ATOMOS Recorder"),
                            profileId = o.optString("profileId", "generic"),
                            did = o.optString("did", "").ifBlank { null },
                            aid = o.optString("aid", "").ifBlank { null },
                            bid = o.optString("bid", "").ifBlank { null },
                            atomOs = o.optString("atomOs", "").ifBlank { null },
                            airGlu = o.optString("airGlu", "").ifBlank { null },
                            preferred = o.optBoolean("preferred", false)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun remove(address: String) {
        writeAll(loadAll().filterNot { it.address == address })
        if (prefs.getString("primary_address", null) == address) {
            prefs.edit().remove("primary_address").apply()
        }
    }

    fun clear() = prefs.edit().clear().apply()

    private fun writeAll(devices: List<SavedAtomosDevice>) {
        val array = JSONArray()
        devices.forEach { d ->
            array.put(JSONObject().apply {
                put("address", d.address)
                put("name", d.name)
                put("profileId", d.profileId)
                put("did", d.did ?: "")
                put("aid", d.aid ?: "")
                put("bid", d.bid ?: "")
                put("atomOs", d.atomOs ?: "")
                put("airGlu", d.airGlu ?: "")
                put("preferred", d.preferred)
            })
        }
        prefs.edit().putString("devices_json", array.toString()).apply()
    }

    private fun loadLegacy(): SavedAtomosDevice? {
        val address = prefs.getString("address", null) ?: return null
        return SavedAtomosDevice(
            address = address,
            name = prefs.getString("name", "ATOMOS Recorder") ?: "ATOMOS Recorder",
            profileId = prefs.getString("profileId", "generic") ?: "generic",
            did = prefs.getString("did", null),
            aid = prefs.getString("aid", null),
            bid = prefs.getString("bid", null),
            atomOs = prefs.getString("atomOs", null),
            airGlu = prefs.getString("airGlu", null),
            preferred = true
        )
    }
}
