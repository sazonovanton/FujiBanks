package org.nemo.fujibanks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.FilmSim
import org.nemo.fujibanks.fuji.Recipe
import kotlin.math.abs

/**
 * One numeric recipe parameter, with the range the camera menu offers for it.
 * The range matters: drawing every parameter on an assumed -4..+4 would put
 * Clarity and Sharpness on scales they do not actually have.
 */
data class Param(
    val label: String,
    val value: Double,
    val min: Double,
    val max: Double,
) {
    /** Where the value sits between min and max, 0..1. */
    val fraction: Float
        get() = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    /** Where zero sits on the same scale — the detent the bar grows from. */
    val zeroFraction: Float
        get() = ((0.0 - min) / (max - min)).toFloat().coerceIn(0f, 1f)

    val display: String
        get() {
            val sign = if (value > 0) "+" else ""
            return if (value % 1.0 == 0.0) "$sign${value.toInt()}" else "$sign$value"
        }
}

/** The numeric parameters of a recipe, in the order the camera menu lists them. */
fun Recipe.numericParams(): List<Param> = buildList {
    add(Param("HIGHLIGHT", highlightTone, -2.0, 4.0))
    add(Param("SHADOW", shadowTone, -2.0, 4.0))
    if (!isMono) add(Param("COLOUR", color, -4.0, 4.0))
    add(Param("SHARPNESS", sharpness, -4.0, 4.0))
    add(Param("HIGH ISO NR", noiseReduction.toDouble(), -4.0, 4.0))
    add(Param("CLARITY", clarity, -5.0, 5.0))
    if (isMono) {
        add(Param("WARM/COOL", monoWC, -9.0, 9.0))
        add(Param("MAGENTA/GREEN", monoMG, -9.0, 9.0))
    }
}

/**
 * A parameter as a track with a detent at zero and a bar growing out of it.
 *
 * The bar direction carries the sign, so the shape of a recipe is readable
 * without reading any numbers — which is the point of showing it this way
 * rather than as a column of figures.
 */
@Composable
fun ParamBar(
    param: Param,
    modifier: Modifier = Modifier,
    accent: Color = Film.Accent,
    /** Drawn behind the bar as a ghost, for before/after comparisons. */
    ghost: Param? = null,
) {
    // Merged, so the row is announced as "HIGHLIGHT, -0.5" in one stop. The
    // label and the value are already real text; it is the bar between them
    // that has nothing to say, and unmerged it split one reading into three.
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One line, always: "MAGENTA/GREEN" wraps at a large font scale, and a
        // row that grows reflows the whole block around it.
        Text(
            param.label,
            style = LabelCaps,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
        )

        Canvas(
            Modifier
                .weight(1f)
                .height(14.dp)
        ) {
            val trackY = size.height / 2f
            val trackHeight = 2.dp.toPx()

            drawLine(
                color = Film.Outline,
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round,
            )

            val zeroX = size.width * param.zeroFraction
            // The detent: a short tick marking where zero falls on this scale.
            drawLine(
                color = Film.TextMuted,
                start = Offset(zeroX, trackY - 4.dp.toPx()),
                end = Offset(zeroX, trackY + 4.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )

            ghost?.let {
                val gx = size.width * it.fraction
                drawLine(
                    color = Film.AccentDim,
                    start = Offset(zeroX, trackY),
                    end = Offset(gx, trackY),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            val valueX = size.width * param.fraction
            if (abs(valueX - zeroX) > 0.5f) {
                drawLine(
                    color = accent,
                    start = Offset(zeroX, trackY),
                    end = Offset(valueX, trackY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // A dot marks the value even when it sits exactly on zero.
            drawCircle(color = accent, radius = 2.5.dp.toPx(), center = Offset(valueX, trackY))
        }

        Spacer(Modifier.width(10.dp))
        Text(
            param.display,
            style = ValueMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Wide enough for "-0.5"; anything narrower wraps the half steps.
            modifier = Modifier.width(38.dp),
        )
    }
}

/** A label/value row for the parameters that are named settings, not numbers. */
@Composable
fun EnumRow(label: String, value: String, emphasis: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = Film.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = ValueMono,
            color = if (emphasis) Film.Accent else Film.TextPrimary,
        )
    }
}

/** A small header for a block within a card. */
@Composable
fun BlockLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = LabelCaps, modifier = modifier.padding(bottom = 6.dp))
}

/**
 * The card a screen shows in place of a list it has nothing to put in.
 *
 * A section that simply disappears when empty is indistinguishable from one
 * that failed to load, and it teaches nothing: the first thing a new library or
 * an untouched backup list has to do is say what would be here and how to put
 * something in it. One shape for all of them, so an empty Photos screen and an
 * empty recipe library do not look like two different kinds of problem.
 */
@Composable
fun EmptyNote(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Film.Surface)
            .border(1.dp, Film.Outline, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Film.TextSecondary)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Film.TextMuted)
    }
}

