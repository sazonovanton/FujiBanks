package org.nemo.fujibanks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.FujiProps
import org.nemo.fujibanks.fuji.Recipe
import org.nemo.fujibanks.fuji.RecipePack
import org.nemo.fujibanks.recipe.TextRecipeParser

/**
 * Recipes: paste one in, keep a library, group them into sets, put a set in the
 * camera.
 *
 * Installing a set replaces all seven banks at once, which is the whole point —
 * but it also means it never happens without showing what changes first.
 */
/** What the editor is open on: one recipe from the library, or a new one. */
private sealed interface Editing {
    data class Existing(val recipe: Recipe) : Editing
    data object New : Editing
}

@Composable
fun RecipesScreen(viewModel: BanksViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var parsed by remember { mutableStateOf<TextRecipeParser.Result?>(null) }
    var packName by rememberSaveable { mutableStateOf("") }
    // Ticked recipes are held by value, not by position. Positions shift under
    // the selection — Duplicate inserts at index+1 — and the tick would silently
    // move to a different recipe, which then went into the camera.
    var selected by remember { mutableStateOf(setOf<Recipe>()) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var installing by remember { mutableStateOf<Recipe?>(null) }
    var deletingPack by remember { mutableStateOf<RecipePack?>(null) }
    var replacingPack by remember { mutableStateOf<RecipePack?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Sets live at the top of the list, so making one from the button at the
    // bottom used to insert a card above the viewport and shove everything
    // down under the finger. Go to it instead — that is also the only
    // confirmation that the set now exists.
    fun madeSet() = scope.launch { listState.animateScrollToItem(0) }

    // Parsing hangs off the text rather than off each keystroke: the preview
    // is a variable-height card sitting between the two fields, so rebuilding
    // it on every character made the field below it slide around while it was
    // being typed into.
    LaunchedEffect(text, name) {
        if (text.isBlank()) { parsed = null; return@LaunchedEffect }
        delay(200)
        parsed = TextRecipeParser.parse(text, name)
    }

    val recipes by viewModel.recipes.collectAsState()
    val packs by viewModel.savedPacks.collectAsState()

    // The editor holds the recipe itself, not its row number: duplicating one
    // inserts at index + 1, and a stored position quietly became a different
    // recipe the moment the list moved under it.
    when (val open = editing) {
        null -> Unit
        is Editing.New -> {
            // Without this, Back from the editor closes the app and takes the
            // unsaved edits with it.
            BackHandler { editing = null }
            RecipeEditorScreen(
                viewModel = viewModel,
                initial = Recipe(),
                onSave = { viewModel.saveRecipe(it); editing = null },
                onCancel = { editing = null },
                onDelete = null,
            )
            return
        }
        is Editing.Existing -> {
            BackHandler { editing = null }
            RecipeEditorScreen(
                viewModel = viewModel,
                initial = open.recipe,
                onSave = { viewModel.updateRecipe(open.recipe, it); editing = null },
                onCancel = { editing = null },
                onDelete = { viewModel.deleteRecipe(open.recipe); editing = null },
            )
            return
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // -- Building one by hand, no camera needed --------------------------
        item {
            Button(
                onClick = { editing = Editing.New },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Film.Accent,
                    contentColor = Film.Background,
                ),
            ) { Text("Create a recipe") }
        }

        // -- Sets, first: this is what gets used day to day ------------------
        item { BlockLabel("SETS") }
        if (packs.isEmpty()) {
            item {
                EmptyNote(
                    title = "No sets yet",
                    body = "A set is up to seven recipes in the order they go into " +
                        "C1–C7. Build the library below, tick the ones you want, " +
                        "and name the set.",
                )
            }
        } else {
            items(packs) { pack ->
                PackCard(
                    pack = pack,
                    onInstall = { viewModel.stageWrite(pack) },
                    onDelete = { deletingPack = pack },
                )
            }
        }
        item { Spacer(Modifier.height(4.dp)) }

        // -- Adding a recipe -------------------------------------------------
        item { BlockLabel("ADD A RECIPE") }
        item {
            Text(
                "Paste one from Fuji X Weekly, or anywhere it is written out as text.",
                style = MaterialTheme.typography.bodySmall,
                color = Film.TextMuted,
            )
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        "Classic Chrome\nDynamic Range: DR400\nHighlight: +1\n…",
                        color = Film.TextMuted,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                colors = fujiFieldColors(),
            )
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fujiFieldColors(),
            )
        }

        parsed?.let { result ->
            item { ParsePreview(result) }
            item {
                Button(
                    onClick = {
                        viewModel.saveRecipe(result.recipe)
                        text = ""; name = ""; parsed = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Film.Accent,
                        contentColor = Film.Background,
                    ),
                ) { Text("Save to library") }
            }
        }

        // -- Library ---------------------------------------------------------
        if (recipes.isEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                BlockLabel("LIBRARY")
                EmptyNote(
                    title = "The library is empty",
                    body = "Paste a recipe above, build one by hand, or read the " +
                        "camera and save a bank you already have.",
                )
            }
        } else {
            item {
                Spacer(Modifier.height(4.dp))
                BlockLabel("LIBRARY (${recipes.size})")
                Text(
                    "Tick up to seven, name the set, and it is ready to install.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextMuted,
                )
            }
            items(recipes) { row ->
                RecipeRow(
                    recipe = row,
                    checked = row in selected,
                    // Seven banks, seven recipes: past that the toggle used to
                    // do nothing at all while still looking like a button.
                    canCheck = row in selected || selected.size < FujiProps.SLOT_COUNT,
                    onToggle = {
                        selected =
                            if (row in selected) selected - row
                            else if (selected.size < FujiProps.SLOT_COUNT) selected + row
                            else selected
                    },
                    onEdit = { editing = Editing.Existing(row) },
                    onInstall = { installing = row },
                    onDuplicate = { viewModel.duplicateRecipe(row) },
                )
            }
            if (selected.isNotEmpty()) {
                item {
                    Column {
                        OutlinedTextField(
                            value = packName,
                            onValueChange = { packName = it },
                            label = { Text("Set name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = fujiFieldColors(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = packName.isNotBlank(),
                            onClick = {
                                // Order follows the library, not the order they
                                // were ticked in, so the slots they land in are
                                // the ones shown on screen.
                                val pack = RecipePack(
                                    packName,
                                    recipes.filter { it in selected },
                                )
                                // savePack replaces by name without a word.
                                if (packs.any { it.name == pack.name }) {
                                    replacingPack = pack
                                } else {
                                    viewModel.savePack(pack)
                                    packName = ""
                                    selected = emptySet()
                                    madeSet()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Film.Accent,
                                contentColor = Film.Background,
                            ),
                        ) { Text("Make a set of ${selected.size}") }
                        if (packName.isBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("THE SET NEEDS A NAME", style = LabelCaps)
                        }
                    }
                }
            }
        }
    }

    installing?.let { recipe ->
        InstallSlotDialog(
            recipe = recipe,
            onInstall = { slot ->
                viewModel.stageSingle(recipe, slot)
                installing = null
            },
            onCancel = { installing = null },
        )
    }

    // Deleting a set has no way back, so it asks — the same treatment deleting
    // a backup already gets.
    deletingPack?.let { pack ->
        FujiDialog(
            title = "Delete “${pack.name}”?",
            subtitle = "Only the grouping goes — the ${pack.recipes.size} recipes stay.",
            onDismiss = { deletingPack = null },
            cancel = DialogAction("Keep it") { deletingPack = null },
            confirm = DialogAction("Delete") {
                viewModel.deletePack(pack.name)
                deletingPack = null
            },
            destructive = true,
        )
    }

    replacingPack?.let { pack ->
        FujiDialog(
            title = "Replace the set “${pack.name}”?",
            subtitle = "That name is taken. Saving replaces it with these " +
                "${pack.recipes.size} recipes.",
            onDismiss = { replacingPack = null },
            cancel = DialogAction("Cancel") { replacingPack = null },
            confirm = DialogAction("Replace") {
                viewModel.savePack(pack)
                packName = ""
                selected = emptySet()
                replacingPack = null
                madeSet()
            },
            destructive = true,
        )
    }
}

/** Choosing which bank a single recipe should land in. */
@Composable
private fun InstallSlotDialog(
    recipe: Recipe,
    onInstall: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var slot by remember { mutableStateOf(1) }
    FujiDialog(
        title = "Install “${recipe.name.ifEmpty { "unnamed" }}”",
        subtitle = "One bank. The other six are left alone.",
        onDismiss = onCancel,
        cancel = DialogAction("Cancel", onClick = onCancel),
        confirm = DialogAction("Continue") { onInstall(slot) },
    ) {
        SlotPicker(selected = setOf(slot), onToggle = { slot = it })
    }
}

@Composable
private fun PackCard(pack: RecipePack, onInstall: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Film.Surface)
            .border(1.dp, Film.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            pack.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Film.TextPrimary,
        )
        Text(
            "${pack.recipes.size} recipes",
            style = MaterialTheme.typography.bodySmall,
            color = Film.TextMuted,
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            pack.recipes.forEach { recipe ->
                Box(
                    Modifier
                        .size(width = 38.dp, height = 28.dp)
                        .clip(RoundedCornerShape(3.dp))
                ) {
                    RecipeFrame(recipe, Modifier.fillMaxSize(), width = 38.dp, showCurve = false)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onInstall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Film.Accent,
                    contentColor = Film.Background,
                ),
            ) { Text("Install in camera") }
            TextButton(onClick = onDelete) { Text("Delete", color = Film.TextMuted) }
        }
    }
}

