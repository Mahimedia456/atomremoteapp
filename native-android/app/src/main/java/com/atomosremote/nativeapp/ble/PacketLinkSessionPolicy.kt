package com.atomosremote.nativeapp.ble

object PacketLinkSessionPolicy {
    const val AUTO_POST_READY_INPUT_ENABLED: Boolean = false

    fun quarantineAfterPacketLinkAttTimeout(purpose: String?): Boolean =
        purpose == "plink18-read" || purpose == "plink18-write"
}
