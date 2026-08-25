package org.nemo.fujibanks.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.nemo.fujibanks.usb.ObjectInfo

/**
 * Frames on the card, and copying them to the phone.
 *
 * Two destinations, because they are not the same job: JPEGs belong in the
 * gallery, and a RAF belongs in a folder the photographer picked — no gallery
 * app can do anything with one.
 *
 * This is the one screen that needs `USB CARD READER` rather than
 * `USB RAW CONV./BACKUP RESTORE` — in the conversion mode the card is not
 * exposed at all. Switching modes on the camera means unplugging it, so the
 * empty state says which mode is wanted instead of just showing nothing.
 */
@Composable
fun PhotosScreen(viewModel: BanksViewModel) {
    val photos by viewModel.photos.collectAsState()
    val thumbs by viewModel.thumbs.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val status by viewModel.photoStatus.collectAsState()
    var selected by rememberSaveable { mutableStateOf(setOf<Int>()) }
    // Only this screen's own work: a render on the Develop tab is not a card read.
    val working = busy?.scope == BusyScope.PHOTOS

    // Handles are the card's own identity, so a refresh that drops a frame must
    // drop it from the selection too — otherwise the count on screen promises
    // more files than the copy can find.
    LaunchedEffect(photos) {
        val live = photos.map { it.handle }.toSet()
        selected = selected intersect live
    }

    // Picking a folder is how a RAF gets somewhere useful — the gallery is the
    // wrong home for one, and "copied, but where?" is not an answer either.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree: Uri? -> if (tree != null) viewModel.savePhotosTo(selected, tree) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { viewModel.loadPhotos() },
                enabled = busy == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Film.Accent,
                    contentColor = Film.Background,
                    disabledContainerColor = Film.SurfaceRaised,
                    disabledContentColor = Film.TextMuted,
                ),
            ) { Text(if (photos.isEmpty()) "Read the card" else "Refresh") }

            if (photos.isNotEmpty()) {
                TextButton(onClick = {
                    selected = if (selected.size == photos.size) emptySet()
                    else photos.map { it.handle }.toSet()
                }) {
                    Text(
                        if (selected.size == photos.size) "None" else "All",
                        color = Film.TextSecondary,
                    )
                }
            }
        }

        if (photos.isEmpty()) {
            EmptyCardNote(working)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(photos, key = { it.handle }) { info ->
                    PhotoCell(
                        info = info,
                        thumb = thumbs[info.handle],
                        selected = info.handle in selected,
                        onClick = {
                            selected = if (info.handle in selected) selected - info.handle
                            else selected + info.handle
                        },
                    )
                }
            }

            // Ticking the first frame used to shrink the grid by the height of
            // this bar and reflow every cell, so the photo under the finger
            // moved. It slides in over the grid instead.
            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Film.Surface)
                        .padding(16.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                viewModel.clearPhotoStatus()
                                viewModel.downloadPhotos(selected)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Film.Accent,
                                contentColor = Film.Background,
                            ),
                        ) { Text("To gallery") }
                        OutlinedButton(onClick = {
                            viewModel.clearPhotoStatus()
                            folderPicker.launch(null)
                        }) { Text("Save to…") }
                        Text(
                            "${selected.size} · ${selectionSize(photos, selected)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Film.TextMuted,
                        )
                    }
                }
            }
            // A copy that says nothing afterwards is a copy nobody trusts; the
            // log is behind a long press and not the place. It sits outside the
            // action bar on purpose — inside, clearing the selection took the
            // answer away with it, and clearing the selection is the natural
            // thing to do once a copy has finished.
            AnimatedVisibility(
                visible = status != null,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Text(
                    status.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Film.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Film.Surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotoCell(
    info: ObjectInfo,
    thumb: ByteArray?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Film.Surface)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Film.Accent else Film.Surface,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick),
    ) {
        if (thumb != null) {
            val bitmap = remember(thumb) {
                android.graphics.BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
            }
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = info.filename,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        if (info.isRaw) {
            Text(
                "RAF",
                style = LabelCaps.copy(color = Film.TextPrimary),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Film.Background.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Film.Accent)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("✓", style = LabelCaps.copy(color = Film.Background))
            }
        }
    }
}

@Composable
private fun EmptyCardNote(working: Boolean) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        EmptyNote(
            title = if (working) "Reading…" else "Nothing read from the card",
            body = "Set CONNECTION MODE on the camera to USB CARD READER, " +
                "connect it, then read.",
        )
    }
}

private fun selectionSize(photos: List<ObjectInfo>, selected: Set<Int>): String {
    val bytes = photos.filter { it.handle in selected }.sumOf { it.compressedSize }
    val mb = bytes / 1024.0 / 1024.0
    return "%.0f MB".format(mb)
}
