package org.nemo.fujibanks.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.Recipe

/**
 * Rendering a RAF through a recipe, on the camera.
 *
 * The phone never processes the image: the file is pushed into the camera's
 * conversion buffer, the recipe is written into its profile, and the JPEG that
 * comes back is what the body itself would have produced. That is the whole
 * reason to do this over a cable rather than approximating the look here.
 *
 * The full set of recipe controls lives here, not just exposure: dialling a
 * look in against a real frame is the point of the screen, and a recipe arrived
 * at this way can be saved to the library like any other. With no RAF loaded
 * the same controls still work — the computed artwork stands in for the frame,
 * and nothing is sent to the camera until there is something to develop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DarkroomScreen(viewModel: BanksViewModel) {
    val context = LocalContext.current
    val rafName by viewModel.rafName.collectAsState()
    val asShot by viewModel.rafAsShot.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val banks by viewModel.banks.collectAsState()
    val busy by viewModel.busy.collectAsState()

    // Checking a bank on the Camera tab disposes this screen; without saving,
    // a recipe dialled in over several minutes came back empty while the render
    // it produced was still on display.
    var recipe by rememberSaveable(stateSaver = RecipeSaver) { mutableStateOf(Recipe()) }
    var exposure by rememberSaveable { mutableStateOf(0.0) }

    val loaded = rafName != null
    // Only this screen's own work counts here: a backup running on another tab
    // is not a reason to replace the artwork with "Working…".
    val working = busy?.scope == BusyScope.DEVELOP

    // Once a RAF is loaded, the recipe it was shot with is the starting point.
    LaunchedEffect(asShot) { asShot?.let { recipe = it } }

    /** A render is a round trip to the camera, so it only happens on commit. */
    fun render() { if (loaded) viewModel.renderWith(recipe, exposure) }

    /** Non-null while the dynamic-range explanation is open; holds the ceiling. */
    var explainingDr by remember { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(context, uri)
        // A file that will not open used to end here in silence: the picker
        // closed, nothing on screen changed, and the app looked hung. The read
        // happens on the screen because the grant belongs to this resolver, so
        // the failure has to be handed back to the view model to be said.
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        when {
            bytes == null -> viewModel.say("Could not read $name")
            bytes.isEmpty() -> viewModel.say("$name is empty")
            else -> viewModel.loadRaf(name, bytes)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
                    .background(Film.Surface),
                contentAlignment = Alignment.Center,
            ) {
                val jpeg = preview
                val bitmap = remember(jpeg) {
                    jpeg?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                when {
                    bitmap != null -> Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "Rendered frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    // No frame: the computed portrait of the recipe stands in.
                    // It characterises the settings, it is not a photograph.
                    !loaded && !working -> RecipeArtwork(recipe, Modifier.fillMaxSize())
                    else -> Text(
                        if (working) "Working…" else "No frame loaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Film.TextMuted,
                    )
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        // RAF has no registered MIME type, so everything is
                        // offered and the extension is checked afterwards.
                        onClick = { picker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Film.Accent,
                            contentColor = Film.Background,
                        ),
                    ) { Text(if (!loaded) "Open a RAF" else "Open another") }
                    if (loaded) {
                        TextButton(onClick = {
                            viewModel.clearRaf(); exposure = 0.0
                        }) { Text("Close", color = Film.TextSecondary) }
                    }
                }
                rafName?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = ValueMono, color = Film.TextSecondary)
                }
                if (!loaded) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Open a .RAF to see the camera develop it. Without one the " +
                            "controls still build a recipe, just with nothing to " +
                            "render it against.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Film.TextMuted,
                    )
                }
            }
        }

        // -- Where the recipe starts from -------------------------------------
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                BlockLabel("START FROM")
                Spacer(Modifier.height(6.dp))
                // Three different kinds of thing used to sit in one unlabelled
                // scrolling row, where a library recipe named "C3" was
                // indistinguishable from bank C3 — and anything past the screen
                // edge was invisible. Grouped, and wrapped so nothing hides.
                asShot?.let { shot ->
                    ChipWrap {
                        StartChip("As shot", recipe == shot) { recipe = shot; render() }
                    }
                }
                if (banks.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("IN THE CAMERA", style = LabelCaps)
                    ChipWrap {
                        banks.sortedBy { it.slot }.forEach { bank ->
                            StartChip("C${bank.slot}", recipe == bank.recipe) {
                                recipe = bank.recipe; render()
                            }
                        }
                    }
                }
                if (asShot == null && banks.isEmpty() && recipes.isEmpty()) {
                    // Otherwise START FROM is a heading over nothing, which
                    // reads as a section that failed rather than an empty one.
                    Spacer(Modifier.height(6.dp))
                    EmptyNote(
                        title = "Nowhere to start from yet",
                        body = "Read the banks on the Camera tab, save a recipe to " +
                            "the library, or open a RAF to start from how it was " +
                            "shot. Or just dial the controls below from neutral.",
                    )
                }
                if (recipes.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("IN THE LIBRARY", style = LabelCaps)
                    ChipWrap {
                        recipes.forEach { saved ->
                            StartChip(saved.name.ifEmpty { "unnamed" }, recipe == saved) {
                                recipe = saved; render()
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = recipe.name,
                    onValueChange = { recipe = recipe.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fujiFieldColors(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    recipe.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextMuted,
                )
                // The conversion is not altered — the recipe goes to the camera
                // as written. But the frame on screen will be wrong, and a wrong
                // render with nothing said about it is the one outcome this
                // screen must not produce. One line, and the reasons behind a
                // tap so they are there for whoever wants them.
                val ceiling = asShot?.dynamicRange
                if (ceiling != null && recipe.dynamicRange > ceiling) {
                    Spacer(Modifier.height(6.dp))
                    ColourWarningRow(
                        asked = recipe.dynamicRange,
                        ceiling = ceiling,
                        onClick = { explainingDr = ceiling },
                    )
                }
            }
        }

        // Exposure cannot live in a bank, but a conversion can apply it, so it
        // belongs on this screen and nowhere else.
        item {
            // Opening a RAF used to inject this block between the name field
            // and the controls, shoving everything down at the moment the
            // preview appeared. It grows in instead.
            AnimatedVisibility(loaded) {
                EditorBlock("EXPOSURE") {
                    StepSlider(
                        label = "COMPENSATION",
                        value = exposure,
                        min = -3.0, max = 3.0, step = 1.0 / 3.0,
                        display = { (if (it > 0) "+" else "") + String.format("%.1f", it) + " EV" },
                        onChange = { exposure = it },
                        onCommit = { render() },
                    )
                }
            }
        }

        recipeControls(recipe, onChange = { recipe = it }, onCommit = { render() })

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = preview != null,
                    onClick = {
                        val base = (rafName ?: "frame").substringBeforeLast('.')
                        viewModel.savePreviewToGallery(
                            base + "_" + recipe.name.ifEmpty { "asshot" }
                                .replace(Regex("[^A-Za-z0-9]+"), "-")
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Film.Accent,
                        contentColor = Film.Background,
                    ),
                ) { Text("Save JPEG") }
                OutlinedButton(onClick = {
                    viewModel.saveRecipe(
                        recipe,
                        say = "Saved \"${recipe.name.ifEmpty { "unnamed" }}\" to the library",
                    )
                }) {
                    Text("Save recipe")
                }
            }
        }

        // A disabled button that does not say what it wants reads as broken.
        if (preview == null) {
            item {
                Text(
                    "SAVE JPEG NEEDS A RENDER FIRST",
                    style = LabelCaps,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    explainingDr?.let { ceiling ->
        DynamicRangeDialog(
            asked = recipe.dynamicRange,
            ceiling = ceiling,
            onLower = {
                recipe = recipe.copy(dynamicRange = ceiling)
                explainingDr = null
                render()
            },
            onDismiss = { explainingDr = null },
        )
    }
}

/**
 * One line saying the frame on screen cannot be trusted, and a way in.
 *
 * Deliberately not a substitution notice: nothing has been changed. The recipe
 * went to the camera as written, and this says the result is wrong anyway.
 */
@Composable
private fun ColourWarningRow(asked: Int, ceiling: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Film.Warn.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Colors may be wrong · DR$asked% on a DR$ceiling% frame",
            style = MaterialTheme.typography.bodySmall,
            color = Film.Warn,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("WHY", style = LabelCaps.copy(color = Film.Warn))
    }
}

/** Why the render is wrong, and the three things that can be done about it. */
@Composable
private fun DynamicRangeDialog(
    asked: Int,
    ceiling: Int,
    onLower: () -> Unit,
    onDismiss: () -> Unit,
) {
    FujiDialog(
        title = "DR$asked% on a DR$ceiling% frame",
        subtitle = "Nothing has been changed — the recipe was sent as written.",
        onDismiss = onDismiss,
        cancel = DialogAction("Leave it", onClick = onDismiss),
        confirm = DialogAction("Use DR$ceiling%", onClick = onLower),
    ) {
        Text(
            "DR is not post-processing: the camera underexposes at capture and lifts " +
                "the shadows back. DR200% needs a stop of headroom, DR400% needs two. " +
                "This frame has none, so the camera returns crushed reds and blues " +
                "instead of refusing.",
            style = MaterialTheme.typography.bodySmall,
            color = Film.TextSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Text("WHAT YOU CAN DO", style = LabelCaps)
        Spacer(Modifier.height(6.dp))
        Bullet("Convert at DR$ceiling% — the button below. Rest of the recipe untouched.")
        Bullet("Use a frame with headroom: DR200% wants ISO 320+, DR400% ISO 640+.")
        Bullet("Change nothing — only this conversion is affected, the recipe is fine.")
        Spacer(Modifier.height(8.dp))
        Text(
            "Observed on an X-T30 III. No other body has been tested.",
            style = MaterialTheme.typography.bodySmall,
            color = Film.TextMuted,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Text("·  ", style = MaterialTheme.typography.bodySmall, color = Film.TextMuted)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Film.TextSecondary,
        )
    }
}

/** Chips that wrap instead of running off the edge of the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipWrap(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun StartChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        color = if (on) Film.Background else Film.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Film.Accent else Film.Surface)
            .border(1.dp, if (on) Film.Accent else Film.Outline, RoundedCornerShape(8.dp))
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

private fun queryName(context: android.content.Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && it.moveToFirst()) return it.getString(index)
    }
    return uri.lastPathSegment ?: "frame.RAF"
}
