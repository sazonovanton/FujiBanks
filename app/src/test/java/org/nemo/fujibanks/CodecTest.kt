package org.nemo.fujibanks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nemo.fujibanks.fuji.Codec
import org.nemo.fujibanks.fuji.DynamicRange
import org.nemo.fujibanks.fuji.Effect
import org.nemo.fujibanks.fuji.FilmSim
import org.nemo.fujibanks.fuji.FujiProps
import org.nemo.fujibanks.fuji.Grain
import org.nemo.fujibanks.fuji.Recipe
import org.nemo.fujibanks.fuji.WhiteBalance
import org.nemo.fujibanks.usb.packI16
import org.nemo.fujibanks.usb.packU16

class CodecTest {

    // -- Tone x10 ----------------------------------------------------------

    @Test
    fun `tone round trips through x10 encoding`() {
        for (tenths in -80..80) {
            val value = tenths / 10.0
            assertEquals(value, Codec.decodeTone(Codec.encodeTone(value)), 0.0001)
        }
    }

    @Test
    fun `tone encodes half steps`() {
        assertEquals(15, Codec.encodeTone(1.5))
        assertEquals(-25, Codec.encodeTone(-2.5))
        assertEquals(1.5, Codec.decodeTone(15), 0.0001)
    }

    @Test
    fun `tone sentinel decodes to zero`() {
        // The camera reports 0x8000 for "not set"; both the unsigned and the
        // sign-extended read of it must land on zero.
        assertEquals(0.0, Codec.decodeTone(0x8000), 0.0001)
        assertEquals(0.0, Codec.decodeTone(-32768), 0.0001)
    }

    // -- High ISO NR -------------------------------------------------------

    @Test
    fun `noise reduction round trips across its whole range`() {
        for (nr in -4..4) {
            assertEquals(nr, Codec.decodeNoiseReduction(Codec.encodeNoiseReduction(nr)))
        }
    }

    @Test
    fun `noise reduction encoding is the documented lookup, not a scale`() {
        // Nothing arithmetic connects these; they are observed values.
        assertEquals(0x8000, Codec.encodeNoiseReduction(-4))
        assertEquals(0x2000, Codec.encodeNoiseReduction(0))
        assertEquals(0x5000, Codec.encodeNoiseReduction(4))
        // Non-monotonic: -2 is higher than +2.
        assertEquals(0x4000, Codec.encodeNoiseReduction(-2))
        assertEquals(0x0000, Codec.encodeNoiseReduction(2))
    }

    @Test
    fun `noise reduction decodes a sign-extended read`() {
        // 0x8000 arrives as -32768 from a signed 16-bit read.
        assertEquals(-4, Codec.decodeNoiseReduction(-32768))
    }

    @Test
    fun `noise reduction clamps out-of-range input`() {
        assertEquals(0x8000, Codec.encodeNoiseReduction(-99))
        assertEquals(0x5000, Codec.encodeNoiseReduction(99))
    }

    // -- White balance -----------------------------------------------------

    @Test
    fun `white balance masks a signed read back to its mode id`() {
        // 0x8007 (Color Temp) reads as -32761 over the wire.
        assertEquals(WhiteBalance.COLOR_TEMP, Codec.decodeWhiteBalance(-32761))
        assertEquals(WhiteBalance.AUTO, Codec.decodeWhiteBalance(0x0002))
    }

    // -- Bank properties -> recipe ----------------------------------------

    @Test
    fun `recipe decodes from bank properties`() {
        val props = mapOf(
            FujiProps.FILM_SIMULATION to FilmSim.CLASSIC_CHROME,
            FujiProps.DYNAMIC_RANGE to 400,
            FujiProps.GRAIN_EFFECT to Grain.STRONG_SMALL.raw,
            FujiProps.COLOR_CHROME to Effect.STRONG.raw,
            FujiProps.SMOOTH_SKIN to Effect.OFF.raw,
            FujiProps.WHITE_BALANCE to WhiteBalance.AUTO,
            FujiProps.WB_SHIFT_R to 2,
            FujiProps.WB_SHIFT_B to -4,
            FujiProps.HIGHLIGHT_TONE to 10,
            FujiProps.SHADOW_TONE to 20,
            FujiProps.COLOR to 40,
            FujiProps.SHARPNESS to 20,
            FujiProps.HIGH_ISO_NR to 0x8000,
            FujiProps.CLARITY to 0,
        )
        val r = Codec.recipeFromProps("Kodachrome", props)

        assertEquals("Kodachrome", r.name)
        assertEquals(FilmSim.CLASSIC_CHROME, r.filmSimulation)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(Grain.STRONG_SMALL, r.grain)
        assertEquals(Effect.STRONG, r.colorChrome)
        assertEquals(2, r.wbShiftR)
        assertEquals(-4, r.wbShiftB)
        assertEquals(1.0, r.highlightTone, 0.0001)
        assertEquals(2.0, r.shadowTone, 0.0001)
        assertEquals(4.0, r.color, 0.0001)
        assertEquals(-4, r.noiseReduction)
    }

    @Test
    fun `monochrome recipe drops colour and keeps mono shifts`() {
        val props = mapOf(
            FujiProps.FILM_SIMULATION to FilmSim.ACROS_R,
            FujiProps.COLOR to 40,      // meaningless on a B&W simulation
            FujiProps.MONO_WC to 15,
            FujiProps.MONO_MG to -20,
        )
        val r = Codec.recipeFromProps("Acros", props)

        assertTrue(r.isMono)
        assertEquals(0.0, r.color, 0.0001)
        assertEquals(1.5, r.monoWC, 0.0001)
        assertEquals(-2.0, r.monoMG, 0.0001)
    }

