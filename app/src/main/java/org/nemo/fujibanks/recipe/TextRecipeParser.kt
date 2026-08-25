package org.nemo.fujibanks.recipe

import org.nemo.fujibanks.fuji.DynamicRange
import org.nemo.fujibanks.fuji.Effect
import org.nemo.fujibanks.fuji.FilmSim
import org.nemo.fujibanks.fuji.Grain
import org.nemo.fujibanks.fuji.Recipe
import org.nemo.fujibanks.fuji.WhiteBalance

/**
 * Parser for recipes written as text, as they appear on Fuji X Weekly.
 *
 * A typical block looks like:
 *
 *     Classic Chrome
 *     Dynamic Range: DR400
 *     Highlight: +1
 *     Shadow: +2
 *     Color: +4
 *     Noise Reduction: -4
 *     Sharpening: +2
 *     Grain Effect: Strong, Small
 *     Color Chrome Effect: Strong
 *     White Balance: Auto, +2 Red & -4 Blue
 *     ISO: up to ISO 6400
 *     Exposure Compensation: +1/3 to +1
 *
 * Two passes: labelled lines first, then whatever is left is tried against the
 * fields still unfilled. Anything unparsed is reported rather than dropped, so
 * the import screen can show what it did and did not understand.
 *
 * Ported from FilmKit's parse-text-preset.ts (MIT).
 */
/** Mutable accumulator; converted to a Recipe once every line is seen. */
private class Draft {
    var filmSimulation: Int? = null
    var dynamicRange: Int? = null
    var grain: Grain? = null
    var colorChrome: Effect? = null
    var colorChromeFxBlue: Effect? = null
    var smoothSkin: Effect? = null
    var whiteBalance: Int? = null
    var wbShiftR: Int? = null
    var wbShiftB: Int? = null
    var wbColorTemp: Int? = null
    var highlightTone: Double? = null
    var shadowTone: Double? = null
    var color: Double? = null
    var sharpness: Double? = null
    var noiseReduction: Int? = null
    var clarity: Double? = null
    var monoWC: Double? = null
    var monoMG: Double? = null

    fun toRecipe(name: String, notes: String): Recipe {
        val d = Recipe()
        return Recipe(
            name = name,
            filmSimulation = filmSimulation ?: d.filmSimulation,
            dynamicRange = dynamicRange ?: d.dynamicRange,
            grain = grain ?: d.grain,
            colorChrome = colorChrome ?: d.colorChrome,
            colorChromeFxBlue = colorChromeFxBlue ?: d.colorChromeFxBlue,
            smoothSkin = smoothSkin ?: d.smoothSkin,
            whiteBalance = whiteBalance ?: d.whiteBalance,
            wbShiftR = wbShiftR ?: d.wbShiftR,
            wbShiftB = wbShiftB ?: d.wbShiftB,
            wbColorTemp = wbColorTemp ?: d.wbColorTemp,
            highlightTone = highlightTone ?: d.highlightTone,
            shadowTone = shadowTone ?: d.shadowTone,
            color = color ?: d.color,
            sharpness = sharpness ?: d.sharpness,
            clarity = clarity ?: d.clarity,
            noiseReduction = noiseReduction ?: d.noiseReduction,
            monoWC = monoWC ?: d.monoWC,
            monoMG = monoMG ?: d.monoMG,
            notes = notes,
        )
    }
}

/** Applies a parsed value to the draft; returns the fields it filled. */
private typealias ValueParser = (String, Draft) -> List<TextRecipeParser.Field>

object TextRecipeParser {

    /** Which recipe field a line filled in. */
    enum class Field(val label: String) {
        FILM_SIMULATION("Film Simulation"),
        DYNAMIC_RANGE("Dynamic Range"),
        GRAIN("Grain Effect"),
        COLOR_CHROME("Color Chrome"),
        COLOR_CHROME_FX_BLUE("Color Chrome FX Blue"),
        SMOOTH_SKIN("Smooth Skin"),
        WHITE_BALANCE("White Balance"),
        WB_SHIFT("WB Shift"),
        WB_COLOR_TEMP("WB Color Temp"),
        HIGHLIGHT_TONE("Highlight"),
        SHADOW_TONE("Shadow"),
        COLOR("Color"),
        SHARPNESS("Sharpness"),
        NOISE_REDUCTION("Noise Reduction"),
        CLARITY("Clarity"),
        MONO_WC("Mono Warm/Cool"),
        MONO_MG("Mono Magenta/Green"),
    }

    data class RecognizedLine(val line: String, val fields: List<Field>)

    data class Result(
        val recipe: Recipe,
        val recognized: List<RecognizedLine>,
        /** Lines matching a known label that a bank cannot store — ISO, exposure. */
        val ignored: List<String>,
        val unrecognized: List<String>,
    ) {
        val filledFields: Set<Field> get() = recognized.flatMap { it.fields }.toSet()
    }

