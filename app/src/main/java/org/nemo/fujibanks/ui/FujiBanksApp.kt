package org.nemo.fujibanks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Height of the progress strip, reserved whether or not it has anything to show. */
private val BusyBarHeight = 26.dp

/**
 * Height of the status row, reserved the same way.
 *
 * Its Connect/Retry button exists only when there is something to connect to,
 * and the camera's controls lock while it is plugged in — so a hotplug is a
 * normal part of using this app, and it must not shove the page up and down.
 */
private val StatusBarHeight = 40.dp

private enum class Section(val label: String) {
    CAMERA("Camera"),
    RECIPES("Recipes"),
    DARKROOM("Develop"),
    PHOTOS("Photos"),
    BACKUP("Backup"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FujiBanksApp(viewModel: BanksViewModel) {
    var section by rememberSaveable { mutableStateOf(Section.CAMERA) }
    var showDebug by rememberSaveable { mutableStateOf(false) }

    val cameraState by viewModel.cameraState.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val report by viewModel.report.collectAsState()
    val dump by viewModel.dump.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val cameraNotice by viewModel.cameraNotice.collectAsState()

    // Debug has no bottom bar of its own, so Back is the only way out that does
    // not require knowing about the long press on the title.
    BackHandler(enabled = showDebug) { showDebug = false }

    val snackbars = remember { SnackbarHostState() }
    // Collected once, for the life of the screen: notices are a queue, and each
    // one is shown to the end before the next starts. Keyed on a value instead,
    // a burst used to cancel its own predecessors mid-sentence.
    LaunchedEffect(Unit) {
        viewModel.notices.collect { snackbars.showSnackbar(it) }
    }

    Scaffold(
        containerColor = Film.Background,
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(
                    containerColor = Film.SurfaceRaised,
                    contentColor = Film.TextPrimary,
                    snackbarData = data,
                )
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        // Long-pressing the title is the way into the protocol
                        // tools. They are for one person on one unverified
                        // camera, not for the app's actual job.
                        Text(
                            if (showDebug) "Debug" else "FujiBanks",
                            fontWeight = FontWeight.SemiBold,
                            // Long press only. A `combinedClickable` here would
                            // need an `onClick` too, and an empty one swallows
                            // every tap on the title to do nothing at all.
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { showDebug = !showDebug })
                            },
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Film.Background,
                        titleContentColor = Film.TextPrimary,
                    ),
                )
                CameraStatusBar(cameraState, onConnect = viewModel::findAndConnect)
                // The busy strip keeps its height whether or not anything is
                // running: it sits above every screen, and letting it appear
                // and vanish shoved the whole page down and back on each
                // render — the one thing that must hold still while a slider
                // is being watched.
                Column(
                    Modifier.fillMaxWidth().height(BusyBarHeight),
                    verticalArrangement = Arrangement.Center,
                ) {
                    busy?.let {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Film.Accent,
                            trackColor = Film.Outline,
                        )
                        Text(
                            it.label,
                            style = LabelCaps,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!showDebug) {
                NavigationBar(containerColor = Film.Surface) {
                    Section.entries.forEach { s ->
                        NavigationBarItem(
                            selected = section == s,
                            onClick = { section = s },
                            icon = {
                                Icon(
                                    when (s) {
                                        Section.CAMERA -> Icons.Default.CameraAlt
                                        Section.RECIPES -> Icons.Default.Style
                                        Section.DARKROOM -> Icons.Default.Photo
                                        Section.PHOTOS -> Icons.Default.PhotoLibrary
                                        Section.BACKUP -> Icons.Default.Save
                                    },
                                    contentDescription = s.label,
                                )
                            },
                            label = { Text(s.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Film.Accent,
                                selectedTextColor = Film.Accent,
                                unselectedIconColor = Film.TextMuted,
                                unselectedTextColor = Film.TextMuted,
                                indicatorColor = Film.SurfaceRaised,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                showDebug -> DebugScreen(viewModel)
                section == Section.CAMERA -> BanksScreen(viewModel)
                section == Section.RECIPES -> RecipesScreen(viewModel)
                section == Section.DARKROOM -> DarkroomScreen(viewModel)
                section == Section.PHOTOS -> PhotosScreen(viewModel)
                section == Section.BACKUP -> BackupScreen(viewModel)
            }
        }
    }

    // Over everything, including the dialogs: it is the first thing drawn and
    // it goes away on its own. Saved, so a rotation does not replay it.
    var intro by rememberSaveable { mutableStateOf(true) }
    if (intro) SplashOverlay { intro = false }

    // First, and on its own: it gates the connection the others all assume.
    // It also waits for the intro, so the first thing on screen is the mark and
    // not a wall of text.
    if (cameraNotice && !intro) {
        CameraNoticeDialog(viewModel::acceptCameraNotice, viewModel::declineCameraNotice)
    }

    pending?.let {
        WriteDiffDialog(it, viewModel::confirmPendingWrite, viewModel::cancelPendingWrite)
    }
    report?.let { WriteReportDialog(it, viewModel::undoLastWrite, viewModel::dismissReport) }
    dump?.let { DumpDialog(it, viewModel::dismissDump) }
}

@Composable
private fun CameraStatusBar(state: CameraState, onConnect: () -> Unit) {
    val (text, action) = when (state) {
        is CameraState.NoDevice -> "No camera connected" to "Connect"
        is CameraState.NeedsPermission -> "Waiting for USB access" to null
        is CameraState.Connecting -> "Connecting…" to null
        // Card-reader mode is a working camera, just not one with banks in it.
        // The bank screens explain themselves; the status bar only states which
        // mode is on, and stays out of the warning colour.
        is CameraState.Connected ->
            if (state.banks) "${state.model} · fw ${state.firmware}" to null
            else "${state.model} · fw ${state.firmware} · card reader, no banks" to null
        is CameraState.Failed -> state.message to "Retry"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(StatusBarHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (state is CameraState.Failed) Film.Bad else Film.TextSecondary,
            // A PTP error can be a paragraph. It gets one line here and the
            // whole of it in the log; growing this row moves the whole app.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = onConnect) { Text(action, color = Film.Accent) }
        }
    }
}

@Composable
private fun WriteReportDialog(report: WriteReport, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val bad = report.results.filterNot { it.ok }

    FujiDialog(
        title = if (report.ok) "Installed and verified" else "Installed with problems",
        subtitle = if (report.ok) {
            // Seven lines all saying "ok" is the same sentence seven times.
            val slots = report.results.map { "C${it.slot}" }.joinToString(" ")
            "$slots — read back and matched."
        } else {
            "${bad.size} of ${report.results.size} did not match. The rest went in."
        },
        onDismiss = onDismiss,
        // Undo is the only way back from a seven-bank overwrite and it lives
        // nowhere else, so a tap beside the dialog must not take it away.
        dismissOnOutside = false,
        // Undo is a second write to the camera, so it stays the quiet action —
        // it is a way out, not the way forward.
        cancel = if (report.undoAvailable) {
            DialogAction("Undo") { onUndo(); onDismiss() }
        } else {
            null
        },
        confirm = DialogAction("Done", onClick = onDismiss),
        // Nothing to list when everything matched: the subtitle already said so.
        content = if (bad.isEmpty()) null else {
            {
                bad.forEach { res ->
                    Text(
                        "C${res.slot}",
                        style = ValueMono,
                        fontWeight = FontWeight.Bold,
                        color = Film.Warn,
                    )
                    res.rejected.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Film.TextMuted,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DumpDialog(text: String, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    FujiDialog(
        title = "Property dump",
        subtitle = "${text.lineSequence().count()} lines, straight off the camera.",
        onDismiss = onDismiss,
        extras = listOf(
            DialogAction("Share") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                context.startActivity(android.content.Intent.createChooser(send, "Share dump"))
            },
            DialogAction("Copy") {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            },
        ),
        confirm = DialogAction("Close", onClick = onDismiss),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Film.TextSecondary,
        )
    }
}
