package com.atomosremote.nativeapp.audio

/** R26.46 layout-only routing: group all AG2 REC/monitor pairs together.
 * Does not claim that f3/f4 raw IDs correlate one-to-one with channel pair keys.
 * Speaker level and mute are independent; no write commands are added.
 */
object AudioR2646RoutingLayout {
    val channelPairKeys: List<String> = listOf("ch12", "ch34", "ch56", "ch78", "ch910", "ch1112")
    val recordingKeys: Set<String> = (channelPairKeys + "analogRecord").toSet()
    val separateSpeakerKeys: Set<String> = setOf("speaker", "speakerMute")
    val headphoneMonitorKeys: Set<String> = setOf("phones", "analogMonitor")
    fun recordPairs(rows: List<AudioR2641Presentation.Row>): List<AudioR2641Presentation.Row> =
        channelPairKeys.mapNotNull { key -> rows.firstOrNull { it.key == key } }
    fun groupingIsSafe(): Boolean =
        recordingKeys.intersect(separateSpeakerKeys).isEmpty() &&
        channelPairKeys.distinct().size == 6 &&
        recordingKeys.size == 7 &&
        separateSpeakerKeys.size == 2
    fun verifiedPairEnableState(@Suppress("UNUSED_PARAMETER") key: String,
                                @Suppress("UNUSED_PARAMETER") enabledRawIds: List<Int>): Boolean? = null
    fun mayWriteRecorder(@Suppress("UNUSED_PARAMETER") key: String): Boolean = false
}
