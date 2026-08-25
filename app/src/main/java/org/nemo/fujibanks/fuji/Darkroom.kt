package org.nemo.fujibanks.fuji

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The camera's own RAW conversion profile, property 0xD185.
 *
 * The camera returns a binary block whose first two bytes give a parameter
 * count; the parameters themselves are int32s at the end of the block. Patching
 * means copying the block and overwriting only the fields that were asked for —
 * fields left alone keep their sentinel, and the camera then falls back to what
 * the RAF's own EXIF says. Building a profile from scratch instead produces a
 * visible shift away from the in-camera rendering.
 *
 * The field indices come from FilmKit's captures of another body, and index 12
 * was never among them — see [patch]. The encoding matches the bank properties:
 * 1-indexed effects, flat grain enum, raw dynamic-range percentage.
 */
object Darkroom {

    private object Idx {
        const val EXPOSURE_BIAS = 4
        const val DYNAMIC_RANGE = 6      // raw percentage
        const val WIDE_D_RANGE = 7
        const val FILM_SIMULATION = 8
        const val GRAIN_EFFECT = 9       // flat enum 1..5
        const val COLOR_CHROME = 10      // 1-indexed
        const val SMOOTH_SKIN = 11       // 1-indexed
        /** Never written — see [patch]. Not in FilmKit's field list, and a
         *  profile off the camera holds 0 here for a frame that certainly
         *  had a white balance. Kept only so [recipeFromProfile] can show it. */
        const val WHITE_BALANCE = 12
        const val WB_SHIFT_R = 13
        const val WB_SHIFT_B = 14
        const val WB_COLOR_TEMP = 15
        const val HIGHLIGHT_TONE = 16    // x10
        const val SHADOW_TONE = 17       // x10
        const val COLOR = 18             // x10
        const val SHARPNESS = 19         // x10
        const val NOISE_REDUCTION = 20   // proprietary lookup
        const val CC_FX_BLUE = 25        // 1-indexed
        const val CLARITY = 27           // x10
    }

