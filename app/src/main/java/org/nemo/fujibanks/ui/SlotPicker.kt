package org.nemo.fujibanks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.FujiProps

/**
 * A row of C1–C7 chips.
 *
 * Used wherever an operation can apply to some banks rather than all of them:
 * backing up a few, restoring one out of a backup, choosing where a single
 * recipe lands.
 */
@Composable
fun SlotPicker(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Set<Int> = (1..FujiProps.SLOT_COUNT).toSet(),
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (slot in 1..FujiProps.SLOT_COUNT) {
            val on = slot in selected
            val available = slot in enabled
            Text(
                "C$slot",
                style = ValueMono.copy(
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        !available -> Film.TextMuted
                        on -> Film.Background
                        else -> Film.TextSecondary
                    },
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) Film.Accent else Film.Surface)
                    .border(
                        1.dp,
                        if (on) Film.Accent else Film.Outline,
                        RoundedCornerShape(7.dp),
                    )
                    // Toggleable, so "C3, ticked" is what gets announced —
                    // being in or out of the set is the whole point of the row.
                    .toggleable(
                        value = on,
                        enabled = available,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(slot) },
                    )
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }
    }
}