    // -- Recipe -> bank properties ----------------------------------------

    private fun List<Codec.PropWrite>.byId(propId: Int) =
        firstOrNull { it.propId == propId }

    @Test
    fun `colour recipe writes Color and skips the mono shifts`() {
        val writes = Codec.propsFromRecipe(
            Recipe(filmSimulation = FilmSim.VELVIA, color = 2.0, monoWC = 1.0)
        )
        assertTrue(writes.byId(FujiProps.COLOR)!!.bytes.contentEquals(packI16(20)))
        assertNull(writes.byId(FujiProps.MONO_WC))
        assertNull(writes.byId(FujiProps.MONO_MG))
    }

    @Test
    fun `monochrome recipe skips Color entirely`() {
        // The camera rejects a Color write on a B&W simulation, and a rejection
        // is indistinguishable from a real failure — so it must never be sent.
        val writes = Codec.propsFromRecipe(
            Recipe(filmSimulation = FilmSim.ACROS, color = 3.0, monoWC = 1.5)
        )
        assertNull(writes.byId(FujiProps.COLOR))
        assertTrue(writes.byId(FujiProps.MONO_WC)!!.bytes.contentEquals(packI16(15)))
    }

    @Test
    fun `monochrome recipe omits a zero mono shift`() {
        // Writing zero to D193 D194 is refused by the camera.
        val writes = Codec.propsFromRecipe(
            Recipe(filmSimulation = FilmSim.ACROS, monoWC = 0.0, monoMG = 0.0)
        )
        assertNull(writes.byId(FujiProps.MONO_WC))
        assertNull(writes.byId(FujiProps.MONO_MG))
    }

    @Test
    fun `colour temperature is written only in Color Temp mode`() {
        val auto = Codec.propsFromRecipe(
            Recipe(whiteBalance = WhiteBalance.AUTO, wbColorTemp = 5500)
        )
        assertNull(auto.byId(FujiProps.WB_COLOR_TEMP))

        val kelvin = Codec.propsFromRecipe(
            Recipe(whiteBalance = WhiteBalance.COLOR_TEMP, wbColorTemp = 5500)
        )
        assertTrue(kelvin.byId(FujiProps.WB_COLOR_TEMP)!!.bytes.contentEquals(packU16(5500)))
    }

    @Test
    fun `a skipped property is not resurrected from the baseline`() {
        // The bank already holds a Color value; a B&W recipe must still not
        // write one, rather than falling back to what was there.
        val base = mapOf(FujiProps.COLOR to packI16(40))
        val writes = Codec.propsFromRecipe(Recipe(filmSimulation = FilmSim.ACROS), base)
        assertNull(writes.byId(FujiProps.COLOR))
    }

    @Test
    fun `unmodelled properties are preserved from the baseline`() {
        val base = mapOf(FujiProps.IMAGE_SIZE to packU16(3))
        val writes = Codec.propsFromRecipe(Recipe(), base)
        assertTrue(writes.byId(FujiProps.IMAGE_SIZE)!!.bytes.contentEquals(packU16(3)))
    }

    @Test
    fun `writes follow the order the camera requires`() {
        val writes = Codec.propsFromRecipe(
            Recipe(whiteBalance = WhiteBalance.COLOR_TEMP, wbColorTemp = 5500)
        )
        val ids = writes.map { it.propId }
        // Film simulation must precede the parameters it gates.
        assertTrue(ids.indexOf(FujiProps.FILM_SIMULATION) < ids.indexOf(FujiProps.COLOR))
        // WB mode must precede the colour temperature.
        assertTrue(ids.indexOf(FujiProps.WHITE_BALANCE) < ids.indexOf(FujiProps.WB_COLOR_TEMP))
    }

    @Test
    fun `bank round trips from recipe to properties and back`() {
        // The strongest guarantee available without a camera: encode a recipe,
        // decode what would land in the bank, and require the same recipe back.
        val original = Recipe(
            name = "Round Trip",
            filmSimulation = FilmSim.CLASSIC_NEG,
            dynamicRange = DynamicRange.DR400,
            grain = Grain.WEAK_LARGE,
            colorChrome = Effect.WEAK,
            colorChromeFxBlue = Effect.STRONG,
            smoothSkin = Effect.OFF,
            whiteBalance = WhiteBalance.COLOR_TEMP,
            wbShiftR = 3,
            wbShiftB = -5,
            wbColorTemp = 5500,
            highlightTone = -1.5,
            shadowTone = 2.0,
            color = 4.0,
            sharpness = -2.0,
            clarity = 3.0,
            noiseReduction = -3,
        )

        val decoded = Codec.recipeFromProps(
            original.name,
            Codec.propsFromRecipe(original).associate { it.propId to Codec.decodeInt(it.bytes) },
        )

        assertEquals(original, decoded)
    }

    @Test
    fun `monochrome bank round trips`() {
        val original = Recipe(
            name = "Acros Push",
            filmSimulation = FilmSim.ACROS_R,
            dynamicRange = DynamicRange.DR200,
            grain = Grain.STRONG_LARGE,
            whiteBalance = WhiteBalance.DAYLIGHT,
            highlightTone = 1.0,
            shadowTone = 2.5,
            sharpness = 1.0,
            noiseReduction = 4,
            monoWC = 2.0,
            monoMG = -1.0,
        )

        val decoded = Codec.recipeFromProps(
            original.name,
            Codec.propsFromRecipe(original).associate { it.propId to Codec.decodeInt(it.bytes) },
        )

        assertEquals(original, decoded)
        assertFalse(decoded.color != 0.0)
    }
}