    /**
     * Overlay a recipe onto the camera's base profile.
     *
     * [exposureBias] is in millistops, matching the profile's own units, and is
     * separate from the recipe because a bank cannot store it — but a
     * conversion can apply it.
     */
    fun patch(base: ByteArray, recipe: Recipe, exposureBias: Double = 0.0): ByteArray {
        require(base.size >= 4) { "Profile too short: ${base.size} bytes" }

        val patched = base.copyOf()
        val buf = ByteBuffer.wrap(patched).order(ByteOrder.LITTLE_ENDIAN)
        val numParams = buf.getShort(0).toInt() and 0xFFFF
        val offset = patched.size - numParams * 4
        require(offset >= 0) { "Profile claims $numParams parameters but is only ${patched.size} bytes" }

        fun set(index: Int, value: Int) {
            val at = offset + index * 4
            if (at + 4 <= patched.size) buf.putInt(at, value)
        }

        set(Idx.FILM_SIMULATION, recipe.filmSimulation)

        // Dynamic range goes through exactly as asked, even when the frame
        // cannot support it.
        //
        // DR on these bodies is not post-processing: the camera underexposes at
        // capture and lifts the shadows back, so DR200 needs a stop of headroom
        // in the exposure and DR400 needs two. Ask a frame for more than it has
        // and the camera does not refuse — it returns a JPEG with red and blue
        // crushed, a violently green photograph. Five of the seven banks on this
        // body are on DR400, and every one of them did it.
        //
        // Clamping it here was tried and taken back out. Quietly converting at
        // a different DR than the recipe says is a substitution, and this screen
        // exists to show what a recipe does — including when what it does is
        // wrong for this frame. Develop reads [dynamicRangeCeiling] and explains
        // the mismatch instead; the choice stays with the person looking at it.
        set(Idx.DYNAMIC_RANGE, recipe.dynamicRange)

        set(Idx.GRAIN_EFFECT, recipe.grain.raw)
        set(Idx.COLOR_CHROME, recipe.colorChrome.raw)
        set(Idx.CC_FX_BLUE, recipe.colorChromeFxBlue.raw)
        set(Idx.SMOOTH_SKIN, recipe.smoothSkin.raw)

        set(Idx.HIGHLIGHT_TONE, Codec.encodeTone(recipe.highlightTone))
        set(Idx.SHADOW_TONE, Codec.encodeTone(recipe.shadowTone))
        set(Idx.SHARPNESS, Codec.encodeTone(recipe.sharpness))
        set(Idx.CLARITY, Codec.encodeTone(recipe.clarity))
        set(Idx.NOISE_REDUCTION, Codec.encodeNoiseReduction(recipe.noiseReduction))

        // Colour is meaningless on a monochrome simulation; leaving the field
        // untouched lets the camera keep its own sentinel there.
        if (!recipe.isMono) set(Idx.COLOR, Codec.encodeTone(recipe.color))

        // White balance.
        //
        // The mode is deliberately NOT written. Index 12 was this app's own
        // guess — FilmKit's field list goes [11] then [13], skipping it — and a
        // profile read back off the camera settles it: a frame shot on C7, which
        // is on Auto (D199 = 2), carries 0 at [12] while [13] and [14] hold C7's
        // actual shifts of +1 and -3. A real exposure always had *some* white
        // balance, so a field sitting at 0 is not recording the mode, and
        // writing the D199 code there was putting an unknown number into an
        // unknown field. Leaving it alone is not a fix for anything: the green
        // renders survive it, and survive As Shot too, where no white-balance
        // field is written at all. Whatever crushes red and blue is elsewhere.
        //
        // Ruled out by the same dump: sign extension. The camera zero-extends
        // its 16-bit values into these 32-bit fields — [20] holds 0x00008000
        // where D1A1 reads -32768 — so 0x00008007 was never the wrong shape.
        //
        // What is written is what FilmKit writes and what the camera's own
        // profile demonstrably holds: the shifts, and the colour temperature
        // when the recipe is on Color Temperature. The cost is that a recipe
        // naming Daylight or Shade no longer forces that mode during a
        // conversion; it inherits the frame's own. Restoring it needs the
        // profile's own WB enumeration, which needs two RAFs shot at known
        // different modes, dumped and compared.
        if (recipe.whiteBalance != WhiteBalance.AS_SHOT) {
            set(Idx.WB_SHIFT_R, recipe.wbShiftR)
            set(Idx.WB_SHIFT_B, recipe.wbShiftB)
            if (recipe.whiteBalance == WhiteBalance.COLOR_TEMP) {
                set(Idx.WB_COLOR_TEMP, recipe.wbColorTemp)
            }
        }

        if (exposureBias != 0.0) {
            set(Idx.EXPOSURE_BIAS, Math.round(exposureBias * 1000).toInt())
        }

        return patched
    }

    /** Read a recipe back out of a profile, for showing what a RAF came in with. */
    fun recipeFromProfile(profile: ByteArray, name: String = ""): Recipe {
        val buf = ByteBuffer.wrap(profile).order(ByteOrder.LITTLE_ENDIAN)
        val numParams = buf.getShort(0).toInt() and 0xFFFF
        val offset = profile.size - numParams * 4

        fun get(index: Int): Int {
            val at = offset + index * 4
            return if (at + 4 <= profile.size) buf.getInt(at) else 0
        }

        val sim = get(Idx.FILM_SIMULATION)
        val mono = FilmSim.isMono(sim)
        return Recipe(
            name = name,
            filmSimulation = if (sim in FilmSim.LABELS) sim else FilmSim.PROVIA,
            dynamicRange = get(Idx.DYNAMIC_RANGE).takeIf { it in DynamicRange.VALUES }
                ?: DynamicRange.DR100,
            grain = Grain.fromRaw(get(Idx.GRAIN_EFFECT)),
            colorChrome = Effect.fromRaw(get(Idx.COLOR_CHROME)),
            colorChromeFxBlue = Effect.fromRaw(get(Idx.CC_FX_BLUE)),
            smoothSkin = Effect.fromRaw(get(Idx.SMOOTH_SKIN)),
            whiteBalance = Codec.decodeWhiteBalance(get(Idx.WHITE_BALANCE)),
            wbShiftR = get(Idx.WB_SHIFT_R),
            wbShiftB = get(Idx.WB_SHIFT_B),
            wbColorTemp = get(Idx.WB_COLOR_TEMP).takeIf { it > 0 } ?: 6500,
            highlightTone = Codec.decodeTone(get(Idx.HIGHLIGHT_TONE)),
            shadowTone = Codec.decodeTone(get(Idx.SHADOW_TONE)),
            color = if (mono) 0.0 else Codec.decodeTone(get(Idx.COLOR)),
            sharpness = Codec.decodeTone(get(Idx.SHARPNESS)),
            clarity = Codec.decodeTone(get(Idx.CLARITY)),
            noiseReduction = Codec.decodeNoiseReduction(get(Idx.NOISE_REDUCTION)),
        )
    }

