package org.nemo.fujibanks.fuji

/** Film simulations, in the camera's own numbering. */
object FilmSim {
    const val PROVIA = 0x01
    const val VELVIA = 0x02
    const val ASTIA = 0x03
    const val PRO_NEG_HI = 0x04
    const val PRO_NEG_STD = 0x05
    const val MONOCHROME = 0x06
    const val MONOCHROME_YE = 0x07
    const val MONOCHROME_R = 0x08
    const val MONOCHROME_G = 0x09
    const val SEPIA = 0x0A
    const val CLASSIC_CHROME = 0x0B
    const val ACROS = 0x0C
    const val ACROS_YE = 0x0D
    const val ACROS_R = 0x0E
    const val ACROS_G = 0x0F
    const val ETERNA = 0x10
    const val CLASSIC_NEG = 0x11
    const val ETERNA_BLEACH = 0x12
    const val NOSTALGIC_NEG = 0x13
    const val REALA_ACE = 0x14

    val LABELS: Map<Int, String> = mapOf(
        PROVIA to "Provia (Standard)",
        VELVIA to "Velvia (Vivid)",
        ASTIA to "Astia (Soft)",
        PRO_NEG_HI to "PRO Neg. Hi",
        PRO_NEG_STD to "PRO Neg. Std",
        MONOCHROME to "Monochrome",
        MONOCHROME_YE to "Monochrome + Yellow",
        MONOCHROME_R to "Monochrome + Red",
        MONOCHROME_G to "Monochrome + Green",
        SEPIA to "Sepia",
        CLASSIC_CHROME to "Classic Chrome",
        ACROS to "Acros",
        ACROS_YE to "Acros + Yellow",
        ACROS_R to "Acros + Red",
        ACROS_G to "Acros + Green",
        ETERNA to "Eterna (Cinema)",
        CLASSIC_NEG to "Classic Neg.",
        ETERNA_BLEACH to "Eterna Bleach Bypass",
        NOSTALGIC_NEG to "Nostalgic Neg.",
        REALA_ACE to "Reala Ace",
    )

    /** B&W simulations — Color is not applicable, MonoWC/MonoMG are. */
    val MONOCHROME_SIMS: Set<Int> = setOf(
        MONOCHROME, MONOCHROME_YE, MONOCHROME_R, MONOCHROME_G,
        SEPIA, ACROS, ACROS_YE, ACROS_R, ACROS_G,
    )

    fun isMono(sim: Int) = sim in MONOCHROME_SIMS
    fun label(sim: Int) = LABELS[sim] ?: "Unknown (0x%02X)".format(sim)
}

object WhiteBalance {
    const val AS_SHOT = 0x0000
    const val AUTO = 0x0002
    const val DAYLIGHT = 0x0004
    const val INCANDESCENT = 0x0006
    const val UNDERWATER = 0x0008
    const val FLUORESCENT_1 = 0x8001
    const val FLUORESCENT_2 = 0x8002
    const val FLUORESCENT_3 = 0x8003
    const val SHADE = 0x8006
    const val COLOR_TEMP = 0x8007
    const val AMBIENCE_PRIORITY = 0x8021

    val LABELS: Map<Int, String> = mapOf(
        AS_SHOT to "As Shot",
        AUTO to "Auto",
        DAYLIGHT to "Daylight",
        INCANDESCENT to "Incandescent",
        UNDERWATER to "Underwater",
        FLUORESCENT_1 to "Fluorescent 1",
        FLUORESCENT_2 to "Fluorescent 2",
        FLUORESCENT_3 to "Fluorescent 3",
        SHADE to "Shade",
        COLOR_TEMP to "Color Temperature",
        AMBIENCE_PRIORITY to "Ambience Priority",
    )

    fun label(wb: Int) = LABELS[wb] ?: "Unknown (0x%04X)".format(wb)
}

/** Dynamic range, stored in the bank as a raw percentage. */
object DynamicRange {
    const val DR100 = 100
    const val DR200 = 200
    const val DR400 = 400

    val VALUES = listOf(DR100, DR200, DR400)
    fun label(dr: Int) = "DR$dr%"
}

/**
 * Grain, as a flat enum in the bank properties (1..5) rather than the
 * strength/size pair the camera menu shows.
 */
enum class Grain(val raw: Int, val label: String) {
    OFF(1, "Off"),
    WEAK_SMALL(2, "Weak, Small"),
    STRONG_SMALL(3, "Strong, Small"),
    WEAK_LARGE(4, "Weak, Large"),
    STRONG_LARGE(5, "Strong, Large");

    companion object {
        fun fromRaw(raw: Int) = entries.firstOrNull { it.raw == raw } ?: OFF
    }
}

/** Off/Weak/Strong effects. Stored 1-indexed in bank properties. */
enum class Effect(val label: String) {
    OFF("Off"), WEAK("Weak"), STRONG("Strong");

    /** Bank encoding is 1-based: 1=Off, 2=Weak, 3=Strong. */
    val raw: Int get() = ordinal + 1

    companion object {
        fun fromRaw(raw: Int) = entries.getOrElse(raw - 1) { OFF }
    }
}
