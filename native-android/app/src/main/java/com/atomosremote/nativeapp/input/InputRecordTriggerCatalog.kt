package com.atomosremote.nativeapp.input

object InputRecordTriggerCatalog {
    const val TRIGGER_SOURCE_GET: Int = 0x006B
    const val TRIGGER_ENABLED_GET: Int = 0x006D
    const val TRIGGER_SOURCE_LIST_GET: Int = 0x007E

    fun commandHex(value: Int): String =
        "0x" + value.toString(16).uppercase().padStart(4, '0')
}
