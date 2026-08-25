package org.nemo.fujibanks.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A fixed, warm palette rather than dynamic colour.
 *
 * The greys carry a little red and yellow so they read as paper and film base
 * instead of screen blue, and the accent is a muted sage rather than anything
 * saturated. Dynamic colour is deliberately not used: pulling accents from the
 * wallpaper would tint the recipe artwork, which is the one thing on screen
 * that has to mean what it shows.
 */
object Film {
    val Background = Color(0xFF0E0C0B)
    val Surface = Color(0xFF1A1715)
    val SurfaceRaised = Color(0xFF241F1B)
    val Outline = Color(0xFF37312B)
    /** The film-strip base the slot rail sits on — darker than the page. */
    val StripBase = Color(0xFF0A0908)

    /** Chalky apricot: pastel, warm, and impossible to mistake for a system blue. */
    val Accent = Color(0xFFE4B79A)
    /** The same hue with the light taken out, for ghosts and inactive marks. */
    val AccentDim = Color(0xFF8A6A57)

    val TextPrimary = Color(0xFFF0EAE3)
    val TextSecondary = Color(0xFFB0A79E)
    val TextMuted = Color(0xFF7C746C)

    /** Reserved for states, never for decoration — pastel like everything else. */
    val Warn = Color(0xFFE9CE9B)
    val Bad = Color(0xFFDFA096)
    val Good = Color(0xFFB9CFA6)
}

private val Scheme = darkColorScheme(
    primary = Film.Accent,
    onPrimary = Film.Background,
    secondary = Film.AccentDim,
    background = Film.Background,
    onBackground = Film.TextPrimary,
    surface = Film.Surface,
    onSurface = Film.TextPrimary,
    surfaceVariant = Film.SurfaceRaised,
    onSurfaceVariant = Film.TextSecondary,
    outline = Film.Outline,
    error = Film.Bad,
)

/** Small uppercase label with wide tracking — used for parameter names. */
val LabelCaps = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    letterSpacing = 1.2.sp,
    fontWeight = FontWeight.Medium,
    color = Film.TextMuted,
)

/** Values read as data, so they get a monospace face and stay aligned. */
val ValueMono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    color = Film.TextPrimary,
)

@Composable
fun FujiBanksTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
