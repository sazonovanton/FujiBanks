package org.nemo.fujibanks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.Grain
import org.nemo.fujibanks.fuji.Recipe
import org.nemo.fujibanks.fuji.WhiteBalance
import kotlin.math.pow
import kotlin.random.Random

/**
 * A recipe drawn as a picture of itself.
 *
 * Every mark here is computed from the recipe's own values — the ramp comes
 * from the tone parameters, the colour from the film simulation and the white
 * balance shift, the speckle from the grain setting. None of it is decoration:
 * two recipes that differ produce visibly different artwork, which is what
 * makes seven banks tellable apart without reading a single number.
 *
 * It is a characterisation, not a preview. It does not claim to show what a
 * photograph would look like.
 */

// -- Tone -------------------------------------------------------------------

/**
 * The tone response implied by Highlight and Shadow.
 *
 * On Fuji, a positive Shadow darkens the low end and a positive Highlight
 * brightens the top, both increasing contrast. Each acts on its own end of the
 * range, so they are weighted towards it and left to overlap in the middle.
 */
fun toneCurve(recipe: Recipe, x: Float): Float {
    val shadowWeight = (1f - x).pow(2)
    val highlightWeight = x.pow(2)
    val shadow = (recipe.shadowTone / 4.0).toFloat()
    val highlight = (recipe.highlightTone / 4.0).toFloat()
    val y = x - shadow * TONE_DEPTH * shadowWeight + highlight * TONE_DEPTH * highlightWeight
    return y.coerceIn(0f, 1f)
}

private const val TONE_DEPTH = 0.3f

// -- Colour -----------------------------------------------------------------

/**
 * The cast a recipe leans towards: the film simulation's own bias, pushed by
 * the white balance shift and the colour temperature, drained by a low Colour
 * value and removed entirely for a monochrome simulation.
 */
fun recipeCast(recipe: Recipe): Color {
    if (recipe.isMono) {
        // Monochrome still carries a tint through the warm/cool axis.
        val warm = (recipe.monoWC / 9.0).toFloat()
        return Color(
            red = (0.5f + warm * 0.12f).coerceIn(0f, 1f),
            green = 0.5f,
            blue = (0.5f - warm * 0.12f).coerceIn(0f, 1f),
        )
    }

    val base = filmSimTint(recipe.filmSimulation)

    // WB shift is in units of roughly a ninth of the range each way.
    val redPush = recipe.wbShiftR / 9f
    val bluePush = recipe.wbShiftB / 9f

    // Colour temperature: below 5000K reads cool, above reads warm.
    val kelvinPush = if (recipe.whiteBalance == WhiteBalance.COLOR_TEMP) {
        ((recipe.wbColorTemp - 5500) / 5000f).coerceIn(-1f, 1f)
    } else 0f

    // Saturation follows the Colour parameter around its neutral point.
    val saturation = (1f + (recipe.color / 4.0).toFloat() * 0.5f).coerceIn(0.2f, 1.6f)

    val grey = (base.red + base.green + base.blue) / 3f
    fun mix(channel: Float, push: Float) =
        (grey + (channel - grey) * saturation + push * 0.18f).coerceIn(0f, 1f)

    return Color(
        red = mix(base.red, redPush + kelvinPush),
        green = mix(base.green, 0f),
        blue = mix(base.blue, bluePush - kelvinPush),
    )
}

/** The ramp a recipe paints: its shadow end, its cast, its highlight end. */
fun recipeRamp(recipe: Recipe): List<Color> {
    val cast = recipeCast(recipe)

    val shadowLevel = toneCurve(recipe, 0.15f)
    val midLevel = toneCurve(recipe, 0.5f)
    val highLevel = toneCurve(recipe, 0.9f)

    fun level(l: Float, tint: Float) = Color(
        red = (cast.red * l * tint).coerceIn(0f, 1f),
        green = (cast.green * l * tint).coerceIn(0f, 1f),
        blue = (cast.blue * l * tint).coerceIn(0f, 1f),
    )

    return listOf(
        level(shadowLevel, 0.55f),
        level(midLevel, 1.0f),
        level(highLevel, 1.45f),
    )
}

