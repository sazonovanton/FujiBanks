package org.nemo.fujibanks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.runtime.Composable

/**
 * The app's mark: one film frame, perforated, with the recipe ramp in the gate.
 *
 * It is the same object the slot rail and the artwork are made of, which is the
 * point — the icon in the launcher is the thing the app shows you, not a symbol
 * standing in for it. The geometry is duplicated in
 * `res/drawable/ic_logo_foreground.xml`, which cannot share code with this: a
 * launcher icon has to be a vector resource, and a vector resource cannot
 * animate the way the intro does. Change one, change the other.
 *
 * [reveal] runs 0..1 and opens the gate from the left; [holes] runs 0..1 and
 * punches the perforation in. Both at 1 is the finished mark. The frame fills
 * whatever bounds it is given — give it the strip's own 56:38, or it letterboxes.
 */
@Composable
fun FujiMark(
    modifier: Modifier = Modifier,
    reveal: Float = 1f,
    holes: Float = 1f,
) {
    Canvas(modifier) { drawMark(reveal, holes) }
}

private fun DrawScope.drawMark(reveal: Float, holes: Float) {
    // Laid out in the icon's own 108-unit grid so the proportions match the
    // launcher exactly, but scaled to the *strip* rather than to the grid: the
    // empty margin around it is there to survive a circular icon mask, and on
    // screen that padding is nothing but wasted size.
    val unit = minOf(size.width / FW, size.height / FH)
    fun u(v: Float) = v * unit
    val originX = size.width / 2f - (FX + FW / 2f) * unit
    val originY = size.height / 2f - (FY + FH / 2f) * unit
    fun x(v: Float) = originX + u(v)
    fun y(v: Float) = originY + u(v)

    fun rounded(left: Float, top: Float, w: Float, h: Float, r: Float) = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(x(left), y(top), x(left + w), y(top + h)),
                CornerRadius(u(r), u(r)),
            )
        )
    }

    // The film base. Warm and plainly lighter than the page, or the strip
    // vanishes and the mark reads as a bar floating in the dark.
    drawPath(rounded(FX, FY, FW, FH, FR), Film.Outline)

    // The gate: the same warm ramp the artwork builds from the tone parameters.
    clipPath(rounded(GX, GY, GW, GH, GR)) {
        drawRect(
            brush = Brush.linearGradient(
                0f to RampHigh,
                0.45f to Film.Accent,
                1f to Film.AccentDim,
                start = Offset(x(GX), y(GY)),
                end = Offset(x(GX + GW), y(GY + GH)),
            ),
            topLeft = Offset(x(GX), y(GY)),
            // Opened from the left, so the frame reads as being pulled through
            // rather than faded up, which would just say "loading".
            size = Size(u(GW) * reveal.coerceIn(0f, 1f), u(GH)),
        )
    }

    // Perforation, punched from the ends inwards.
    val step = (FW - HoleInset * 2f) / HoleCount
    val punched = (holes.coerceIn(0f, 1f) * HoleCount).toInt()
    for (row in HoleRows) {
        for (i in 0 until HoleCount) {
            val order = if (i % 2 == 0) i / 2 else HoleCount - 1 - i / 2
            if (order >= punched) continue
            val left = FX + HoleInset + step * i + (step - HoleW) / 2f
            drawPath(rounded(left, row, HoleW, HoleH, HoleR), Film.Background)
        }
    }
}

// The mark's geometry, matching res/drawable/ic_logo_foreground.xml exactly.
// Sized to the adaptive-icon safe zone: a circular launcher mask keeps only the
// middle 66 of the 108 units, and a first cut at 64x44 had its ends shaved off.
private const val FX = 26f
private const val FY = 35f
private const val FW = 56f
private const val FH = 38f
private const val FR = 6f
private const val GX = 31.5f
private const val GY = 44.5f
private const val GW = 45f
private const val GH = 19f
private const val GR = 2f
private const val HoleW = 4.4f
private const val HoleH = 3.5f
private const val HoleR = 1.05f
private const val HoleCount = 6
private const val HoleInset = 4.4f
private val HoleRows = floatArrayOf(38.1f, 66.4f)

/** The top of the ramp — a lit apricot, brighter than the accent itself. */
private val RampHigh = androidx.compose.ui.graphics.Color(0xFFF6DCC6)
