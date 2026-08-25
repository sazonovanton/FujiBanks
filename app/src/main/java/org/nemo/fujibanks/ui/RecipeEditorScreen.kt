package org.nemo.fujibanks.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.fuji.Recipe

/**
 * Building a recipe by hand, with no camera involved.
 *
 * The artwork at the top is redrawn from the values as they move, so the effect
 * of a change is visible while making it rather than after installing it. The
 * controls themselves are the shared ones in [recipeControls], limited to what
 * the camera actually accepts, so nothing can be built here that cannot be
 * written.
 */
@Composable
fun RecipeEditorScreen(
    viewModel: BanksViewModel,
    initial: Recipe,
    onSave: (Recipe) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var recipe by rememberSaveable(stateSaver = RecipeSaver) { mutableStateOf(initial) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Live portrait of whatever the controls currently say — or the sample
        // frame, once there is one to show instead.
        item {
            Box(Modifier.fillMaxWidth().height(150.dp)) {
                RecipeFrame(recipe, Modifier.fillMaxSize(), width = 400.dp)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.6f to Color.Transparent,
                            1f to Film.Background.copy(alpha = 0.92f),
                        )
                    )
                )
                Text(
                    recipe.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                )
                // Said plainly, because the two things in this box are not the
                // same kind of claim: one is a photograph someone took, the
                // other is a drawing of the numbers.
                Text(
                    if (recipe.photoId != null) "SAMPLE FRAME" else "COMPUTED FROM THE SETTINGS",
                    style = LabelCaps,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                )
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                PhotoAttachRow(recipe.photoId, viewModel) { id ->
                    recipe = recipe.copy(photoId = id)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "A frame shot with this recipe, kept on the phone and shrunk to " +
                        "preview size. It is not written to the camera.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextMuted,
                )
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
            }
        }

        recipeControls(recipe, onChange = { recipe = it })

        item {
            EditorBlock("NOTES") {
                Text(
                    "ISO, exposure compensation, anything a bank cannot hold.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = recipe.notes,
                    onValueChange = { recipe = recipe.copy(notes = it) },
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = fujiFieldColors(),
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onSave(recipe) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Film.Accent,
                        contentColor = Film.Background,
                    ),
                ) { Text("Save") }
                TextButton(onClick = onCancel) { Text("Cancel", color = Film.TextSecondary) }
                Spacer(Modifier.weight(1f))
                onDelete?.let {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text("Delete", color = Film.Bad)
                    }
                }
            }
        }
    }

    // A single tap used to delete the recipe outright, with nothing to undo it.
    if (confirmingDelete && onDelete != null) {
        FujiDialog(
            title = "Delete “${recipe.name.ifEmpty { "unnamed" }}”?",
            subtitle = "Out of the library for good. The camera is not touched.",
            onDismiss = { confirmingDelete = false },
            cancel = DialogAction("Keep it") { confirmingDelete = false },
            confirm = DialogAction("Delete") {
                confirmingDelete = false
                onDelete()
            },
            destructive = true,
        )
    }
}
