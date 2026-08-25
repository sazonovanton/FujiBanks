package org.nemo.fujibanks.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import org.nemo.fujibanks.fuji.DynamicRange
import org.nemo.fujibanks.fuji.Effect
import org.nemo.fujibanks.fuji.FilmSim
import org.nemo.fujibanks.fuji.Grain
import org.nemo.fujibanks.fuji.Recipe
import org.nemo.fujibanks.fuji.WhiteBalance
import kotlin.math.roundToInt

/**
 * Keeps a recipe across a tab switch or a rotation.
 *
 * The screens hold a work-in-progress recipe in composable state, and switching
 * tabs disposes them. [Recipe] is already `@Serializable` for the library files,
 * so the same JSON does for a Bundle.
 */
val RecipeSaver: Saver<Recipe, String> = Saver(
    save = { runCatching { Json.encodeToString(Recipe.serializer(), it) }.getOrNull() },
    restore = { runCatching { Json.decodeFromString(Recipe.serializer(), it) }.getOrNull() },
)

/**
 * Every control a recipe has, as items in a LazyColumn.
 *
 * One set of controls, two homes: the editor builds a recipe with no camera in
 * the room, and Develop turns the same knobs against a loaded frame. They have
 * to agree — a value dialled in one place and rendered in the other must be the
 * same value — so there is one implementation and both screens call it.
 *
 * [onChange] fires while a control moves, so the artwork and the numbers keep
 * up. [onCommit] fires when it settles: a chip tap, or a slider let go of. A
 * render is a round trip to the camera and takes seconds, so it hangs off the
 * second one, never the first.
 */
fun LazyListScope.recipeControls(
    recipe: Recipe,
    onChange: (Recipe) -> Unit,
    onCommit: () -> Unit = {},
) {
    item {
        EditorBlock("FILM SIMULATION") {
            ChipRow(
                options = FilmSim.LABELS.entries.sortedBy { it.key }
                    .map { it.key to it.value },
                selected = recipe.filmSimulation,
                onSelect = { sim ->
                    // Switching between colour and monochrome makes some
                    // parameters meaningless; clear them rather than
                    // carrying a value the camera will refuse.
                    val nowMono = FilmSim.isMono(sim)
                    onChange(
                        recipe.copy(
                            filmSimulation = sim,
                            color = if (nowMono) 0.0 else recipe.color,
                            monoWC = if (nowMono) recipe.monoWC else 0.0,
                            monoMG = if (nowMono) recipe.monoMG else 0.0,
                        )
                    )
                    onCommit()
                },
            )
        }
    }

    item {
        EditorBlock("SETTINGS") {
            LabelledChips(
                label = "Dynamic Range",
                options = DynamicRange.VALUES.map { it to DynamicRange.label(it) },
                selected = recipe.dynamicRange,
                onSelect = { onChange(recipe.copy(dynamicRange = it)); onCommit() },
            )
            LabelledChips(
                label = "Grain",
                options = Grain.entries.map { it to it.label },
                selected = recipe.grain,
                onSelect = { onChange(recipe.copy(grain = it)); onCommit() },
            )
            LabelledChips(
                label = "Colour Chrome",
                options = Effect.entries.map { it to it.label },
                selected = recipe.colorChrome,
                onSelect = { onChange(recipe.copy(colorChrome = it)); onCommit() },
            )
            LabelledChips(
                label = "CC FX Blue",
                options = Effect.entries.map { it to it.label },
                selected = recipe.colorChromeFxBlue,
                onSelect = { onChange(recipe.copy(colorChromeFxBlue = it)); onCommit() },
            )
            LabelledChips(
                label = "Smooth Skin",
                options = Effect.entries.map { it to it.label },
                selected = recipe.smoothSkin,
                onSelect = { onChange(recipe.copy(smoothSkin = it)); onCommit() },
            )
        }
    }

    item {
        EditorBlock("WHITE BALANCE") {
            ChipRow(
                options = WhiteBalance.LABELS.entries
                    .sortedBy { it.key }
                    .map { it.key to it.value },
                selected = recipe.whiteBalance,
                onSelect = { onChange(recipe.copy(whiteBalance = it)); onCommit() },
            )
            Spacer(Modifier.height(8.dp))
            // Kelvin only exists under Color Temperature, and mono swaps COLOUR
            // for the two toning sliders. Appearing and vanishing shoved
            // everything below them; as a motion it reads as what it is.
            AnimatedVisibility(recipe.whiteBalance == WhiteBalance.COLOR_TEMP) {
                StepSlider(
                    label = "TEMPERATURE",
                    value = recipe.wbColorTemp.toDouble(),
                    min = 2500.0, max = 10000.0, step = 100.0,
                    display = { "${it.toInt()}K" },
                    onChange = { onChange(recipe.copy(wbColorTemp = it.toInt())) },
                    onCommit = onCommit,
                )
            }
            StepSlider(
                label = "SHIFT RED",
                value = recipe.wbShiftR.toDouble(),
                min = -9.0, max = 9.0, step = 1.0,
                onChange = { onChange(recipe.copy(wbShiftR = it.roundToInt())) },
                onCommit = onCommit,
            )
            StepSlider(
                label = "SHIFT BLUE",
                value = recipe.wbShiftB.toDouble(),
                min = -9.0, max = 9.0, step = 1.0,
                onChange = { onChange(recipe.copy(wbShiftB = it.roundToInt())) },
                onCommit = onCommit,
            )
        }
    }

    item {
        EditorBlock("ADJUSTMENTS") {
            // Tone parameters take half steps on the camera; the rest do not.
            StepSlider(
                label = "HIGHLIGHT", value = recipe.highlightTone,
                min = -2.0, max = 4.0, step = 0.5,
                onChange = { onChange(recipe.copy(highlightTone = it)) },
                onCommit = onCommit,
            )
            StepSlider(
                label = "SHADOW", value = recipe.shadowTone,
                min = -2.0, max = 4.0, step = 0.5,
                onChange = { onChange(recipe.copy(shadowTone = it)) },
                onCommit = onCommit,
            )
            AnimatedVisibility(!recipe.isMono) {
                StepSlider(
                    label = "COLOUR", value = recipe.color,
                    min = -4.0, max = 4.0, step = 1.0,
                    onChange = { onChange(recipe.copy(color = it)) },
                    onCommit = onCommit,
                )
            }
            StepSlider(
                label = "SHARPNESS", value = recipe.sharpness,
                min = -4.0, max = 4.0, step = 1.0,
                onChange = { onChange(recipe.copy(sharpness = it)) },
                onCommit = onCommit,
            )
            StepSlider(
                label = "HIGH ISO NR", value = recipe.noiseReduction.toDouble(),
                min = -4.0, max = 4.0, step = 1.0,
                onChange = { onChange(recipe.copy(noiseReduction = it.roundToInt())) },
                onCommit = onCommit,
            )
            StepSlider(
                label = "CLARITY", value = recipe.clarity,
                min = -5.0, max = 5.0, step = 1.0,
                onChange = { onChange(recipe.copy(clarity = it)) },
                onCommit = onCommit,
            )
            AnimatedVisibility(recipe.isMono) {
                Column {
                    StepSlider(
                        label = "WARM/COOL", value = recipe.monoWC,
                        min = -9.0, max = 9.0, step = 1.0,
                        onChange = { onChange(recipe.copy(monoWC = it)) },
                        onCommit = onCommit,
                    )
                    StepSlider(
                        label = "MAGENTA/GREEN", value = recipe.monoMG,
                        min = -9.0, max = 9.0, step = 1.0,
                        onChange = { onChange(recipe.copy(monoMG = it)) },
                        onCommit = onCommit,
                    )
                }
            }
        }
    }
}