@Composable
private fun RecipeRow(
    recipe: Recipe,
    checked: Boolean,
    canCheck: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onInstall: () -> Unit,
    onDuplicate: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) Film.SurfaceRaised else Film.Surface)
            .border(
                1.dp,
                if (checked) Film.Accent else Film.Outline,
                RoundedCornerShape(10.dp),
            )
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 52.dp, height = 38.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onEdit)
            ) {
                RecipeFrame(recipe, Modifier.fillMaxSize(), width = 52.dp, showCurve = false)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text(
                    recipe.name.ifEmpty { "unnamed" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Film.TextPrimary,
                )
                Text(
                    recipe.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextMuted,
                )
            }
            TextButton(onClick = onToggle, enabled = canCheck) {
                Text(
                    if (checked) "In set" else "To set",
                    color = when {
                        checked -> Film.Accent
                        canCheck -> Film.TextSecondary
                        else -> Film.TextMuted
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Text("Edit", color = Film.TextSecondary) }
            TextButton(onClick = onDuplicate) { Text("Duplicate", color = Film.TextSecondary) }
            TextButton(onClick = onInstall) { Text("Install alone", color = Film.Accent) }
        }
    }
}

/** What the parser understood, and what it did not. Nothing is dropped quietly. */
@Composable
private fun ParsePreview(result: TextRecipeParser.Result) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Film.Surface)
            .border(1.dp, Film.Outline, RoundedCornerShape(12.dp)),
    ) {
        Box(Modifier.fillMaxWidth().height(90.dp)) {
            RecipeArtwork(result.recipe, Modifier.fillMaxSize())
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                result.recipe.summary(),
                fontWeight = FontWeight.SemiBold,
                color = Film.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))

            val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            result.recognized.forEach {
                Text("✓ ${it.line}", style = mono, color = Film.Good)
            }
            result.ignored.forEach {
                Text("• $it", style = mono, color = Film.Warn)
            }
            if (result.ignored.isNotEmpty()) {
                Text(
                    "A bank cannot hold these — kept as a note, set them on the camera.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Film.TextMuted,
                )
            }
            result.unrecognized.forEach {
                Text("? $it", style = mono, color = Film.Bad)
            }
            if (result.unrecognized.isNotEmpty()) {
                Text(
                    "Not understood — check these against the original.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Film.TextMuted,
                )
            }
        }
    }
}
