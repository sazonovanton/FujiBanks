package org.nemo.fujibanks.fuji

import org.nemo.fujibanks.usb.packI16
import org.nemo.fujibanks.usb.packU16
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Translation between menu values and the bank wire encoding.
 *
 * The encodings are not uniform: tone parameters are multiplied by ten, effects
 * are 1-indexed, grain is a flat enum, and High ISO NR uses a lookup table with
 * no arithmetic relationship to the value it represents. Every quirk here was
 * observed on a camera, not derived.
 */
object Codec {

    /** Sentinel the camera returns for "not set"; decodes to zero. */
    private const val TONE_SENTINEL = 0x8000

    // -- Tone parameters (x10) ---------------------------------------------

    fun decodeTone(raw: Int): Double {
        if (raw == TONE_SENTINEL || raw == -32768) return 0.0
        return raw / 10.0
    }

    fun encodeTone(value: Double): Int = Math.round(value * 10).toInt()

    // -- High ISO NR -------------------------------------------------------

    /**
     * High ISO NR is a lookup, not a scale. Note that -2 maps to 0x4000 while
     * +2 maps to 0x0000: the ordering is genuinely non-monotonic.
     */
    val NR_ENCODE: Map<Int, Int> = mapOf(
        -4 to 0x8000, -3 to 0x7000, -2 to 0x4000, -1 to 0x3000,
        0 to 0x2000, 1 to 0x1000, 2 to 0x0000, 3 to 0x6000, 4 to 0x5000,
    )

    private val NR_DECODE: Map<Int, Int> = NR_ENCODE.entries.associate { (k, v) -> v to k }

    fun decodeNoiseReduction(raw: Int): Int = NR_DECODE[raw and 0xFFFF] ?: 0

    fun encodeNoiseReduction(value: Int): Int = NR_ENCODE[value.coerceIn(-4, 4)] ?: 0x2000

    // -- White balance -----------------------------------------------------

    /** WB comes back as a signed 16-bit read; the mode ids are unsigned. */
    fun decodeWhiteBalance(raw: Int): Int = raw and 0xFFFF

    // -- Raw property bytes ------------------------------------------------

    /** Decode a 1/2/4-byte property payload as a signed integer. */
    fun decodeInt(bytes: ByteArray): Int {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (bytes.size) {
            1 -> bytes[0].toInt()
            2 -> buf.short.toInt()
            4 -> buf.int
            else -> 0
        }
    }

    // -- Bank properties -> Recipe ----------------------------------------

    /**
     * Build a recipe from the raw D18E-D1A5 values of one bank.
     * Missing properties fall back to the recipe defaults.
     */
    fun recipeFromProps(name: String, props: Map<Int, Int>): Recipe {
        val filmSim = props[FujiProps.FILM_SIMULATION] ?: FilmSim.PROVIA
        val mono = FilmSim.isMono(filmSim)
        return Recipe(
            name = name,
            filmSimulation = filmSim,
            dynamicRange = props[FujiProps.DYNAMIC_RANGE]?.takeIf { it in DynamicRange.VALUES }
                ?: DynamicRange.DR100,
            grain = Grain.fromRaw(props[FujiProps.GRAIN_EFFECT] ?: Grain.OFF.raw),
            colorChrome = Effect.fromRaw(props[FujiProps.COLOR_CHROME] ?: Effect.OFF.raw),
            colorChromeFxBlue = Effect.fromRaw(props[FujiProps.COLOR_CHROME_FX_BLUE] ?: Effect.OFF.raw),
            smoothSkin = Effect.fromRaw(props[FujiProps.SMOOTH_SKIN] ?: Effect.OFF.raw),
            whiteBalance = decodeWhiteBalance(props[FujiProps.WHITE_BALANCE] ?: WhiteBalance.AUTO),
            wbShiftR = props[FujiProps.WB_SHIFT_R] ?: 0,
            wbShiftB = props[FujiProps.WB_SHIFT_B] ?: 0,
            wbColorTemp = props[FujiProps.WB_COLOR_TEMP]?.takeIf { it > 0 } ?: 6500,
            highlightTone = decodeTone(props[FujiProps.HIGHLIGHT_TONE] ?: 0),
            shadowTone = decodeTone(props[FujiProps.SHADOW_TONE] ?: 0),
            color = if (mono) 0.0 else decodeTone(props[FujiProps.COLOR] ?: 0),
            sharpness = decodeTone(props[FujiProps.SHARPNESS] ?: 0),
            clarity = decodeTone(props[FujiProps.CLARITY] ?: 0),
            noiseReduction = decodeNoiseReduction(props[FujiProps.HIGH_ISO_NR] ?: 0x2000),
            monoWC = if (mono) decodeTone(props[FujiProps.MONO_WC] ?: 0) else 0.0,
            monoMG = if (mono) decodeTone(props[FujiProps.MONO_MG] ?: 0) else 0.0,
        )
    }

