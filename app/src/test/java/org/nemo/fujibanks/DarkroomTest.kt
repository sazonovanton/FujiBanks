package org.nemo.fujibanks

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nemo.fujibanks.fuji.Darkroom
import org.nemo.fujibanks.fuji.Recipe
import org.nemo.fujibanks.fuji.WhiteBalance
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The D185 conversion profile patch.
 *
 * These cover what the patch is *known* to do: it touches the fields it was
 * asked for and nothing else, it leaves the whole white-balance block alone on
 * As Shot, and it gates colour temperature on the mode that allows it.
 *
 * They deliberately assert nothing about the *value* written for the
 * white-balance mode. Every bank on Color Temperature still renders green and
 * the meaning of index 12 is unknown — see `Darkroom.patch`. A test that
 * froze the current guess would only make the next fix look like a regression.
 */
class DarkroomTest {

    /** A profile shaped like the camera's: a count, then int32s at the end. */
    private fun profile(params: Int = 32, fill: Int = SENTINEL): ByteArray {
        val bytes = ByteArray(64 + params * 4)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0, params.toShort())
        for (i in 0 until params) buf.putInt(64 + i * 4, fill)
        return bytes
    }

    private fun field(profile: ByteArray, index: Int): Int = Darkroom.fields(profile)[index]

    /** Write one parameter into a profile, the way the camera would have. */
    private fun setField(profile: ByteArray, index: Int, value: Int) {
        val buf = ByteBuffer.wrap(profile).order(ByteOrder.LITTLE_ENDIAN)
        val params = buf.getShort(0).toInt() and 0xFFFF
        buf.putInt(profile.size - params * 4 + index * 4, value)
    }

    @Test
    fun `the white balance mode is never written into the profile`() {
        // Index 12 is not the mode. A profile read off the camera holds 0 there
        // for a frame shot on a bank set to Auto, while the shift fields hold
        // that bank's real values — so whatever [12] records, it is not this.
        // Writing 0x8007 into it turned every Color Temperature bank green.
        for (mode in listOf(WhiteBalance.AUTO, WhiteBalance.DAYLIGHT, WhiteBalance.COLOR_TEMP, WhiteBalance.SHADE)) {
            val out = Darkroom.patch(profile(), Recipe(whiteBalance = mode))
            assertEquals("mode 0x%04X must leave [12] alone".format(mode), SENTINEL, field(out, WB_MODE))
        }
    }

    @Test
    fun `a named mode still carries its shifts and its kelvin`() {
        val out = Darkroom.patch(
            profile(),
            Recipe(whiteBalance = WhiteBalance.COLOR_TEMP, wbColorTemp = 6600, wbShiftR = -1, wbShiftB = -3),
        )
        assertEquals(-1, field(out, WB_SHIFT_R))
        assertEquals(-3, field(out, WB_SHIFT_B))
        assertEquals(6600, field(out, WB_TEMP))
    }

    @Test
    fun `as shot leaves the whole white balance block alone`() {
        val out = Darkroom.patch(profile(), Recipe(whiteBalance = WhiteBalance.AS_SHOT, wbShiftR = 5))

        // Untouched means the RAF's own EXIF still wins; writing 0 would not be
        // the same thing, it would be an explicit "use EXIF" on top of a shift.
        assertEquals(SENTINEL, field(out, WB_MODE))
        assertEquals(SENTINEL, field(out, WB_SHIFT_R))
        assertEquals(SENTINEL, field(out, WB_TEMP))
    }

    @Test
    fun `colour temperature is only written on the mode that gates it`() {
        val out = Darkroom.patch(profile(), Recipe(whiteBalance = WhiteBalance.DAYLIGHT, wbColorTemp = 6600))
        assertEquals(SENTINEL, field(out, WB_TEMP))
    }

    @Test
    fun `shifts go through signed`() {
        val out = Darkroom.patch(profile(), Recipe(whiteBalance = WhiteBalance.COLOR_TEMP, wbShiftR = -1, wbShiftB = -3))
        assertEquals(-1, field(out, WB_SHIFT_R))
        assertEquals(-3, field(out, WB_SHIFT_B))
    }

    @Test
    fun `a patch changes nothing but the fields it was asked for`() {
        val base = profile()
        val out = Darkroom.patch(base, Recipe(whiteBalance = WhiteBalance.AS_SHOT))
        assertEquals(base.size, out.size)
        // The header and everything before the parameter block survive intact.
        assertEquals(
            base.copyOf(64).toList(),
            out.copyOf(64).toList(),
        )
    }

    @Test
    fun `dynamic range goes through as asked, even past what the frame can do`() {
        // Clamping this was tried and taken back out: converting at a DR the
        // recipe does not name is a substitution, and Develop explains the
        // mismatch instead of quietly resolving it. The wrong-looking frame is
        // the honest answer to a recipe the frame cannot support.
        val shotAt100 = profile().also { setField(it, DR, 100) }

        for (asked in listOf(100, 200, 400)) {
            val out = Darkroom.patch(shotAt100, Recipe(dynamicRange = asked))
            assertEquals(asked, field(out, DR))
        }
    }

    @Test
    fun `the ceiling is the frame's own dynamic range`() {
        // Not used to clamp — used by Develop to decide whether to warn.
        assertEquals(100, Darkroom.dynamicRangeCeiling(profile().also { setField(it, DR, 100) }))
        assertEquals(200, Darkroom.dynamicRangeCeiling(profile().also { setField(it, DR, 200) }))
        assertEquals(400, Darkroom.dynamicRangeCeiling(profile().also { setField(it, DR, 400) }))
    }

    @Test
    fun `an unreadable ceiling reports the top of the range, so nothing is warned about`() {
        val odd = profile().also { setField(it, DR, 12345) }
        assertEquals(400, Darkroom.dynamicRangeCeiling(odd))
    }

    private companion object {
        /** 0x8000 sign-extended: the camera's "not set" in a 32-bit field. */
        const val SENTINEL = -32768
        const val WB_MODE = 12
        const val WB_SHIFT_R = 13
        const val WB_SHIFT_B = 14
        const val WB_TEMP = 15
        const val DR = 6
    }
}
