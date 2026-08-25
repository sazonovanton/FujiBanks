package org.nemo.fujibanks.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.FujiProps
import org.nemo.fujibanks.fuji.Recipe

/**
 * What a write would change, slot by slot, before anything is sent.
 *
 * Writing replaces up to seven banks in one go, which is exactly the operation
 * worth being sure about — but seven slots' worth of every changed parameter is
 * a wall taller than the phone, and a wall nobody reads confirms nothing. So a
 * slot is one line: where it goes, what lands there, how much moves. The detail
 * is one tap away, and there the outgoing value is still drawn as a ghost
 * behind the incoming one.
 */
@Composable
fun WriteDiffDialog(
    pending: PendingWrite,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val slots = pending.slots()
    // Every slot already holding what would be written: there is nothing to
    // send, and offering to send it anyway invites a pointless camera write.
    val anyChange = slots.any { (_, before, after) -> before == null || before != after }
    // One open at a time, so the dialog cannot grow past its cap.
    var open by remember { mutableStateOf<Int?>(null) }

    FujiDialog(
        title = "Install “${pending.title}”",
        subtitle = when {
            !anyChange -> "Every bank already holds this."
            slots.size == FujiProps.SLOT_COUNT -> "All seven banks. Backed up first."
            slots.size == 1 -> "One bank. Backed up first."
            else -> "${slots.size} banks. Backed up first."
        },
        onDismiss = onCancel,
        cancel = DialogAction("Cancel", onClick = onCancel),
        confirm = DialogAction("Install", enabled = anyChange, onClick = onConfirm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slots.forEach { (slot, before, after) ->
                SlotDiff(
                    slot = slot,
                    before = before,
                    after = after,
                    expanded = open == slot,
                    onToggle = { open = if (open == slot) null else slot },
                )
            }
        }
    }
}

@Composable
private fun SlotDiff(
    slot: Int,
    before: Recipe?,
    after: Recipe,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val oldRows = before?.enumRows()?.toMap() ?: emptyMap()
    val changedSettings =
        if (before == null) after.enumRows()
        else after.enumRows().filter { (label, value) -> oldRows[label] != value }

    val oldParams = before?.numericParams()?.associateBy { it.label } ?: emptyMap()
    val changedParams = after.numericParams().filter { p ->
        oldParams[p.label]?.value != p.value
    }
    val count = changedSettings.size + changedParams.size

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Film.SurfaceRaised)
            .clickable(enabled = count > 0, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "C$slot",
                style = ValueMono,
                fontWeight = FontWeight.Bold,
                color = if (count > 0) Film.Accent else Film.TextMuted,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                after.name.ifEmpty { "unnamed" }.uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (count > 0) Film.TextPrimary else Film.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    // A slot that was never read has nothing to compare
                    // against, so a count would be a count of nothing.
                    before == null -> "NOT READ"
                    count == 0 -> "NO CHANGE"
                    expanded -> "$count ▴"
                    else -> "$count ▾"
                },
                style = LabelCaps,
                color = if (count > 0) Film.Accent else Film.TextMuted,
            )
        }

        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                changedSettings.forEach { (label, value) ->
                    val was = oldRows[label]
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = Film.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (was != null) {
                            Text(was, style = ValueMono, color = Film.TextMuted)
                            Text("  →  ", style = ValueMono, color = Film.TextMuted)
                        }
                        Text(value, style = ValueMono, color = Film.TextPrimary)
                    }
                }

                if (changedParams.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        changedParams.forEach { p ->
                            ParamBar(p, ghost = oldParams[p.label])
                        }
                    }
                }
            }
        }
    }
}