/**
 * A pastel keyed to the film simulation, in the spirit of the stock rather than
 * a measurement of it: ochre for the Classic pair, rose for Velvia, sage for
 * the Eterna cinema stocks, warm neutrals for the monochromes.
 *
 * These are the base the artwork is built from, so they are kept desaturated —
 * the recipe's own Colour value pushes saturation from here, and starting
 * anywhere brighter would leave nowhere to go.
 */
fun filmSimTint(sim: Int): Color = when (sim) {
    FilmSim.VELVIA -> Color(0xFFDCADAD)          // dusty rose
    FilmSim.ASTIA -> Color(0xFFEAC8B7)            // pale peach
    FilmSim.PRO_NEG_HI, FilmSim.PRO_NEG_STD -> Color(0xFFE0C9BC)
    FilmSim.CLASSIC_CHROME -> Color(0xFFD2C09C)   // faded ochre
    FilmSim.CLASSIC_NEG -> Color(0xFFC6AC95)
    FilmSim.NOSTALGIC_NEG -> Color(0xFFDEC09E)    // warm amber
    FilmSim.ETERNA -> Color(0xFFB6C2B6)           // cinema sage
    FilmSim.ETERNA_BLEACH -> Color(0xFFC0C6C4)    // bleached, nearly grey
    FilmSim.REALA_ACE -> Color(0xFFCEC9C1)
    FilmSim.SEPIA -> Color(0xFFD7BF9C)
    FilmSim.MONOCHROME, FilmSim.MONOCHROME_YE,
    FilmSim.MONOCHROME_R, FilmSim.MONOCHROME_G -> Color(0xFFC9C4BE)
    FilmSim.ACROS, FilmSim.ACROS_YE,
    FilmSim.ACROS_R, FilmSim.ACROS_G -> Color(0xFFCFCBC4)
    else -> Color(0xFFC6CABF)                     // Provia and anything unknown
}

/** Enum-valued settings, as label/value pairs. */
fun Recipe.enumRows(): List<Pair<String, String>> = buildList {
    add("Simulation" to FilmSim.label(filmSimulation))
    add("Dynamic Range" to "DR$dynamicRange%")
    add("Grain" to grain.label)
    add("Colour Chrome" to colorChrome.label)
    add("CC FX Blue" to colorChromeFxBlue.label)
    add("Smooth Skin" to smoothSkin.label)
    val wb = org.nemo.fujibanks.fuji.WhiteBalance.label(whiteBalance)
    val shift = "R${if (wbShiftR >= 0) "+" else ""}$wbShiftR B${if (wbShiftB >= 0) "+" else ""}$wbShiftB"
    if (whiteBalance == org.nemo.fujibanks.fuji.WhiteBalance.COLOR_TEMP) {
        add("White Balance" to "${wbColorTemp}K · $shift")
    } else {
        add("White Balance" to "$wb · $shift")
    }
}
