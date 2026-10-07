package com.atomosremote.nativeapp.atomos

enum class AtomosFamily { SHOGUN, NINJA, NEON, GENERIC }
enum class AtomosCapability { BLE_REMOTE, WIFI, ETHERNET, AIRGLU_RF, NDI_HX3, LANC, SDI, HDMI }
enum class CapabilityStatus { BUILT_IN, REQUIRES_ACCESSORY, OPTIONAL_ACTIVATION, VALIDATE, NOT_AVAILABLE }

data class AtomosDeviceProfile(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
    val family: AtomosFamily,
    val capabilities: Map<AtomosCapability, CapabilityStatus>,
    val maxRaw: String? = null,
    val accessoryNote: String? = null,
    val notes: String
) {
    val ble: Boolean get() = capabilities[AtomosCapability.BLE_REMOTE] !in listOf(null, CapabilityStatus.NOT_AVAILABLE)
    val network: Boolean get() = capabilities[AtomosCapability.WIFI] !in listOf(null, CapabilityStatus.NOT_AVAILABLE) ||
        capabilities[AtomosCapability.ETHERNET] !in listOf(null, CapabilityStatus.NOT_AVAILABLE)
    val airGlu: Boolean get() = capabilities[AtomosCapability.AIRGLU_RF] !in listOf(null, CapabilityStatus.NOT_AVAILABLE)
}

