package com.atomosremote.nativeapp.audio

/** R27.15 one-shot Audio In Level / InputSourceGet read-only correlation.
 * No SET, no inferred Mic/Line enum, no polling and no shared transport changes.
 */
object AudioR2715InputLevelTrial {
    fun eligible(key: String, command: Int): Boolean =
        key == "inputLevel" && command == 0x06B3

    fun matches(key: String, command: Int, reply: Int, local: Boolean,
                attConfirmed: Boolean, safeOwner: Boolean, sinceAttMs: Long): Boolean =
        eligible(key, command) && local && attConfirmed && safeOwner &&
            sinceAttMs in 0L..30_000L && (reply and 0x0FFF) == 0x06AE
}