    /**
     * The highest dynamic range this frame can be converted at.
     *
     * A profile whose DR field holds something outside the three known values
     * is not trusted to cap anything — better to convert as asked than to clamp
     * on a misread byte.
     */
    fun dynamicRangeCeiling(base: ByteArray): Int {
        val value = fields(base).getOrNull(Idx.DYNAMIC_RANGE) ?: return DynamicRange.DR400
        return if (value in DynamicRange.VALUES) value else DynamicRange.DR400
    }

    /**
     * Every parameter in a profile, by index, as the camera itself set it.
     *
     * The indices above came from FilmKit, and FilmKit does not document index
     * 12 at all — it writes the shifts and the colour temperature and never the
     * white-balance mode. A field this app writes but nothing has confirmed is
     * exactly the sort of thing worth being able to check, so there has to be a
     * way to read the block back and see what the body actually puts there.
     */
    fun fields(profile: ByteArray): List<Int> {
        if (profile.size < 4) return emptyList()
        val buf = ByteBuffer.wrap(profile).order(ByteOrder.LITTLE_ENDIAN)
        val numParams = buf.getShort(0).toInt() and 0xFFFF
        val offset = profile.size - numParams * 4
        if (offset < 0) return emptyList()
        return List(numParams) { i ->
            val at = offset + i * 4
            if (at + 4 <= profile.size) buf.getInt(at) else 0
        }
    }

    /** Names for the indices this app claims to know, for the dump. */
    val FIELD_NAMES: Map<Int, String> = mapOf(
        Idx.EXPOSURE_BIAS to "ExposureBias(millistops)",
        Idx.DYNAMIC_RANGE to "DynamicRange%",
        Idx.WIDE_D_RANGE to "WideDRange",
        Idx.FILM_SIMULATION to "FilmSimulation",
        Idx.GRAIN_EFFECT to "GrainEffect",
        Idx.COLOR_CHROME to "ColorChrome",
        Idx.SMOOTH_SKIN to "SmoothSkin",
        Idx.WHITE_BALANCE to "WhiteBalance (UNCONFIRMED)",
        Idx.WB_SHIFT_R to "WBShiftR",
        Idx.WB_SHIFT_B to "WBShiftB",
        Idx.WB_COLOR_TEMP to "ColorTemp(K)",
        Idx.HIGHLIGHT_TONE to "HighlightTone x10",
        Idx.SHADOW_TONE to "ShadowTone x10",
        Idx.COLOR to "Color x10",
        Idx.SHARPNESS to "Sharpness x10",
        Idx.NOISE_REDUCTION to "HighIsoNR",
        Idx.CC_FX_BLUE to "ColorChromeFxBlue",
        Idx.CLARITY to "Clarity x10",
    )

    /** ObjectInfo for a RAF upload. The format code must be exactly 0xF802. */
    fun rafObjectInfo(size: Int): ByteArray {
        val filename = org.nemo.fujibanks.usb.packPtpString(RAF_UPLOAD_NAME)
        val buf = ByteBuffer.allocate(52 + filename.size + 3).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)                 // StorageID
        buf.putShort(0xF802.toShort())// ObjectFormat — anything else fails silently
        buf.putShort(0)               // ProtectionStatus
        buf.putInt(size)              // CompressedSize
        buf.putShort(0)               // ThumbFormat
        buf.putInt(0)                 // ThumbCompressedSize
        buf.putInt(0)                 // ThumbPixWidth
        buf.putInt(0)                 // ThumbPixHeight
        buf.putInt(0)                 // ImagePixWidth
        buf.putInt(0)                 // ImagePixHeight
        buf.putInt(0)                 // ImageBitDepth
        buf.putInt(0)                 // ParentObject
        buf.putShort(0)               // AssociationType
        buf.putInt(0)                 // AssociationDesc
        buf.putInt(0)                 // SequenceNumber
        buf.put(filename)
        buf.put(0)                    // CaptureDate
        buf.put(0)                    // ModificationDate
        buf.put(0)                    // Keywords
        return buf.array().copyOf(buf.position())
    }

    const val RAF_UPLOAD_NAME = "FUP_FILE.dat"
}