object AtomosProfiles {
    val shogunProfiles = listOf(
        AtomosDeviceProfile(
            id = "shogun-ultra",
            displayName = "Shogun Ultra",
            aliases = listOf("shogun ultra", "sgua"),
            family = AtomosFamily.SHOGUN,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.BUILT_IN,
                AtomosCapability.WIFI to CapabilityStatus.BUILT_IN,
                AtomosCapability.ETHERNET to CapabilityStatus.BUILT_IN,
                AtomosCapability.AIRGLU_RF to CapabilityStatus.BUILT_IN,
                AtomosCapability.NDI_HX3 to CapabilityStatus.OPTIONAL_ACTIVATION,
                AtomosCapability.LANC to CapabilityStatus.BUILT_IN,
                AtomosCapability.SDI to CapabilityStatus.BUILT_IN,
                AtomosCapability.HDMI to CapabilityStatus.BUILT_IN
            ),
            maxRaw = "8K RAW",
            notes = "Built-in BLE remote, Wi-Fi 6E, 1GbE and AirGlu RF on AtomOS 11. Current-firmware Shogun Ultra target; no AtomOS 10.72 downgrade dependency."
        ),
        AtomosDeviceProfile(
            id = "shogun",
            displayName = "Shogun",
            aliases = listOf("shogun"),
            family = AtomosFamily.SHOGUN,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.BUILT_IN,
                AtomosCapability.WIFI to CapabilityStatus.BUILT_IN,
                AtomosCapability.ETHERNET to CapabilityStatus.BUILT_IN,
                AtomosCapability.AIRGLU_RF to CapabilityStatus.BUILT_IN,
                AtomosCapability.NDI_HX3 to CapabilityStatus.OPTIONAL_ACTIVATION,
                AtomosCapability.LANC to CapabilityStatus.BUILT_IN,
                AtomosCapability.SDI to CapabilityStatus.BUILT_IN,
                AtomosCapability.HDMI to CapabilityStatus.BUILT_IN
            ),
            maxRaw = "6K RAW",
            notes = "Current Shogun family with BLE remote, Wi-Fi 6, 1GbE and AirGlu RF."
        ),
        AtomosDeviceProfile(
            id = "shogun-connect",
            displayName = "Shogun CONNECT",
            aliases = listOf("shogun connect", "shogunconnect"),
            family = AtomosFamily.SHOGUN,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.VALIDATE,
                AtomosCapability.WIFI to CapabilityStatus.BUILT_IN,
                AtomosCapability.ETHERNET to CapabilityStatus.BUILT_IN,
                AtomosCapability.AIRGLU_RF to CapabilityStatus.BUILT_IN,
                AtomosCapability.SDI to CapabilityStatus.BUILT_IN,
                AtomosCapability.HDMI to CapabilityStatus.BUILT_IN
            ),
            notes = "Connectivity-rich Shogun generation. BLE control capability is treated as firmware-dependent until validated on hardware."
        )
    )

    val ninjaProfiles = listOf(
        AtomosDeviceProfile(
            id = "ninja-ultra-connect",
            displayName = "Ninja Ultra + ATOMOS Connect",
            aliases = listOf("ninja ultra"),
            family = AtomosFamily.NINJA,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.WIFI to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.ETHERNET to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.AIRGLU_RF to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.NDI_HX3 to CapabilityStatus.OPTIONAL_ACTIVATION,
                AtomosCapability.LANC to CapabilityStatus.BUILT_IN,
                AtomosCapability.HDMI to CapabilityStatus.BUILT_IN
            ),
            accessoryNote = "ATOMOS Connect required for BLE/network/AirGlu features",
            notes = "Ninja Ultra gains BLE, Wi-Fi 6E, Ethernet and AirGlu through ATOMOS Connect."
        ),
        AtomosDeviceProfile(
            id = "ninja-connect",
            displayName = "Ninja + ATOMOS Connect",
            aliases = listOf("ninja"),
            family = AtomosFamily.NINJA,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.WIFI to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.ETHERNET to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.AIRGLU_RF to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.NDI_HX3 to CapabilityStatus.OPTIONAL_ACTIVATION,
                AtomosCapability.LANC to CapabilityStatus.BUILT_IN,
                AtomosCapability.HDMI to CapabilityStatus.BUILT_IN
            ),
            accessoryNote = "ATOMOS Connect required for BLE/network/AirGlu features",
            notes = "Current Ninja family with optional ATOMOS Connect connectivity module."
        ),
        AtomosDeviceProfile(
            id = "ninja-v-sync",
            displayName = "Ninja V / V+ + AtomX SYNC",
            aliases = listOf("ninja v+", "ninja v plus", "ninja v"),
            family = AtomosFamily.NINJA,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.AIRGLU_RF to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.WIFI to CapabilityStatus.NOT_AVAILABLE,
                AtomosCapability.ETHERNET to CapabilityStatus.NOT_AVAILABLE,
                AtomosCapability.LANC to CapabilityStatus.BUILT_IN,
                AtomosCapability.HDMI to CapabilityStatus.BUILT_IN
            ),
            accessoryNote = "AtomX SYNC module required for the documented AtomRemote BLE workflow",
            notes = "Legacy Ninja V/V+ AtomRemote profile. ATOMOS Connect has a different connectivity path and should be validated separately."
        ),
        AtomosDeviceProfile(
            id = "ninja-v-connect",
            displayName = "Ninja V / V+ + ATOMOS Connect",
            aliases = listOf("ninja v connect", "ninja v+ connect"),
            family = AtomosFamily.NINJA,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.VALIDATE,
                AtomosCapability.WIFI to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.ETHERNET to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.AIRGLU_RF to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.NDI_HX3 to CapabilityStatus.OPTIONAL_ACTIVATION,
                AtomosCapability.SDI to CapabilityStatus.REQUIRES_ACCESSORY,
                AtomosCapability.HDMI to CapabilityStatus.BUILT_IN
            ),
            accessoryNote = "ATOMOS Connect required",
            notes = "Network/AirGlu capability is provided by ATOMOS Connect; BLE remote behavior remains hardware/firmware validated."
        )
    )

    val otherProfiles = listOf(
        AtomosDeviceProfile(
            id = "neon",
            displayName = "NEON",
            aliases = listOf("neon"),
            family = AtomosFamily.NEON,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.VALIDATE,
                AtomosCapability.ETHERNET to CapabilityStatus.BUILT_IN
            ),
            notes = "Model and firmware capabilities must be validated before remote commands are enabled."
        ),
        AtomosDeviceProfile(
            id = "generic",
            displayName = "ATOMOS Recorder",
            aliases = listOf("atomos"),
            family = AtomosFamily.GENERIC,
            capabilities = mapOf(
                AtomosCapability.BLE_REMOTE to CapabilityStatus.VALIDATE,
                AtomosCapability.WIFI to CapabilityStatus.VALIDATE,
                AtomosCapability.ETHERNET to CapabilityStatus.VALIDATE,
                AtomosCapability.AIRGLU_RF to CapabilityStatus.VALIDATE
            ),
            notes = "Generic fallback. The app inspects actual transports instead of assuming capability from the model name."
        )
    )

    val profiles: List<AtomosDeviceProfile> = shogunProfiles + ninjaProfiles + otherProfiles

    fun match(name: String?): AtomosDeviceProfile? {
        val n = name?.lowercase()?.trim().orEmpty()
        if (n.isBlank()) return null
        return profiles
            .flatMap { profile -> profile.aliases.map { alias -> profile to alias } }
            .filter { (_, alias) -> n.contains(alias) }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.first
    }

    fun byId(id: String?): AtomosDeviceProfile? = profiles.firstOrNull { it.id == id }
}
