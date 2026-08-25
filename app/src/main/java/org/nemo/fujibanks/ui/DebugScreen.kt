package org.nemo.fujibanks.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The protocol tools, kept out of the way.
 *
 * These exist because the bank property map is confirmed on one camera model
 * and guessed everywhere else. They speak in property codes and raw bytes, and
 * none of them belongs in front of someone who just wants to load a recipe.
 */
@Composable
fun DebugScreen(viewModel: BanksViewModel) {
    val log by viewModel.log.collectAsState()
    val cameraState by viewModel.cameraState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val listState = rememberLazyListState()
    // The log is the last item; a sweep writes to it for minutes, and reading
    // the newest line meant scrolling down by hand each time.
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Protocol tools",
                style = MaterialTheme.typography.titleMedium,
                color = Film.TextPrimary,
            )
            Text(
                when (val s = cameraState) {
                    is CameraState.Connected -> "${s.model} · fw ${s.firmware} · ${s.serial}"
                    else -> "not connected"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Film.TextMuted,
            )
        }

        item {
            ToolBlock(
                title = "Inspect",
                hint = "Read every property the camera advertises, and the seven bank " +
                    "slots one by one.",
            ) {
                OutlinedButton(
                    onClick = viewModel::dumpBanks,
                    enabled = busy == null,
                ) { Text("Property dump") }
                OutlinedButton(
                    onClick = viewModel::dumpStorage,
                    enabled = busy == null,
                ) { Text("Objects") }
                OutlinedButton(
                    onClick = viewModel::dumpProfile,
                    enabled = busy == null,
                ) { Text("RAW profile") }
            }
        }

        item {
            ToolBlock(
                title = "Find a property",
                hint = "Sweep D000–D3FF, change one setting on the camera, sweep again. " +
                    "The baseline is kept on disk, so unplugging is fine.",
            ) {
                OutlinedButton(
                    onClick = viewModel::captureBaseline,
                    enabled = busy == null,
                ) { Text("Baseline") }
                OutlinedButton(
                    onClick = viewModel::diffAgainstBaseline,
                    enabled = busy == null,
                ) { Text("Diff") }
            }
        }

        item {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BlockLabel("LOG")
                    // This is the only record of what the camera said. A dump
                    // can be copied and shared; the log could not leave the
                    // phone at all, which is backwards.
                    if (log.isNotEmpty()) {
                        val text = log.joinToString("\n")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(text))
                            }) { Text("Copy", style = LabelCaps.copy(color = Film.Accent)) }
                            TextButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(send, "Share log"))
                            }) { Text("Share", style = LabelCaps.copy(color = Film.Accent)) }
                        }
                    }
                }
                Text(
                    if (log.isEmpty()) "empty" else log.takeLast(40).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Film.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun ToolBlock(
    title: String,
    hint: String,
    buttons: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Film.Surface)
            .border(1.dp, Film.Outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        BlockLabel(title.uppercase())
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Film.TextMuted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}
