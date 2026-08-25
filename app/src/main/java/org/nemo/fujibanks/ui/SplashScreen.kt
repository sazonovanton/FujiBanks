package org.nemo.fujibanks.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The mark assembling itself, over the app, once per launch.
 *
 * Short on purpose. This is a tool someone opens to fix a bank before a walk,
 * not a title sequence, so it costs about a second and a tap skips it. The mark
 * and the name are all it says: anything longer than two words is gone before
 * it can be read, and a line nobody reads is a line worth removing.
 */
@Composable
fun SplashOverlay(onDone: () -> Unit) {
    val reveal = remember { Animatable(0f) }
    val holes = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // The gate opens first and the perforation follows it, so the frame
        // reads as being pulled through rather than assembled out of parts.
        reveal.animateTo(1f, tween(420, easing = EaseOutCubic))
        holes.animateTo(1f, tween(200, easing = LinearEasing))
        words.animateTo(1f, tween(180))
        fade.animateTo(0f, tween(280, delayMillis = 180))
        onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            .alpha(fade.value)
            .background(Film.Background)
            // Skippable, and without a ripple: a splash that flashes back at
            // the finger looks like a button that did something.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDone,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The strip's own proportions, 56:38 — the mark fills this exactly.
        FujiMark(
            Modifier.size(width = 132.dp, height = 90.dp),
            reveal = reveal.value,
            holes = holes.value,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "FUJIBANKS",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 6.sp,
            ),
            color = Film.TextPrimary,
            modifier = Modifier.alpha(words.value),
        )
    }
}
