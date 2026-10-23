package com.atomosremote.nativeapp.audio

/** R26.50: Only interprets actual 0x8647 GET and locally operator-confirmed REC IDs.
 * This adapter does not construct or submit any recorder command.
 */
object AudioR2650ReadbackUi {
    fun analogRow(key: String, mapping: Map<String, Long>, hex: String): AudioR2641Presentation.Row {
        require(key == "analogLeft" || key == "analogRight")
        val display = if (key == "analogLeft") "Analog Left REC" else "Analog Right REC"
        return buildRecRow(key, display, mapping, hex)
    }

    fun enrich(rows: List<AudioR2641Presentation.Row>, mapping: Map<String, Long>,
               hex: String): List<AudioR2641Presentation.Row> {
        val page = AudioR26PageSnapshot.decode(hex) ?: return rows
        val enabled = page.recordEnabledList.toSet()
        val valid = page.recordValidList.toSet()
        if (enabled.size != page.recordEnabledList.size ||
            valid.size != page.recordValidList.size || !valid.containsAll(enabled)) return rows
        return rows.map { row ->
            when {
                row.key in AudioRecCalibratedChannels.pairKeys ->
                    buildRecRow(row.key, row.label, mapping, hex)
                row.key == "analogRecord" -> {
                    val mapped = mapping.count { (key, id) ->
                        key in AudioRecCalibratedChannels.pairKeys && id in valid
                    }
                    row.copy(value = "${enabled.size}/${valid.size} enabled IDs",
                        detail = "Recorder f3 enabled=$enabled; f4 valid=$valid. " +
                            "$mapped operator-mapped channel(s); no ID guessed.")
                }
                else -> row
            }
        }
    }

    private fun buildRecRow(key: String, label: String, mapping: Map<String, Long>,
                            hex: String): AudioR2641Presentation.Row {
        val id = mapping[key]
        val p = AudioR26PageSnapshot.decode(hex)
        val valid = p?.recordValidList?.toSet().orEmpty()
        val enabled = p?.recordEnabledList?.toSet().orEmpty()
        val (value, detail, evidence) = when {
            id == null -> Triple("Not calibrated", "LEARN this exact physical REC channel, Refresh, CONFIRM the candidate ID.",
                AudioR2641Presentation.Evidence.NOT_REPORTED)
            p == null -> Triple("Waiting GET", "REC ID $id locally mapped, but a current recorder GET is required.",
                AudioR2641Presentation.Evidence.NOT_REPORTED)
            id !in valid -> Triple("Not available", "Saved REC ID $id absent from current f4 valid IDs.",
                AudioR2641Presentation.Evidence.NOT_REPORTED)
            else -> Triple(if (id in enabled) "REC ON" else "REC OFF",
                "Recorder f3/f4 readback for operator-calibrated REC ID $id. Android SET not transmitted.",
                AudioR2641Presentation.Evidence.AG2_CALIBRATED)
        }
        return AudioR2641Presentation.Row(key, label, value, detail, evidence)
    }
}
