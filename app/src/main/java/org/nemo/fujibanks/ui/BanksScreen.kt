package org.nemo.fujibanks.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.BankRead
import org.nemo.fujibanks.fuji.FujiProps

/**
 * What is in the camera right now.
 *
 * The slot rail is a strip of film: seven frames, each showing the artwork its
 * recipe generates. Picking one opens it below. Nothing here writes to the
 * camera — installing recipes lives on its own screen.
 */
@Composable
fun BanksScreen(viewModel: BanksViewModel) {
    val banks by viewModel.banks.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var selected by rememberSaveable { mutableIntStateOf(1) }
    val current = banks.firstOrNull { it.slot == selected }
    var saving by remember { mutableStateOf<BankRead?>(null) }
    var savingSet by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { FilmStripRail(banks, selected) { selected = it } }

        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // One USB session, one operation at a time: a second tap while
                // the first read is in flight would queue a second sweep of all
                // seven slots against a camera already answering.
                Button(
                    onClick = viewModel::readBanks,
                    enabled = busy == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Film.Accent,
                        contentColor = Film.Background,
                        disabledContainerColor = Film.SurfaceRaised,
                        disabledContentColor = Film.TextMuted,
                    ),
                ) { Text("Read from camera") }
                // The busy label used to be repeated here. The strip at the top
                // of the app already carries it, and the same sentence twice on
                // one screen reads as two things happening.

                // Whatever is in the camera right now, kept as one set. This is
                // how you get back to a bank layout you liked without picking
                // the seven recipes out of the library one at a time.
                OutlinedButton(
                    onClick = { savingSet = true },
                    enabled = busy == null && banks.isNotEmpty(),
                ) { Text("Save all as a set") }
            }
        }

        item {
            Box(Modifier.padding(horizontal = 16.dp)) {
                if (current == null) EmptySlotNote(selected, banks.isEmpty())
                else BankDetail(current, onSaveToLibrary = { saving = current })
            }
        }
    }

    if (savingSet) {
        SaveSetDialog(
            count = banks.size,
            onConfirm = { name ->
                viewModel.packFromCurrentBanks(name)
                savingSet = false
            },
            onCancel = { savingSet = false },
        )
    }

    saving?.let { bank ->
        SaveBankDialog(
            bank = bank,
            onConfirm = { name ->
                viewModel.saveBankAsRecipe(bank.slot, name)
                saving = null
            },
            onCancel = { saving = null },
        )
    }
}

/** Naming the seven banks on their way into the library as one set. */
@Composable
private fun SaveSetDialog(count: Int, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    FujiDialog(
        title = "Save the banks as a set",
        subtitle = "$count bank${if (count == 1) "" else "s"} as they are now, in slot " +
            "order. Keeps a copy on the phone; the camera is not touched.",
        onDismiss = onCancel,
        cancel = DialogAction("Cancel", onClick = onCancel),
        confirm = DialogAction("Save", enabled = name.isNotBlank()) { onConfirm(name) },
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Set name") },
            singleLine = true,
            colors = fujiFieldColors(),
        )
    }
}

/** Naming a bank on its way into the library. */
@Composable
private fun SaveBankDialog(bank: BankRead, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember {
        mutableStateOf(bank.recipe.name.ifEmpty { "C${bank.slot}" })
    }
    FujiDialog(
        title = "Save C${bank.slot} to the library",
        subtitle = "Keeps a copy on the phone. The camera is not touched.",
        onDismiss = onCancel,
        cancel = DialogAction("Cancel", onClick = onCancel),
        confirm = DialogAction("Save", enabled = name.isNotBlank()) { onConfirm(name) },
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            colors = fujiFieldColors(),
        )
    }
}

// -- The rail ---------------------------------------------------------------

/**
 * Seven frames on a strip, sprocket holes and all.
 *
 * An unread slot keeps its frame rather than disappearing: the camera always
 * has seven, and a gap would read as a missing bank.
 */
@Composable
private fun FilmStripRail(banks: List<BankRead>, selected: Int, onSelect: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Film.StripBase)
            .padding(vertical = 2.dp),
    ) {
        // Seven 76dp frames are wider than any phone, so C5-C7 start off
        // screen. A LazyRow can be told to bring the chosen one into view;
        // a plain horizontal scroll cannot, and the far slots stayed hidden.
        val railState = rememberLazyListState()
        LaunchedEffect(selected) { railState.animateScrollToItem((selected - 1).coerceAtLeast(0)) }
        LazyRow(
            state = railState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(FujiProps.SLOT_COUNT) { index ->
                val slot = index + 1
                FilmFrame(
                    slot = slot,
                    bank = banks.firstOrNull { it.slot == slot },
                    selected = slot == selected,
                    onClick = { onSelect(slot) },
                )
            }
        }
    }
}

@Composable
private fun FilmFrame(slot: Int, bank: BankRead?, selected: Boolean, onClick: () -> Unit) {
    val frameWidth = 76.dp
    val frameHeight = 104.dp

    Box(
        Modifier
            .size(frameWidth, frameHeight)
            .clickable(onClick = onClick),
    ) {
        // The strip itself: perforated edges above and below the image gate.
        Canvas(Modifier.fillMaxSize()) {
            drawSprockets(top = true, color = Film.Background)
            drawSprockets(top = false, color = Film.Background)
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .border(
                        width = if (selected) 1.5.dp else 0.5.dp,
                        color = if (selected) Film.Accent else Film.Outline,
                        shape = RoundedCornerShape(3.dp),
                    ),
            ) {
                if (bank != null) {
                    RecipeArtwork(bank.recipe, Modifier.fillMaxSize(), showCurve = false)
                } else {
                    Box(Modifier.fillMaxSize().background(Film.Surface))
                }
            }

            Spacer(Modifier.height(3.dp))
            Text(
                "C$slot",
                style = LabelCaps.copy(
                    color = when {
                        selected -> Film.Accent
                        bank == null -> Film.TextMuted
                        else -> Film.TextSecondary
                    },
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}

// -- The open bank ----------------------------------------------------------

@Composable
private fun BankDetail(bank: BankRead, onSaveToLibrary: () -> Unit) {
    val r = bank.recipe

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Film.Surface)
            .border(1.dp, Film.Outline, RoundedCornerShape(14.dp)),
    ) {
        // The artwork as a header, with the name laid over its darker edge.
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            RecipeArtwork(r, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Film.Background.copy(alpha = 0.92f),
                        )
                    )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(
                    "C${bank.slot}",
                    style = LabelCaps.copy(color = Film.Accent, fontWeight = FontWeight.Bold),
                )
                Text(
                    r.name.ifEmpty { "unnamed" }.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    r.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }

        Column(Modifier.padding(16.dp)) {
            BlockLabel("SETTINGS")
            r.enumRows().forEach { (label, value) ->
                EnumRow(label, value, emphasis = label == "Simulation")
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Film.Outline)
            Spacer(Modifier.height(14.dp))

            BlockLabel("ADJUSTMENTS")
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                r.numericParams().forEach { ParamBar(it) }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onSaveToLibrary) {
                Text("Save to library")
            }

            if (r.notes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Film.Outline)
                Spacer(Modifier.height(14.dp))
                BlockLabel("NOT STORED IN THE BANK")
                Text(
                    r.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun EmptySlotNote(slot: Int, nothingRead: Boolean) {
    EmptyNote(
        title = if (nothingRead) "Nothing read yet" else "C$slot was not read",
        body = "Set CONNECTION MODE on the camera to USB RAW CONV./BACKUP RESTORE, " +
            "connect it, then read.",
    )
}