    // -- Lookups -----------------------------------------------------------

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val FILM_SIM_LOOKUP: Map<String, Int> = buildMap {
        for ((value, label) in FilmSim.LABELS) {
            put(norm(label), value)
            // "Provia (Standard)" should also answer to "Provia".
            val short = label.replace(Regex("\\s*\\(.*\\)"), "")
            if (short != label) put(norm(short), value)
        }
        // Names as recipe authors actually write them.
        put("proviastd", FilmSim.PROVIA)
        put("standard", FilmSim.PROVIA)
        put("vivid", FilmSim.VELVIA)
        put("soft", FilmSim.ASTIA)
        put("pronegativehi", FilmSim.PRO_NEG_HI)
        put("pronegativestd", FilmSim.PRO_NEG_STD)
        put("monochromeyellowfilter", FilmSim.MONOCHROME_YE)
        put("monochromeredfilter", FilmSim.MONOCHROME_R)
        put("monochromegreenfilter", FilmSim.MONOCHROME_G)
        put("monoye", FilmSim.MONOCHROME_YE)
        put("monor", FilmSim.MONOCHROME_R)
        put("monog", FilmSim.MONOCHROME_G)
        put("monoyellowfilter", FilmSim.MONOCHROME_YE)
        put("monoredfilter", FilmSim.MONOCHROME_R)
        put("monogreenfilter", FilmSim.MONOCHROME_G)
        put("classicnegative", FilmSim.CLASSIC_NEG)
        put("eternacinema", FilmSim.ETERNA)
        put("cinema", FilmSim.ETERNA)
        put("eternableach", FilmSim.ETERNA_BLEACH)
        put("bleachbypass", FilmSim.ETERNA_BLEACH)
        put("nostalgicnegative", FilmSim.NOSTALGIC_NEG)
        put("acrosyellowfilter", FilmSim.ACROS_YE)
        put("acrosredfilter", FilmSim.ACROS_R)
        put("acrosgreenfilter", FilmSim.ACROS_G)
    }

    private val WB_LOOKUP: Map<String, Int> = buildMap {
        for ((value, label) in WhiteBalance.LABELS) put(norm(label), value)
        put("auto", WhiteBalance.AUTO)
        put("daylight", WhiteBalance.DAYLIGHT)
        put("sunny", WhiteBalance.DAYLIGHT)
        put("cloudy", WhiteBalance.SHADE)
        put("shade", WhiteBalance.SHADE)
        put("tungsten", WhiteBalance.INCANDESCENT)
        put("incandescent", WhiteBalance.INCANDESCENT)
        put("fluorescent", WhiteBalance.FLUORESCENT_1)
        put("underwater", WhiteBalance.UNDERWATER)
        put("kelvin", WhiteBalance.COLOR_TEMP)
        put("colortemperature", WhiteBalance.COLOR_TEMP)
        put("colourtemperature", WhiteBalance.COLOR_TEMP)
    }

    // -- Value parsers -----------------------------------------------------

    private val parseFilmSim: ValueParser = { value, d ->
        val sim = FILM_SIM_LOOKUP[norm(value)]
        if (sim != null) { d.filmSimulation = sim; listOf(Field.FILM_SIMULATION) } else emptyList()
    }

    private val KELVIN = Regex("""(\d{3,5})\s*K\b""", RegexOption.IGNORE_CASE)
    private val WB_SHIFT = Regex(
        """([+-]?\d+)\s*Red\s*[&,]\s*([+-]?\d+)\s*Blue""", RegexOption.IGNORE_CASE
    )

    private val parseWhiteBalance: ValueParser = { value, d ->
        val fields = mutableListOf<Field>()

        val kelvin = KELVIN.find(value)
        if (kelvin != null) {
            d.whiteBalance = WhiteBalance.COLOR_TEMP
            d.wbColorTemp = kelvin.groupValues[1].toInt()
            fields += Field.WHITE_BALANCE
            fields += Field.WB_COLOR_TEMP
        } else {
            val modePart = value.split(",").first().trim()
            WB_LOOKUP[norm(modePart)]?.let {
                d.whiteBalance = it
                fields += Field.WHITE_BALANCE
            }
        }

        WB_SHIFT.find(value)?.let { m ->
            d.wbShiftR = m.groupValues[1].toInt()
            d.wbShiftB = m.groupValues[2].toInt()
            fields += Field.WB_SHIFT
        }
        fields
    }

