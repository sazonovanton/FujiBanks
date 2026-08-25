package org.nemo.fujibanks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * One dialog for the whole app.
 *
 * Every modal had been assembling itself: its own container colour, its own
 * idea of whether the confirming action was a filled button or a bare word, its
 * own answer to what happens when the content is longer than the screen. They
 * are the moments where the camera is about to be written to, so they are
 * exactly the wrong place for six slightly different layouts.
 *
 * The shape here is fixed: a title, an optional line saying what the thing
 * costs, scrolling content that cannot push the buttons off the bottom, and
 * actions that read in one order — the one that proceeds is filled and last.
 */

/** A button in a dialog's action row. */
data class DialogAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FujiDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: DialogAction? = null,
    cancel: DialogAction? = null,
    /** Actions that neither proceed nor back out — Copy, Share. */
    extras: List<DialogAction> = emptyList(),
    /** What this will do to the camera or to a saved file, in one line. */
    subtitle: String? = null,
    /** Colours the confirming button as a warning: it destroys something. */
    destructive: Boolean = false,
    /**
     * False when the dialog carries something that cannot be got back once it
     * is gone — an Undo offered nowhere else. A tap beside the dialog is too
     * cheap a way to lose that.
     */
    dismissOnOutside: Boolean = true,
    /** Omitted entirely when the title and subtitle already say everything. */
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = dismissOnOutside),
        containerColor = Film.Surface,
        titleContentColor = Film.TextPrimary,
        textContentColor = Film.TextSecondary,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Film.TextPrimary)
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Film.TextMuted,
                    )
                }
            }
        },
        // Capped and scrollable in one place: a property dump and a seven-slot
        // diff are both taller than the screen, and neither may push the
        // buttons out of reach.
        text = content?.let {
            {
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    content = it,
                )
            }
        },
        // Everything goes in one slot so three actions wrap instead of
        // colliding, and so the order is the same in every dialog.
        confirmButton = {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                extras.forEach { SecondaryAction(it, Film.TextMuted) }
                cancel?.let { SecondaryAction(it, Film.TextSecondary) }
                confirm?.let { PrimaryAction(it, destructive) }
            }
        },
    )
}

@Composable
private fun PrimaryAction(action: DialogAction, destructive: Boolean) {
    Button(
        onClick = action.onClick,
        enabled = action.enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) Film.Bad else Film.Accent,
            contentColor = Film.Background,
            disabledContainerColor = Film.SurfaceRaised,
            disabledContentColor = Film.TextMuted,
        ),
    ) { Text(action.label) }
}

@Composable
private fun SecondaryAction(action: DialogAction, color: Color) {
    TextButton(onClick = action.onClick, enabled = action.enabled) {
        Text(action.label, color = if (action.enabled) color else Film.TextMuted)
    }
}
