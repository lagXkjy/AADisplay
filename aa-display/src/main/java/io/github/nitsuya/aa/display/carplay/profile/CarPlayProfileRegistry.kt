package io.github.nitsuya.aa.display.carplay.profile

/**
 * Code-registered Source / HU profiles (no prefs UI).
 *
 * - New phone spoof: add a [CarPlaySourceIdentity] and point [defaultSource] at it.
 * - New head unit: add a [CarPlayHuProfile] to [huProfiles] with hints / future
 *   protocol matchers; wire behavior via profile fields, not vendor name checks.
 */
object CarPlayProfileRegistry {
    val iphone14_2: CarPlaySourceIdentity = CarPlaySourceIdentity(
        id = "iphone14_2",
        iapName = "iPhone",
        iapModel = "iPhone14,2",
        iapManufacturer = "Apple",
        iapFirmwareVersion = "17.5.1",
        iapHardwareVersion = "1.0",
        iapLanguage = "zh-CN",
        iapRegion = "zh-CN",
        airplayModel = "iPhone14,2",
        airplayOsName = "iPhone OS",
        airplayName = "AADisplay",
    )

    /** DiPlay / generic wireless CarPlay receiver. */
    val generic: CarPlayHuProfile = CarPlayHuProfile(
        id = "generic",
        dialPolicy = CarPlayDialPolicy.OUTBOUND_ACCESSORY,
        userAgent = "AirPlay/950.7.1",
        hkp = "3",
        osVersion = "16.0",
        osBuildVersion = "20A392",
        sourceVersion = "950.7.1",
        usesMib3WirelessPath = false,
    )

    /** Audi MMI / MIB3 CSM path (validated against audi.11). */
    val mib3: CarPlayHuProfile = CarPlayHuProfile(
        id = "mib3",
        dialPolicy = CarPlayDialPolicy.INBOUND_PREFERRED,
        nameHints = listOf("audi", "mmi"),
        ssidHints = listOf("audi", "mmi"),
        userAgent = "AirPlay/980.77.1",
        hkp = "2",
        osVersion = "17.5.1",
        osBuildVersion = "17.5.1",
        sourceVersion = "980.77.1",
        usesMib3WirelessPath = true,
    )

    /** Ordered: first matching non-generic profile wins in the resolver. */
    val huProfiles: List<CarPlayHuProfile> = listOf(mib3, generic)

    fun defaultSource(): CarPlaySourceIdentity = iphone14_2

    fun defaultHu(): CarPlayHuProfile = generic
}
