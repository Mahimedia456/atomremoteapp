package com.atomosremote.nativeapp.monitor

data class MonitorAnamorphicState(
    val currentMode: Int? = null,
    val pendingMode: Int? = null,
    val status: String = "Anamorphic state not read yet"
) {
    companion object {
        // Exact enum order recovered from the latest supplied macOS Contents.
        val macOsModeFamily: List<Pair<Int, String>> = listOf(
            0 to "Off",
            1 to "2x 4:3",
            2 to "2x",
            3 to "1.5x",
            4 to "1.33x",
            5 to "1.8x",
            6 to "1.25x",
            7 to "1.79x",
            8 to "1.65x",
            9 to "1.6x"
        )

        fun label(value: Int?): String =
            macOsModeFamily.firstOrNull { it.first == value }?.second
                ?: if (value == null) "Not reported" else "Mode $value"
    }
}