    // -- Recipe -> bank properties ----------------------------------------

    /** One property write: which id, what bytes. */
    data class PropWrite(val propId: Int, val bytes: ByteArray) {
        override fun equals(other: Any?) =
            other is PropWrite && other.propId == propId && other.bytes.contentEquals(bytes)
        override fun hashCode() = propId * 31 + bytes.contentHashCode()
    }

    /**
     * Turn a recipe into the ordered property writes for one bank.
     *
     * Properties the camera would reject are left out entirely rather than
     * written and ignored: Color on a B&W simulation, MonoWC/MonoMG on a colour
     * one, ColorTemp when the WB mode is not Color Temperature. Writing them
     * anyway produces a rejection the caller cannot distinguish from a real
     * failure.
     *
     * [base] supplies bytes for properties we do not model, so a write preserves
     * whatever the bank already held instead of stamping a default over it.
     */
    fun propsFromRecipe(recipe: Recipe, base: Map<Int, ByteArray> = emptyMap()): List<PropWrite> {
        val mono = recipe.isMono
        val computed = buildMap<Int, ByteArray> {
            put(FujiProps.DYNAMIC_RANGE, packU16(recipe.dynamicRange))
            put(FujiProps.FILM_SIMULATION, packU16(recipe.filmSimulation))
            put(FujiProps.GRAIN_EFFECT, packU16(recipe.grain.raw))
            put(FujiProps.COLOR_CHROME, packU16(recipe.colorChrome.raw))
            put(FujiProps.COLOR_CHROME_FX_BLUE, packU16(recipe.colorChromeFxBlue.raw))
            put(FujiProps.SMOOTH_SKIN, packU16(recipe.smoothSkin.raw))
            put(FujiProps.WHITE_BALANCE, packU16(recipe.whiteBalance))
            put(FujiProps.WB_SHIFT_R, packI16(recipe.wbShiftR))
            put(FujiProps.WB_SHIFT_B, packI16(recipe.wbShiftB))
            put(FujiProps.HIGHLIGHT_TONE, packI16(encodeTone(recipe.highlightTone)))
            put(FujiProps.SHADOW_TONE, packI16(encodeTone(recipe.shadowTone)))
            put(FujiProps.SHARPNESS, packI16(encodeTone(recipe.sharpness)))
            put(FujiProps.CLARITY, packI16(encodeTone(recipe.clarity)))
            put(FujiProps.HIGH_ISO_NR, packU16(encodeNoiseReduction(recipe.noiseReduction)))

            if (recipe.whiteBalance == WhiteBalance.COLOR_TEMP && recipe.wbColorTemp > 0) {
                put(FujiProps.WB_COLOR_TEMP, packU16(recipe.wbColorTemp))
            }
            if (!mono) {
                put(FujiProps.COLOR, packI16(encodeTone(recipe.color)))
            }
            // The camera rejects a zero write to these, so an unset value means
            // "leave the bank alone" rather than "set to zero".
            if (mono && recipe.monoWC != 0.0) {
                put(FujiProps.MONO_WC, packI16(encodeTone(recipe.monoWC)))
            }
            if (mono && recipe.monoMG != 0.0) {
                put(FujiProps.MONO_MG, packI16(encodeTone(recipe.monoMG)))
            }
        }

        // Properties the camera would reject for this recipe. They must not fall
        // back to the baseline either — a rejected write is indistinguishable
        // from a real failure, so we never attempt one.
        val skip = buildSet {
            if (mono) add(FujiProps.COLOR) else {
                add(FujiProps.MONO_WC)
                add(FujiProps.MONO_MG)
            }
            if (recipe.whiteBalance != WhiteBalance.COLOR_TEMP) add(FujiProps.WB_COLOR_TEMP)
            if (mono && recipe.monoWC == 0.0) add(FujiProps.MONO_WC)
            if (mono && recipe.monoMG == 0.0) add(FujiProps.MONO_MG)
        }

        return FujiProps.WRITE_ORDER.mapNotNull { propId ->
            if (propId in skip) return@mapNotNull null
            val bytes = computed[propId]
                ?: base[propId]
                ?: FujiProps.UNKNOWN_DEFAULTS[propId]?.let { packU16(it) }
            // Nothing computed, nothing in the bank, no observed default: skip
            // rather than guess a value onto the camera.
            if (bytes == null) null else PropWrite(propId, bytes)
        }
    }
}
