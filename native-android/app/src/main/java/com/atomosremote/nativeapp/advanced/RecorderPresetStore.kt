package com.atomosremote.nativeapp.advanced

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class RecorderPresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("atomos_recorder_presets", Context.MODE_PRIVATE)

    fun loadAll(): List<RecorderPreset> {
        val raw = prefs.getString("items", null) ?: return defaults()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cmds = o.optJSONArray("commandKeys") ?: JSONArray()
                    add(
                        RecorderPreset(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", "Preset ${i + 1}"),
                            commandKeys = buildList {
                                for (j in 0 until cmds.length()) add(cmds.optString(j))
                            }.filter { it.isNotBlank() },
                            note = o.optString("note", "")
                        )
                    )
                }
            }
        }.getOrElse { defaults() }
    }

    fun save(preset: RecorderPreset) {
        val items = loadAll().toMutableList()
        val index = items.indexOfFirst { it.id == preset.id }
        if (index >= 0) items[index] = preset else items.add(preset)
        persist(items)
    }

    fun create(name: String, commandKeys: List<String>, note: String = ""): RecorderPreset {
        val preset = RecorderPreset(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Recorder Preset" },
            commandKeys = commandKeys.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            note = note.trim()
        )
        save(preset)
        return preset
    }

    fun remove(id: String) {
        persist(loadAll().filterNot { it.id == id })
    }

    private fun persist(items: List<RecorderPreset>) {
        val arr = JSONArray()
        items.forEach { preset ->
            arr.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("note", preset.note)
                    .put("commandKeys", JSONArray(preset.commandKeys))
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun defaults(): List<RecorderPreset> = listOf(
        RecorderPreset(
            id = "monitor-clean",
            name = "Monitor Clean",
            commandKeys = listOf("waveform-off", "zebra-off", "peaking-off", "false-color-off", "zoom-off", "guides-off"),
            note = "Uses only validated commands that exist in the active protocol profile."
        ),
        RecorderPreset(
            id = "monitor-analysis",
            name = "Monitor Analysis",
            commandKeys = listOf("waveform-on", "peaking-on"),
            note = "Example high-level preset. Missing command payloads remain locked."
        )
    )
}