// -- Drawing ----------------------------------------------------------------

/**
 * The full artwork: ramp, tone curve, grain speckle.
 *
 * The grain speckle is seeded from the grain setting and the name, not from the
 * whole recipe: the seed exists so the speckle holds still, and hashing every
 * field meant a slider drag re-scattered up to 2600 points on every frame — the
 * artwork boiled while it was being dialled in.
 */
@androidx.compose.runtime.Composable
fun RecipeArtwork(
    recipe: Recipe,
    modifier: Modifier = Modifier,
    showCurve: Boolean = true,
) {
    val ramp = recipeRamp(recipe)
    val seed = remember(recipe.name, recipe.grain) { (recipe.name to recipe.grain).hashCode() }

    // Said plainly, because the artwork is a characterisation of the settings
    // and not a photograph — calling it a preview here would make the same
    // false claim by ear that the SAMPLE FRAME / COMPUTED label prevents by eye.
    val described = remember(recipe.name) {
        "Artwork computed from the settings of ${recipe.name.ifEmpty { "this recipe" }}"
    }

    Canvas(modifier.semantics { contentDescription = described }) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to ramp[2],
                0.5f to ramp[1],
                1f to ramp[0],
            )
        )
        // A horizontal wash keeps a flat recipe from reading as a plain block.
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.28f),
                0.45f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.18f),
            )
        )

        if (recipe.grain != Grain.OFF) drawGrain(recipe.grain, seed)
        if (showCurve) drawToneCurve(recipe)
    }
}

/** Grain as actual speckle: density from strength, dot size from grain size. */
private fun DrawScope.drawGrain(grain: Grain, seed: Int) {
    val strong = grain == Grain.STRONG_SMALL || grain == Grain.STRONG_LARGE
    val large = grain == Grain.WEAK_LARGE || grain == Grain.STRONG_LARGE

    val count = ((size.width * size.height) / (if (strong) 90f else 220f)).toInt()
        .coerceAtMost(MAX_GRAIN_DOTS)
    val radius = (if (large) 1.4f else 0.8f).dp.toPx()
    val alpha = if (strong) 0.22f else 0.12f

    val rng = Random(seed)
    repeat(count) {
        val x = rng.nextFloat() * size.width
        val y = rng.nextFloat() * size.height
        val bright = rng.nextBoolean()
        drawCircle(
            color = if (bright) Color.White.copy(alpha = alpha)
            else Color.Black.copy(alpha = alpha),
            radius = radius,
            center = Offset(x, y),
        )
    }
}

private const val MAX_GRAIN_DOTS = 2600

/** The tone response, drawn across the artwork as a thin ridge. */
private fun DrawScope.drawToneCurve(recipe: Recipe) {
    val path = Path()
    val steps = 48
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = t * size.width
        val y = size.height * (1f - toneCurve(recipe, t))
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    // A dark pass underneath keeps the ridge readable over a bright ramp.
    drawPath(
        path,
        color = Color.Black.copy(alpha = 0.35f),
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
    )
    drawPath(
        path,
        color = Color.White.copy(alpha = 0.72f),
        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
    )

    // The straight line the curve departs from. Kept faint on purpose: it is a
    // reference, and at full strength it read as the subject of the picture.
    drawLine(
        color = Color.White.copy(alpha = 0.10f),
        start = Offset(0f, size.height),
        end = Offset(size.width, 0f),
        strokeWidth = 0.75.dp.toPx(),
    )
}

/**
 * Sprocket holes along an edge, for the film-strip treatment on the slot rail.
 */
fun DrawScope.drawSprockets(top: Boolean, color: Color) {
    val holeWidth = 5.dp.toPx()
    val holeHeight = 4.dp.toPx()
    val gap = 5.dp.toPx()
    val margin = 3.dp.toPx()
    val y = if (top) margin else size.height - margin - holeHeight
    var x = margin
    while (x + holeWidth <= size.width - margin) {
        drawRoundRect(
            color = color,
            topLeft = Offset(x, y),
            size = Size(holeWidth, holeHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
        )
        x += holeWidth + gap
    }
}