/**
 * A slider constrained to the steps the camera accepts, with the value shown
 * beside it. Continuous sliders would let a value be chosen that has to be
 * rounded on the way out, and then the number on screen is not what gets written.
 */
@Composable
internal fun StepSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    display: (Double) -> String = { signed(it) },
    onCommit: () -> Unit = {},
    onChange: (Double) -> Unit,
) {
    val positions = ((max - min) / step).roundToInt()
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = LabelCaps, modifier = Modifier.weight(1f))
            Text(display(value), style = ValueMono, maxLines = 1)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                // Snap to the step grid so the label and the write agree.
                val snapped = min + ((raw - min) / step).roundToInt() * step
                onChange(snapped.coerceIn(min, max))
            },
            onValueChangeFinished = onCommit,
            valueRange = min.toFloat()..max.toFloat(),
            steps = (positions - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = Film.Accent,
                activeTrackColor = Film.Accent,
                inactiveTrackColor = Film.Outline,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

internal fun signed(v: Double): String {
    val sign = if (v > 0) "+" else ""
    return if (v % 1.0 == 0.0) "$sign${v.toInt()}" else "$sign$v"
}

@Composable
internal fun <T> LabelledChips(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = LabelCaps)
        Spacer(Modifier.height(4.dp))
        ChipRow(options, selected, onSelect)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    // A wrapping row, not a scrolling one. FILM SIMULATION alone is twenty
    // options and several screen-widths wide: scrolled, a recipe set to
    // ACROS+R opened showing no selected chip at all, and the only way to find
    // out what was selected was to drag blindly to the far end.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) Film.Background else Film.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Film.Accent else Film.Surface)
                    .border(
                        1.dp,
                        if (on) Film.Accent else Film.Outline,
                        RoundedCornerShape(8.dp),
                    )
                    // `selectable`, not `clickable`: selection here is carried
                    // by colour and weight alone, so a screen reader announced
                    // "Classic Chrome" identically whether it was on or off.
                    .selectable(
                        selected = on,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
internal fun EditorBlock(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        BlockLabel(title)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Film.Surface)
                .border(1.dp, Film.Outline, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) { content() }
    }
}

/**
 * The one text-field palette in the app. Three byte-identical copies of this
 * block used to sit in three screens; a field that looks different from the
 * others reads as a different kind of field.
 */
@Composable
internal fun fujiFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Film.Surface,
    unfocusedContainerColor = Film.Surface,
    focusedTextColor = Film.TextPrimary,
    unfocusedTextColor = Film.TextPrimary,
    focusedIndicatorColor = Film.Accent,
    unfocusedIndicatorColor = Film.Outline,
    focusedLabelColor = Film.Accent,
    unfocusedLabelColor = Film.TextMuted,
    cursorColor = Film.Accent,
)
