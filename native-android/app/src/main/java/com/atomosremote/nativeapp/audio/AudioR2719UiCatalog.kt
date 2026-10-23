package com.atomosremote.nativeapp.audio

/** R27.19 presentation vocabulary ONLY. No commands or guessed wire enum mappings. */
object AudioR2719UiCatalog {
    val gainChoices: List<Int> = (-10..20 step 5).toList()
    val micInputChoices = listOf("Line Level", "Mic Level")
    val meterOrientationChoices = listOf("Vertical", "Horizontal")
    val audioEmbedChoices = listOf("Off", "1/2")
    val analogMonitorChoices = listOf("Mic / Line", "1/2", "3/4", "5/6", "7/8", "9/10", "11/12")
    val recorderChannels = listOf("MIC/LINE L", "MIC/LINE R", "1/2", "3/4", "5/6", "7/8", "9/10", "11/12")
    fun greyedChannel(position: Int): Boolean = position == 6 || position == 7
    fun formatGain(value: Int): String = if (value > 0) "+${value}.0 dB" else "${value}.0 dB"
}
