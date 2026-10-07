package com.atomosremote.nativeapp.input

object InputExtendedReadPlan {
    data class Probe(val key: String, val commandId: Int, val label: String)

    val probes = listOf(
        // Change state first for safe refresh.
        Probe("changeId", 0x0083, "Input Change"),

        // Previously UI-only/partial values now explicitly queried.
        Probe("hdmiStandard", 0x0069, "HDMI Standard"),
        Probe("triggerSource", 0x006B, "Trigger Source"),
        Probe("triggerEnabled", 0x006D, "Trigger Enabled"),
        Probe("hdrCamera", 0x0073, "Camera"),
        Probe("hdrGamma", 0x0075, "Gamma"),
        Probe("hdrGamut", 0x0077, "Gamut"),
        Probe("hdrAuto", 0x0079, "HDR Auto"),

        // Capability lists.
        Probe("hdrCameraList", 0x007B, "Camera Options"),
        Probe("hdrGammaList", 0x007C, "Gamma Options"),
        Probe("hdrGamutList", 0x007D, "Gamut Options"),

        // 20.03.6.10-6.12.
        Probe("sdiLink", 0x007F, "SDI Link"),
        Probe("activeSdiSource", 0x0080, "Active SDI Source"),
        Probe("inputRange", 0x0081, "Input Range"),
        Probe("rawStatus", 0x0084, "RAW Input"),
        Probe("cameraMetadata", 0x0085, "Camera Metadata"),

        // HDMI capability list.
        Probe("hdmiStandardList", 0x0086, "HDMI Standard Options")
    )

    /**
     * Converts recorder payloads to official ATOMOS-facing names.
     * Numeric/internal values never leave this layer.
     */
    fun displayValue(
        key: String,
        body: ByteArray,
        printable: String?
    ): String {
        val field1 = InputProtoReader.fieldVarint(body, 1)

        return when (key) {
            "changeId" -> "Synced"

            "hdmiStandard" ->
                InputOfficialEnumNames.hdmiStandard(field1) ?: "Recorder value received"

            "triggerSource" ->
                InputOfficialEnumNames.triggerSource(field1) ?: "Recorder value received"

            "triggerEnabled" ->
                InputOfficialEnumNames.enabled(field1) ?: "Recorder value received"

            "hdrCamera" ->
                InputOfficialEnumNames.camera(field1) ?: "Recorder value received"

            "hdrGamma" ->
                InputOfficialEnumNames.gamma(field1) ?: "Recorder value received"

            "hdrGamut" ->
                InputOfficialEnumNames.gamut(field1) ?: "Recorder value received"

            "hdrAuto" ->
                InputOfficialEnumNames.enabled(field1) ?: "Recorder value received"

            "hdrCameraList" ->
                InputOfficialEnumNames.cameraList(InputProtoReader.fieldVarints(body, 1))
                    ?: "Recorder value received"

            "hdrGammaList" ->
                InputOfficialEnumNames.gammaList(InputProtoReader.fieldVarints(body, 1))
                    ?: "Recorder value received"

            "hdrGamutList" ->
                InputOfficialEnumNames.gamutList(InputProtoReader.fieldVarints(body, 1))
                    ?: "Recorder value received"

            "sdiLink" ->
                InputOfficialEnumNames.linkState(field1) ?: "Recorder value received"

            "activeSdiSource" ->
                InputOfficialEnumNames.activeSdiSource(field1) ?: "Recorder value received"

            "inputRange" ->
                InputOfficialEnumNames.range(field1) ?: "Recorder value received"

            "rawStatus" -> {
                val enabled = InputOfficialEnumNames.enabled(field1)
                val rawType = InputOfficialEnumNames.rawType(
                    InputProtoReader.fieldVarint(body, 2)
                )
                when {
                    enabled == "Off" -> "Off"
                    enabled == "On" && rawType != null -> "On - $rawType"
                    enabled != null -> enabled
                    else -> "Recorder value received"
                }
            }

            "cameraMetadata" ->
                printable?.trim()?.takeIf { it.isNotEmpty() }
                    ?: InputProtoReader.firstUtf8(body)
                    ?: "Recorder metadata received"

            "hdmiStandardList" ->
                InputOfficialEnumNames.hdmiStandardList(
                    InputProtoReader.fieldVarints(body, 1)
                ) ?: "Recorder value received"

            else ->
                printable?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Recorder value received"
        }
    }
}

data class InputExtendedSnapshot(
    val generation: Int = 0,
    val running: Boolean = false,
    val unchanged: Boolean = false,
    val completed: Int = 0,
    val expected: Int = 0,
    val values: Map<String, String> = emptyMap(),
    val responseTypes: Map<String, Int> = emptyMap(),
    val rawPayloads: Map<String, String> = emptyMap(),
    val status: String = "Not refreshed"
)