    private val parseDynamicRange: ValueParser = { value, d ->
        val v = value.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val dr = when (v) {
            "DR100", "100" -> DynamicRange.DR100
            "DR200", "200" -> DynamicRange.DR200
            "DR400", "400" -> DynamicRange.DR400
            // "Auto" cannot be stored in a bank as such; DR100 is the safe floor.
            "AUTO" -> DynamicRange.DR100
            else -> null
        }
        if (dr != null) { d.dynamicRange = dr; listOf(Field.DYNAMIC_RANGE) } else emptyList()
    }

    private val parseGrain: ValueParser = { value, d ->
        val v = value.lowercase().trim()
        val grain = if (v == "off") Grain.OFF else {
            val strong = Regex("""\bstrong\b""").containsMatchIn(v)
            val weak = Regex("""\bweak\b""").containsMatchIn(v)
            val large = Regex("""\blarge\b""").containsMatchIn(v)
            when {
                strong && large -> Grain.STRONG_LARGE
                strong -> Grain.STRONG_SMALL
                weak && large -> Grain.WEAK_LARGE
                weak -> Grain.WEAK_SMALL
                else -> null
            }
        }
        if (grain != null) { d.grain = grain; listOf(Field.GRAIN) } else emptyList()
    }

    private fun effectParser(field: Field, apply: (Draft, Effect) -> Unit): ValueParser =
        { value, d ->
            val effect = when (value.lowercase().trim()) {
                "off" -> Effect.OFF
                "weak" -> Effect.WEAK
                "strong" -> Effect.STRONG
                else -> null
            }
            if (effect != null) { apply(d, effect); listOf(field) } else emptyList()
        }

    /** Accepts "+2", "-1.5", "2". Rejects anything with trailing prose. */
    private fun numericParser(field: Field, apply: (Draft, Double) -> Unit): ValueParser =
        { value, d ->
            val n = value.trim().removePrefix("+").toDoubleOrNull()
            if (n != null) { apply(d, n); listOf(field) } else emptyList()
        }

    private fun intParser(field: Field, apply: (Draft, Int) -> Unit): ValueParser =
        { value, d ->
            val n = value.trim().removePrefix("+").toDoubleOrNull()
            if (n != null) { apply(d, Math.round(n).toInt()); listOf(field) } else emptyList()
        }

    // -- Label patterns (pass 1) -------------------------------------------
    // Order matters: the more specific label must be tried first, otherwise
    // "Color Chrome Effect Blue" is eaten by the Color Chrome pattern.

    private data class LabelPattern(
        val regex: Regex,
        val parser: ValueParser?,
        val ignore: Boolean = false,
    )

    private val IC = setOf(RegexOption.IGNORE_CASE)

    private val LABEL_PATTERNS: List<LabelPattern> = listOf(
        LabelPattern(Regex("""^film\s*sim(?:ulation)?\s*[:=\-]?\s*(.+)$""", IC), parseFilmSim),
        LabelPattern(
            Regex("""^colou?r\s*chrome\s*(?:effect\s*)?(?:fx\s*)?blue\s*[:=\-]?\s*(.+)$""", IC),
            effectParser(Field.COLOR_CHROME_FX_BLUE) { d, e -> d.colorChromeFxBlue = e },
        ),
        LabelPattern(
            Regex("""^colou?r\s*chrome(?:\s*effect)?\s*[:=\-]?\s*(.+)$""", IC),
            effectParser(Field.COLOR_CHROME) { d, e -> d.colorChrome = e },
        ),
        LabelPattern(Regex("""^(?:dynamic\s*range|dr)\s*[:=\-]?\s*(.+)$""", IC), parseDynamicRange),
        LabelPattern(Regex("""^grain(?:\s*effect)?\s*[:=\-]?\s*(.+)$""", IC), parseGrain),
        LabelPattern(
            Regex("""^smooth\s*skin\s*[:=\-]?\s*(.+)$""", IC),
            effectParser(Field.SMOOTH_SKIN) { d, e -> d.smoothSkin = e },
        ),
        LabelPattern(
            Regex("""^(?:white\s*balance|wb|colou?r\s*temp(?:erature)?|kelvin)\s*[:=\-]?\s*(.+)$""", IC),
            parseWhiteBalance,
        ),
        LabelPattern(
            Regex("""^highlight(?:s)?(?:\s*tone)?\s*[:=\-]?\s*(.+)$""", IC),
            numericParser(Field.HIGHLIGHT_TONE) { d, v -> d.highlightTone = v },
        ),
        LabelPattern(
            Regex("""^shadow(?:s)?(?:\s*tone)?\s*[:=\-]?\s*(.+)$""", IC),
            numericParser(Field.SHADOW_TONE) { d, v -> d.shadowTone = v },
        ),
        LabelPattern(
            Regex("""^colou?r(?!\s*chrome)(?!\s*temp)\s*[:=\-]?\s*(.+)$""", IC),
            numericParser(Field.COLOR) { d, v -> d.color = v },
        ),
        LabelPattern(
            Regex("""^(?:sharpness|sharpening)\s*[:=\-]?\s*(.+)$""", IC),
            numericParser(Field.SHARPNESS) { d, v -> d.sharpness = v },
        ),
        LabelPattern(
            Regex("""^(?:high\s*iso\s*(?:noise\s*reduction|nr)|noise\s*reduction|nr)\s*[:=\-]?\s*(.+)$""", IC),
            intParser(Field.NOISE_REDUCTION) { d, v -> d.noiseReduction = v },
        ),
        LabelPattern(
            Regex("""^clarity\s*[:=\-]?\s*(.+)$""", IC),
            numericParser(Field.CLARITY) { d, v -> d.clarity = v },
        ),
        LabelPattern(
            Regex("""^(?:mono(?:chrome)?\s*)?(?:warm\s*[/&]?\s*cool|wc)\s*[:=\-]?\s*(.+)$""", IC),
            numericParser(Field.MONO_WC) { d, v -> d.monoWC = v },
        ),
        LabelPattern(
            Regex("""^(?:mono(?:chrome)?\s*)?(?:magenta\s*[/&]?\s*green|mg)\s*[:=\-]?\s*(.+)$""", IC),
            numericParser(Field.MONO_MG) { d, v -> d.monoMG = v },
        ),
        // Recognised, but a bank cannot hold them — they end up in the notes.
        LabelPattern(Regex("""^iso\s*[:=\-]?\s*(.+)$""", IC), null, ignore = true),
        LabelPattern(
            Regex("""^(?:exposure(?:\s*compensation)?|ev)\s*[:=\-]?\s*(.+)$""", IC),
            null, ignore = true,
        ),
        LabelPattern(Regex("""^(?:d(?:ynamic)?\s*range\s*priority|wide\s*d(?:ynamic)?\s*range)\s*[:=\-]?\s*(.+)$""", IC), null, ignore = true),
    )

