package org.nemo.fujibanks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.BankSnapshot
import org.nemo.fujibanks.fuji.FujiProps

/**
 * Copies of what the camera held, and the way back to any of them.
 *
 * A backup is taken automatically before every install, so this screen is
 * mostly for taking one deliberately and for going back further than the last
 * change.
 */
@Composable
fun BackupScreen(viewModel: BanksViewModel) {
    val backups by viewModel.backups.collectAsState()
    val banks by viewModel.banks.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var confirming by remember { mutableStateOf<BankSnapshot?>(null) }
    var deleting by remember { mutableStateOf<BankSnapshot?>(null) }
    var backupSlots by rememberSaveable { mutableStateOf((1..FujiProps.SLOT_COUNT).toSet()) }
    // Which slots a restore should touch, per snapshot. Defaults to all of them.
    var restoreSlots by remember { mutableStateOf(setOf<Int>()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    "Back up the camera",
                    style = MaterialTheme.typography.titleMedium,
                    color = Film.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Saves all seven banks as they stand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                SlotPicker(
                    selected = backupSlots,
                    onToggle = { slot ->
                        backupSlots =
                            if (slot in backupSlots) backupSlots - slot else backupSlots + slot
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (backupSlots.size == FujiProps.SLOT_COUNT) "All seven banks"
                    else "Only " + backupSlots.sorted().joinToString(", ") { "C$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextMuted,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    // Off while a read is in flight: two taps used to mean two
                    // coroutines and two near-identical files.
                    enabled = backupSlots.isNotEmpty() && busy == null,
                    onClick = { viewModel.backupNow(backupSlots) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Film.Accent,
                        contentColor = Film.Background,
                        disabledContainerColor = Film.SurfaceRaised,
                        disabledContentColor = Film.TextMuted,
                    ),
                ) { Text("Back up now") }
                if (backupSlots.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("PICK AT LEAST ONE BANK", style = LabelCaps)
                }
                // Reserved rather than conditional: this note vanishing the
                // moment a read succeeds used to pull the whole list up.
                AnimatedVisibility(banks.isEmpty()) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Connect the camera first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Film.Warn,
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            BlockLabel(if (backups.isEmpty()) "SAVED" else "SAVED (${backups.size})")
        }
        if (backups.isEmpty()) {
            item {
                EmptyNote(
                    title = "No backups yet",
                    body = "Take one before the first write. Installing a set takes " +
                        "its own backup first and offers Undo, so this list fills " +
                        "itself as you go — but a snapshot of untouched banks is " +
                        "the only way back to what the camera shipped with.",
                )
            }
        } else {
            items(backups, key = { it.takenAt }) { snapshot ->
                BackupCard(
                    snapshot = snapshot,
                    onRestore = {
                        restoreSlots = snapshot.banks.map { it.slot }.toSet()
                        confirming = snapshot
                    },
                    onDelete = { deleting = snapshot },
                )
            }
        }
    }

    confirming?.let { snapshot ->
        FujiDialog(
            title = "Restore this backup?",
            subtitle = "Saved ${formatWhen(snapshot.takenAt)}. Backed up first, " +
                "so this is reversible.",
            // That sentence is only true because restoreInternal snapshots the
            // banks before it writes and the report then offers Undo.
            onDismiss = { confirming = null },
            cancel = DialogAction("Cancel") { confirming = null },
            confirm = DialogAction("Restore", enabled = restoreSlots.isNotEmpty()) {
                viewModel.restoreBackup(snapshot, restoreSlots)
                confirming = null
            },
        ) {
            BlockLabel("RESTORE WHICH BANKS")
            Spacer(Modifier.height(8.dp))
            val available = snapshot.banks.map { it.slot }.toSet()
            SlotPicker(
                selected = restoreSlots,
                onToggle = { slot ->
                    restoreSlots =
                        if (slot in restoreSlots) restoreSlots - slot else restoreSlots + slot
                },
                enabled = available,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Banks left unticked keep what they hold now.",
                style = MaterialTheme.typography.labelSmall,
                color = Film.TextMuted,
            )
        }
    }

    // Deleting a backup is the one thing on this screen that cannot be undone —
    // it used to happen on a single tap, next to Restore.
    deleting?.let { snapshot ->
        FujiDialog(
            title = "Delete this backup?",
            subtitle = "Saved ${formatWhen(snapshot.takenAt)}. Gone for good — " +
                "the camera is not touched.",
            onDismiss = { deleting = null },
            cancel = DialogAction("Keep") { deleting = null },
            confirm = DialogAction("Delete") {
                viewModel.deleteBackup(snapshot.takenAt)
                deleting = null
            },
            destructive = true,
        )
    }
}

@Composable
private fun BackupCard(snapshot: BankSnapshot, onRestore: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Film.Surface)
            .border(1.dp, Film.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            formatWhen(snapshot.takenAt),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Film.TextPrimary,
        )
        Text(
            buildString {
                append(snapshot.cameraModel)
                append(" · ")
                if (snapshot.banks.size == FujiProps.SLOT_COUNT) append("all seven banks")
                else append(snapshot.banks.sortedBy { it.slot }
                    .joinToString(", ") { "C${it.slot}" })
            },
            style = MaterialTheme.typography.bodySmall,
            color = Film.TextMuted,
        )

        Spacer(Modifier.height(10.dp))

        // The banks as they were, as a row of thumbnails.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            snapshot.banks.sortedBy { it.slot }.forEach { bank ->
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(width = 34.dp, height = 26.dp)
                            .clip(RoundedCornerShape(3.dp))
                    ) {
                        RecipeArtwork(bank.recipe, Modifier.fillMaxSize(), showCurve = false)
                    }
                    Text("C${bank.slot}", style = LabelCaps)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRestore) { Text("Restore") }
            TextButton(onClick = onDelete) { Text("Delete", color = Film.TextMuted) }
        }
    }
}


