package org.nemo.fujibanks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nemo.fujibanks.fuji.DynamicRange
import org.nemo.fujibanks.fuji.Effect
import org.nemo.fujibanks.fuji.FilmSim
import org.nemo.fujibanks.fuji.Grain
import org.nemo.fujibanks.fuji.WhiteBalance
import org.nemo.fujibanks.recipe.TextRecipeParser

/**
 * Recipes here are in the shape Fuji X Weekly publishes them, including the
 * lines a bank cannot store.
 */
class TextRecipeParserTest {

    @Test
    fun `parses a classic chrome recipe`() {
        val text = """
            Classic Chrome
            Dynamic Range: DR400
            Highlight: +1
            Shadow: +2
            Color: +4
            Noise Reduction: -4
            Sharpening: +2
            Grain Effect: Strong, Small
            Color Chrome Effect: Strong
            White Balance: Auto, +2 Red & -4 Blue
            ISO: up to ISO 6400
            Exposure Compensation: +1/3 to +1
        """.trimIndent()

        val r = TextRecipeParser.parse(text).recipe

        assertEquals(FilmSim.CLASSIC_CHROME, r.filmSimulation)
        assertEquals(DynamicRange.DR400, r.dynamicRange)
        assertEquals(1.0, r.highlightTone, 0.0001)
        assertEquals(2.0, r.shadowTone, 0.0001)
        assertEquals(4.0, r.color, 0.0001)
        assertEquals(-4, r.noiseReduction)
        assertEquals(2.0, r.sharpness, 0.0001)
        assertEquals(Grain.STRONG_SMALL, r.grain)
        assertEquals(Effect.STRONG, r.colorChrome)
        assertEquals(WhiteBalance.AUTO, r.whiteBalance)
        assertEquals(2, r.wbShiftR)
        assertEquals(-4, r.wbShiftB)
    }

    @Test
    fun `ISO and exposure are kept as notes rather than dropped`() {
        val text = """
            Velvia
            ISO: up to ISO 6400
            Exposure Compensation: +1/3 to +2/3
        """.trimIndent()

        val result = TextRecipeParser.parse(text)

        // Neither can be stored in a bank, so they must survive as text the
        // photographer can act on.
        assertEquals(2, result.ignored.size)
        assertTrue(result.recipe.notes.contains("ISO"))
        assertTrue(result.recipe.notes.contains("Exposure"))
    }

    @Test
    fun `reads a kelvin white balance`() {
        val r = TextRecipeParser.parse("White Balance: 5500K, -1 Red & +2 Blue").recipe
        assertEquals(WhiteBalance.COLOR_TEMP, r.whiteBalance)
        assertEquals(5500, r.wbColorTemp)
        assertEquals(-1, r.wbShiftR)
        assertEquals(2, r.wbShiftB)
    }

    @Test
    fun `color chrome fx blue does not get eaten by color chrome`() {
        val text = """
            Color Chrome Effect: Weak
            Color Chrome Effect Blue: Strong
        """.trimIndent()
        val r = TextRecipeParser.parse(text).recipe
        assertEquals(Effect.WEAK, r.colorChrome)
        assertEquals(Effect.STRONG, r.colorChromeFxBlue)
    }

    @Test
    fun `color does not get eaten by color chrome or color temp`() {
        val r = TextRecipeParser.parse("Color: +3").recipe
        assertEquals(3.0, r.color, 0.0001)
    }

    @Test
    fun `recognises film simulations by the names recipes actually use`() {
        val cases = mapOf(
            "Film Simulation: Classic Negative" to FilmSim.CLASSIC_NEG,
            "Film Simulation: Eterna Bleach Bypass" to FilmSim.ETERNA_BLEACH,
            "Film Simulation: Nostalgic Negative" to FilmSim.NOSTALGIC_NEG,
            "Film Simulation: Acros + Red" to FilmSim.ACROS_R,
            "Film Simulation: Monochrome + Yellow" to FilmSim.MONOCHROME_YE,
            "Film Simulation: Reala Ace" to FilmSim.REALA_ACE,
            "Film Sim: Velvia" to FilmSim.VELVIA,
            "Film Simulation: PRO Neg. Std" to FilmSim.PRO_NEG_STD,
        )
        for ((line, expected) in cases) {
            assertEquals(line, expected, TextRecipeParser.parse(line).recipe.filmSimulation)
        }
    }

    @Test
    fun `parses a monochrome recipe with warm cool and magenta green`() {
        val text = """
            Acros
            Monochromatic Color: WC +2
            Warm/Cool: +2
            Magenta/Green: -1
            Dynamic Range: DR200
            Grain Effect: Weak, Large
        """.trimIndent()

        val r = TextRecipeParser.parse(text).recipe

        assertEquals(FilmSim.ACROS, r.filmSimulation)
        assertEquals(2.0, r.monoWC, 0.0001)
        assertEquals(-1.0, r.monoMG, 0.0001)
        assertEquals(Grain.WEAK_LARGE, r.grain)
    }

    @Test
    fun `bare lines are matched when unlabelled`() {
        val text = """
            Velvia
            DR200
            Strong
        """.trimIndent()
        val result = TextRecipeParser.parse(text)
        assertEquals(FilmSim.VELVIA, result.recipe.filmSimulation)
        assertEquals(DynamicRange.DR200, result.recipe.dynamicRange)
    }

    @Test
    fun `unparsable lines are reported, not silently dropped`() {
        val text = """
            Kodachrome 64
            Film Simulation: Classic Chrome
            shot wide open in the afternoon
        """.trimIndent()

        val result = TextRecipeParser.parse(text)

        assertEquals(FilmSim.CLASSIC_CHROME, result.recipe.filmSimulation)
        assertTrue(result.unrecognized.any { it.contains("wide open") })
    }

    @Test
    fun `an unnamed recipe takes its title from the first unmatched line`() {
        val text = """
            Kodachrome 64
            Film Simulation: Classic Chrome
        """.trimIndent()
        assertEquals("Kodachrome 64", TextRecipeParser.parse(text).recipe.name)
    }

    @Test
    fun `an explicit name wins over the inferred one`() {
        val text = """
            Kodachrome 64
            Film Simulation: Classic Chrome
        """.trimIndent()
        assertEquals("Mine", TextRecipeParser.parse(text, name = "Mine").recipe.name)
    }

    @Test
    fun `recognised lines report which fields they filled`() {
        val result = TextRecipeParser.parse("White Balance: Auto, +2 Red & -4 Blue")
        val fields = result.recognized.single().fields
        assertTrue(TextRecipeParser.Field.WHITE_BALANCE in fields)
        assertTrue(TextRecipeParser.Field.WB_SHIFT in fields)
    }

    @Test
    fun `a label with an unparsable value is reported`() {
        val result = TextRecipeParser.parse("Highlight: somewhere around plus one")
        assertTrue(result.unrecognized.any { it.startsWith("Highlight") })
    }

    @Test
    fun `blank input yields defaults and no claims`() {
        val result = TextRecipeParser.parse("\n\n   \n")
        assertTrue(result.recognized.isEmpty())
        assertTrue(result.unrecognized.isEmpty())
        assertEquals(FilmSim.PROVIA, result.recipe.filmSimulation)
    }
}