    /** For unlabelled lines: try these against fields still unfilled. */
    private val PASS2: List<Pair<Field, ValueParser>> = listOf(
        Field.FILM_SIMULATION to parseFilmSim,
        Field.WHITE_BALANCE to parseWhiteBalance,
        Field.DYNAMIC_RANGE to parseDynamicRange,
        Field.GRAIN to parseGrain,
        Field.COLOR_CHROME to effectParser(Field.COLOR_CHROME) { d, e -> d.colorChrome = e },
        Field.COLOR_CHROME_FX_BLUE to
            effectParser(Field.COLOR_CHROME_FX_BLUE) { d, e -> d.colorChromeFxBlue = e },
        Field.SMOOTH_SKIN to effectParser(Field.SMOOTH_SKIN) { d, e -> d.smoothSkin = e },
    )

    // -- Entry point -------------------------------------------------------

    fun parse(text: String, name: String = ""): Result {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val draft = Draft()
        val recognized = mutableListOf<RecognizedLine>()
        val ignored = mutableListOf<String>()
        val unrecognized = mutableListOf<String>()
        val filled = mutableSetOf<Field>()
        val unlabelled = mutableListOf<String>()

        // Pass 1 — labelled lines.
        for (line in lines) {
            val pattern = LABEL_PATTERNS.firstOrNull { it.regex.containsMatchIn(line) }
            if (pattern == null) {
                unlabelled.add(line)
                continue
            }
            if (pattern.ignore || pattern.parser == null) {
                ignored.add(line)
                continue
            }
            val value = pattern.regex.find(line)!!.groupValues[1].trim()
            val fields = pattern.parser.invoke(value, draft)
            if (fields.isEmpty()) {
                // The label was understood but the value was not — worth showing.
                unrecognized.add(line)
            } else {
                recognized.add(RecognizedLine(line, fields))
                filled.addAll(fields)
            }
        }

        // Pass 2 — bare lines like "Classic Chrome" or "DR400".
        for (line in unlabelled) {
            var matched = false
            for ((field, parser) in PASS2) {
                if (field in filled) continue
                val fields = parser(line, draft)
                if (fields.isNotEmpty()) {
                    recognized.add(RecognizedLine(line, fields))
                    filled.addAll(fields)
                    matched = true
                    break
                }
            }
            if (!matched) unrecognized.add(line)
        }

        // ISO and exposure cannot live in a bank, so they are kept as a note
        // rather than silently dropped.
        val notes = ignored.joinToString("\n")

        // An unnamed recipe takes the first unrecognised line as its name — on
        // Fuji X Weekly that is nearly always the recipe's title.
        val resolvedName = when {
            name.isNotEmpty() -> name
            unrecognized.isNotEmpty() -> unrecognized.first().take(NAME_MAX)
            else -> ""
        }

        return Result(draft.toRecipe(resolvedName, notes), recognized, ignored, unrecognized)
    }

    private const val NAME_MAX = 32
}
