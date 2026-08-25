package org.nemo.fujibanks.fuji

/**
 * Fujifilm vendor device properties.
 *
 * The custom recipe banks C1-C7 live behind three groups of properties, all
 * reachable with plain GetDevicePropValue / SetDevicePropValue:
 *
 *   D18C          slot selector — write 1..7, then every following read/write
 *                 targets that bank
 *   D18D          preset name (PTP string)
 *   D18E..D1A5    the 24 recipe properties
 *
 * The mapping and encodings below come from FilmKit (MIT), which derived them
 * from Wireshark captures of X RAW Studio against an X100VI. Bodies other than
 * the X100VI are unverified — DumpScreen exists to check them before trusting
 * this table.
 */
object FujiProps {
    const val PRESET_SLOT = 0xD18C
    const val PRESET_NAME = 0xD18D

    const val FIRST_PRESET_PROP = 0xD18E
    const val LAST_PRESET_PROP = 0xD1A5

    // The 24 recipe properties.
    const val IMAGE_SIZE = 0xD18E
    const val IMAGE_QUALITY = 0xD18F
    const val DYNAMIC_RANGE = 0xD190      // raw percentage: 100 / 200 / 400
    const val UNKNOWN_D191 = 0xD191       // always 0 on X100VI
    const val FILM_SIMULATION = 0xD192
    const val MONO_WC = 0xD193            // warm/cool, x10, B&W sims only
    const val MONO_MG = 0xD194            // magenta/green, x10, B&W sims only
    const val GRAIN_EFFECT = 0xD195       // flat enum 1..5
    const val COLOR_CHROME = 0xD196       // 1=Off 2=Weak 3=Strong
    const val COLOR_CHROME_FX_BLUE = 0xD197
    const val SMOOTH_SKIN = 0xD198
    const val WHITE_BALANCE = 0xD199
    const val WB_SHIFT_R = 0xD19A
    const val WB_SHIFT_B = 0xD19B
    const val WB_COLOR_TEMP = 0xD19C      // only writable when WB == ColorTemp
    const val HIGHLIGHT_TONE = 0xD19D     // x10
    const val SHADOW_TONE = 0xD19E        // x10
    const val COLOR = 0xD19F              // x10, rejected by B&W sims
    const val SHARPNESS = 0xD1A0          // x10
    const val HIGH_ISO_NR = 0xD1A1        // proprietary non-linear encoding
    const val CLARITY = 0xD1A2            // x10
    const val LONG_EXP_NR = 0xD1A3
    const val COLOR_SPACE = 0xD1A4
    const val UNKNOWN_D1A5 = 0xD1A5       // always 7 on X100VI

    // Live shooting properties, useful for identifying the body during a dump.
    const val FIRMWARE_VERSION = 0xD186

    /**
     * RAW conversion. Both report unsupported until a RAF has been uploaded —
     * with nothing loaded there is no profile to read and nothing to convert.
     */
    const val RAW_CONV_PROFILE = 0xD185
    const val START_RAW_CONVERSION = 0xD183

    val NAMES: Map<Int, String> = mapOf(
        PRESET_SLOT to "PresetSlot",
        PRESET_NAME to "PresetName",
        IMAGE_SIZE to "ImageSize",
        IMAGE_QUALITY to "ImageQuality",
        DYNAMIC_RANGE to "DynamicRange%",
        UNKNOWN_D191 to "?D191",
        FILM_SIMULATION to "FilmSimulation",
        MONO_WC to "MonoWC x10",
        MONO_MG to "MonoMG x10",
        GRAIN_EFFECT to "GrainEffect",
        COLOR_CHROME to "ColorChrome",
        COLOR_CHROME_FX_BLUE to "ColorChromeFxBlue",
        SMOOTH_SKIN to "SmoothSkin",
        WHITE_BALANCE to "WhiteBalance",
        WB_SHIFT_R to "WBShiftR",
        WB_SHIFT_B to "WBShiftB",
        WB_COLOR_TEMP to "ColorTemp(K)",
        HIGHLIGHT_TONE to "HighlightTone x10",
        SHADOW_TONE to "ShadowTone x10",
        COLOR to "Color x10",
        SHARPNESS to "Sharpness x10",
        HIGH_ISO_NR to "HighIsoNR",
        CLARITY to "Clarity x10",
        LONG_EXP_NR to "LongExpNR",
        COLOR_SPACE to "ColorSpace",
        UNKNOWN_D1A5 to "?D1A5",
        FIRMWARE_VERSION to "FirmwareVersion",
        RAW_CONV_PROFILE to "RawConvProfile",
        START_RAW_CONVERSION to "StartRawConversion",
    )

    fun name(propId: Int): String = NAMES[propId] ?: "0x%04X".format(propId)

    /** Number of recipe banks the camera exposes. */
    const val SLOT_COUNT = 7

    /**
     * Write order matters. The camera rejects ColorTemp unless the WB mode was
     * set first, and rejects Color/MonoWC/MonoMG unless the film simulation
     * already agrees with them. This is the order the official app uses.
     */
    val WRITE_ORDER: List<Int> = listOf(
        IMAGE_SIZE, IMAGE_QUALITY,
        DYNAMIC_RANGE, UNKNOWN_D191,
        FILM_SIMULATION,
        MONO_WC, MONO_MG,
        GRAIN_EFFECT, COLOR_CHROME, COLOR_CHROME_FX_BLUE, SMOOTH_SKIN,
        WHITE_BALANCE, WB_COLOR_TEMP, WB_SHIFT_R, WB_SHIFT_B,
        HIGHLIGHT_TONE, SHADOW_TONE, COLOR, SHARPNESS, HIGH_ISO_NR, CLARITY,
        LONG_EXP_NR, COLOR_SPACE, UNKNOWN_D1A5,
    )

    /**
     * Values observed on camera scans, used for properties we do not model in
     * the UI and have no baseline for.
     */
    val UNKNOWN_DEFAULTS: Map<Int, Int> = mapOf(
        IMAGE_SIZE to 7,        // L 3:2
        IMAGE_QUALITY to 4,
        UNKNOWN_D191 to 0,
        HIGH_ISO_NR to 0x4000,
        LONG_EXP_NR to 1,       // On
        COLOR_SPACE to 1,       // sRGB
        UNKNOWN_D1A5 to 7,
    )
}
