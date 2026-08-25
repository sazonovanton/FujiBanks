package org.nemo.fujibanks.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nemo.fujibanks.data.BackupStore
import org.nemo.fujibanks.data.PackStore
import org.nemo.fujibanks.data.PhotoStore
import org.nemo.fujibanks.data.SaveResult
import org.nemo.fujibanks.fuji.Bank
import org.nemo.fujibanks.fuji.Codec
import org.nemo.fujibanks.fuji.BankRead
import org.nemo.fujibanks.fuji.BankSnapshot
import org.nemo.fujibanks.fuji.FujiCamera
import org.nemo.fujibanks.fuji.FujiProps
import org.nemo.fujibanks.fuji.Recipe
import org.nemo.fujibanks.fuji.RecipePack
import org.nemo.fujibanks.fuji.SlotWriteResult
import org.nemo.fujibanks.usb.ObjectInfo
import org.nemo.fujibanks.usb.UsbTransport
import org.nemo.fujibanks.usb.toHex

/** Where the camera connection currently stands. */
sealed interface CameraState {
    data object NoDevice : CameraState
    data class NeedsPermission(val device: UsbDevice) : CameraState
    data class Connecting(val device: UsbDevice) : CameraState
    /**
     * The camera answered and the session is open. [banks] says whether this
     * mode exposes the recipe properties: in USB CARD READER it does not, and
     * that is a working camera for the Photos screen, not a failure.
     */
    data class Connected(
        val model: String,
        val firmware: String,
        val serial: String,
        val banks: Boolean = true,
    ) : CameraState
    data class Failed(val message: String) : CameraState
}

/**
 * Which screen asked for the operation that is running.
 *
 * The camera is one serialized session, so the top strip shows every operation
 * whatever started it. A screen still has to know whether the work is *its*
 * work: Develop replaces its artwork with "Working…" while rendering, and doing
 * that because a backup is running on another tab says something untrue.
 */
enum class BusyScope { CAMERA, RECIPES, DEVELOP, PHOTOS, BACKUP, DEBUG }

/** A camera operation in flight: what it is doing, and who asked. */
data class Busy(val label: String, val scope: BusyScope)

/** Result of writing a whole pack, ready to be shown slot by slot. */
data class WriteReport(
    val results: List<SlotWriteResult>,
    val undoAvailable: Boolean,
) {
    val ok: Boolean get() = results.all { it.ok }
}

/**
 * A write that has been staged but not sent.
 *
 * Installing usually replaces several banks at once, so it is shown as a diff
 * first: what each slot holds now against what it would hold. Nothing reaches
 * the camera until this is confirmed.
 *
 * Slots are assigned explicitly rather than by position, so a single recipe can
 * be put into one chosen bank as easily as a set fills all seven.
 */
data class PendingWrite(
    val title: String,
    val assignments: List<Pair<Int, Recipe>>,
    val current: List<BankRead>,
) {
    /** Slot number, the recipe in it now, and the one replacing it. */
    fun slots(): List<Triple<Int, Recipe?, Recipe>> =
        assignments.sortedBy { it.first }.map { (slot, incoming) ->
            Triple(slot, current.firstOrNull { it.slot == slot }?.recipe, incoming)
        }
}

class BanksViewModel(app: Application) : AndroidViewModel(app) {

    private val camera = FujiCamera { appendLog(it) }
    private val backupStore = BackupStore(app)
    private val packs = PackStore(app)
    private val photoStore = PhotoStore(app)

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.NoDevice)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _banks = MutableStateFlow<List<BankRead>>(emptyList())
    val banks: StateFlow<List<BankRead>> = _banks.asStateFlow()

    private val _busy = MutableStateFlow<Busy?>(null)
    /** Non-null while a camera operation is in flight; the label says which. */
    val busy: StateFlow<Busy?> = _busy.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _notices = Channel<String>(Channel.BUFFERED)
    /**
     * One line for the person holding the phone, shown and then gone.
     *
     * The log is behind a long press on the title, which is right for property
     * codes and wrong for "the write failed". Anything a photographer can act on
     * comes through here as well as going to the log.
     *
     * A channel rather than a `StateFlow`, because notices arrive in bursts: a
     * copy of six frames that fails the same way six times has six things to
     * say, and a StateFlow holding the same string does not emit twice, so the
     * repeats vanished. Queued, each one waits its turn.
     */
    val notices: Flow<String> = _notices.receiveAsFlow()

    private val _report = MutableStateFlow<WriteReport?>(null)
    val report: StateFlow<WriteReport?> = _report.asStateFlow()

    private val _savedPacks = MutableStateFlow<List<RecipePack>>(emptyList())
    val savedPacks: StateFlow<List<RecipePack>> = _savedPacks.asStateFlow()

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes: StateFlow<List<Recipe>> = _recipes.asStateFlow()

    private val _dump = MutableStateFlow<String?>(null)
    val dump: StateFlow<String?> = _dump.asStateFlow()

    private val _pending = MutableStateFlow<PendingWrite?>(null)
    val pending: StateFlow<PendingWrite?> = _pending.asStateFlow()

    private val _backups = MutableStateFlow<List<BankSnapshot>>(emptyList())
    val backups: StateFlow<List<BankSnapshot>> = _backups.asStateFlow()

    private val usbManager: UsbManager
        get() = getApplication<Application>().getSystemService(Context.USB_SERVICE) as UsbManager

    private var pendingDevice: UsbDevice? = null

    init {
        viewModelScope.launch {
            _savedPacks.value = packs.loadPacks()
            _recipes.value = packs.loadRecipes()
            _backups.value = backupStore.list()
            // Frames left behind by a session that was killed with the editor
            // open, or by a recipe deleted before this ran.
            sweepPhotos()
        }
    }

    // -- Connection --------------------------------------------------------

    /**
     * Whether the camera notice still has to be shown.
     *
     * A marker file rather than a preference, to sit beside the other state
     * this app keeps in its own files, and because a single question asked once
     * does not need a preferences framework behind it.
     */
    private val noticeFile = java.io.File(app.filesDir, "camera-notice-accepted")

    private val _cameraNotice = MutableStateFlow(false)
    /** True while the first-connection notice is on screen. */
    val cameraNotice: StateFlow<Boolean> = _cameraNotice.asStateFlow()

    /**
     * Accepting it connects straight away, since wanting to connect is what
     * raised it. Declining leaves the camera alone — that is the whole point of
     * asking before the first connection rather than after it.
     */
    fun acceptCameraNotice() {
        runCatching { noticeFile.writeText("1") }
        _cameraNotice.value = false
        findAndConnect()
    }

    fun declineCameraNotice() {
        _cameraNotice.value = false
        appendLog("Camera notice declined — not connecting")
    }

    /** Look for a Fujifilm body on the bus and connect, asking for access first. */
    fun findAndConnect() {
        val device = usbManager.deviceList.values.firstOrNull { UsbTransport.isFujiCamera(it) }
        if (device == null) {
            _cameraState.value = CameraState.NoDevice
            appendLog("No Fujifilm camera found on USB")
            return
        }
        // Every route to a connection comes through here — launch, hotplug, the
        // Connect button — so this is the one place the notice has to gate. It
        // says "if the warranty matters more to you than this app, do not
        // connect", which is worth nothing if the cable is already talking.
        //
        // After the device check, not before: this app is also a recipe editor,
        // and opening it on a train to fix a bank before going out should not
        // produce a wall of text about a camera that is not there.
        if (!noticeFile.exists()) {
            _cameraNotice.value = true
            return
        }
        if (!usbManager.hasPermission(device)) {
            pendingDevice = device
            _cameraState.value = CameraState.NeedsPermission(device)
            requestPermission(device)
            return
        }
        connect(device)
    }

    private fun requestPermission(device: UsbDevice) {
        val context = getApplication<Application>()
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager.requestPermission(device, intent)
    }

    /** Called by the activity once the permission broadcast lands. */
    fun onPermissionResult(granted: Boolean) {
        val device = pendingDevice
        pendingDevice = null
        if (!granted || device == null) {
            _cameraState.value = CameraState.Failed("USB access denied")
            return
        }
        connect(device)
    }

    private fun connect(device: UsbDevice) = launchBusy("Connecting", BusyScope.CAMERA) {
        _cameraState.value = CameraState.Connecting(device)
        try {
            val info = camera.connect(usbManager, device)
            _cameraState.value = CameraState.Connected(
                info.model, info.deviceVersion, info.serialNumber,
                banks = camera.supportsBanks(),
            )
            if (!camera.supportsBanks()) {
                appendLog(
                    "This body did not advertise the bank properties. If reads fail, " +
                        "set CONNECTION MODE to USB RAW CONV./BACKUP RESTORE."
                )
            }
            readBanksInternal()
        } catch (e: Exception) {
            _cameraState.value = CameraState.Failed(e.message ?: "Connection failed")
            appendLog("Connect failed: ${e.message}")
        }
    }

    // -- Reading -----------------------------------------------------------

    fun readBanks() = launchBusy("Reading banks", BusyScope.CAMERA) { readBanksInternal() }

    private suspend fun readBanksInternal() {
        try {
            _banks.value = camera.readAllBanks()
            appendLog("Read ${_banks.value.size} banks")
        } catch (e: FujiCamera.WrongModeException) {
            // Not a failure: card-reader mode has no bank properties and the
            // Photos screen wants exactly that. The bank screens say so
            // themselves, so the connection stays up and unalarmed.
            appendLog(e.message ?: "Wrong USB mode")
            _cameraState.update { state ->
                if (state is CameraState.Connected) state.copy(banks = false) else state
            }
            _banks.value = emptyList()
        } catch (e: Exception) {
            noticed("Read failed: ${e.message}")
        }
    }

    /** Back up every bank, or only the slots asked for. */
    fun backupNow(slots: Set<Int>? = null) = launchBusy("Backing up", BusyScope.BACKUP) {
        if (!ensureConnected()) return@launchBusy
        try {
            val snapshot = snapshotFromCamera().let { full ->
                if (slots == null) full
                else full.copy(banks = full.banks.filter { it.slot in slots })
            }
            if (snapshot.banks.isEmpty()) {
                noticed("Nothing selected to back up")
                return@launchBusy
            }
            when (val result = backupStore.saveIfChanged(snapshot)) {
                is SaveResult.Saved -> {
                    _backups.value = backupStore.list()
                    noticed("Backed up ${snapshot.banks.size} banks")
                }
                // A backup that changes nothing is not written, and saying so is
                // the difference between that and a button that did not work.
                is SaveResult.Duplicate -> noticed(
                    "Banks unchanged since the backup of " +
                        "${formatWhen(result.existing.takenAt)} — kept that one"
                )
            }
        } catch (e: Exception) {
            noticed("Backup failed: ${e.message}")
        }
    }

    /**
     * Read the camera and keep the result as a restorable snapshot.
     *
     * A short read is thrown away rather than saved. [FujiCamera.readAllBanks]
     * stops at the first slot the camera refuses and returns what it has, and
     * such a stub is indistinguishable from a real backup once it is on disk —
     * restoring it would leave the untouched slots holding whatever came later.
     * Refusing here is what makes "never write without a snapshot first" mean
     * anything: the caller aborts instead of writing against a partial read.
     */
    private suspend fun snapshotFromCamera(): BankSnapshot {
        val read = camera.readAllBanks()
        _banks.value = read
        if (read.size < FujiProps.SLOT_COUNT) throw ShortReadException(read.size)
        val info = camera.deviceInfo
        return BankSnapshot(
            cameraModel = info?.model ?: "unknown",
            serialNumber = info?.serialNumber ?: "",
            takenAt = System.currentTimeMillis(),
            banks = read.map { Bank(it.slot, it.recipe) },
        )
    }

    // -- Writing -----------------------------------------------------------

    /**
     * Write a pack across the banks, snapshotting first.
     *
     * The snapshot is taken from the camera rather than from what is on screen:
     * the on-screen state may be stale, and undo has to restore what was really
     * there. If the snapshot fails, nothing is written.
     */
    /**
     * Stage a pack for writing. Reads the camera first so the diff is against
     * what is actually in the banks, not what was last shown.
     */
    fun stageWrite(pack: RecipePack) = stageAssignments(
        pack.name,
        pack.recipes.mapIndexed { index, recipe -> (index + 1) to recipe },
    )

    /** Stage one recipe into one chosen bank. */
    fun stageSingle(recipe: Recipe, slot: Int) = stageAssignments(
        recipe.name.ifEmpty { "recipe" },
        listOf(slot to recipe),
    )

    private fun stageAssignments(title: String, assignments: List<Pair<Int, Recipe>>) =
        launchBusy("Reading current banks", BusyScope.RECIPES) {
            if (!ensureConnected()) return@launchBusy
            try {
                readBanksInternal()
                _pending.value = PendingWrite(title, assignments, _banks.value)
            } catch (e: Exception) {
                noticed("Could not stage write: ${e.message}")
            }
        }

    fun cancelPendingWrite() { _pending.value = null }

    fun confirmPendingWrite() {
        val pending = _pending.value ?: return
        _pending.value = null
        writeAssignments(pending.title, pending.assignments)
    }

    private fun writeAssignments(title: String, assignments: List<Pair<Int, Recipe>>) =
        launchBusy("Installing $title", BusyScope.RECIPES) {
        val baseline = try {
            snapshotFromCamera()
        } catch (e: Exception) {
            noticed("Nothing written: could not back up the banks first (${e.message})")
            return@launchBusy
        }
        when (val result = backupStore.saveIfChanged(baseline)) {
            is SaveResult.Saved -> _backups.value = backupStore.list()
            // Undo restores the newest snapshot, and that is this one's twin.
            is SaveResult.Duplicate -> appendLog(
                "Banks already backed up at ${formatWhen(result.existing.takenAt)}"
            )
        }

        val baseProps = _banks.value.associate { it.slot to it.rawProps }
        val results = mutableListOf<SlotWriteResult>()
        for ((slot, recipe) in assignments.sortedBy { it.first }) {
            if (slot !in 1..FujiProps.SLOT_COUNT) continue
            progress("Installing C$slot: ${recipe.name.ifEmpty { "unnamed" }}")
            results += try {
                camera.writeBank(slot, recipe, baseProps[slot] ?: emptyMap())
            } catch (e: Exception) {
                noticed("C$slot failed: ${e.message}")
                SlotWriteResult(slot, ok = false, rejected = listOf(e.message ?: "error"))
            }
        }

        _report.value = WriteReport(results, undoAvailable = true)
        readBanksInternal()
    }

    /**
     * Put back whatever the most recent snapshot holds.
     *
     * No snapshot is taken first, and that is the point: the newest backup *is*
     * what this is putting back, and taking another one now would only record
     * the state being undone.
     */
    fun undoLastWrite() = launchBusy("Restoring", BusyScope.BACKUP) {
        val snapshot = backupStore.latest()
        if (snapshot == null) {
            noticed("No backup to restore")
            return@launchBusy
        }
        restoreInternal(snapshot, backupFirst = false)
    }

    /**
     * Put back a snapshot, or only the slots picked from it. Restoring one bank
     * out of a backup leaves the other six alone.
     */
    fun restoreBackup(snapshot: BankSnapshot, slots: Set<Int>? = null) = launchBusy("Restoring", BusyScope.BACKUP) {
        restoreInternal(
            if (slots == null) snapshot
            else snapshot.copy(banks = snapshot.banks.filter { it.slot in slots })
        )
    }

    fun deleteBackup(takenAt: Long) = viewModelScope.launch {
        backupStore.delete(takenAt)
        _backups.value = backupStore.list()
    }

    /**
     * Write a snapshot back into the banks, snapshotting what is there first.
     *
     * [backupFirst] is false only for undo, which is already putting the newest
     * backup back. Otherwise this takes the same precaution as
     * [writeAssignments]: restore is a full overwrite of whatever the banks hold
     * now, so what it destroys has to be on disk before it goes — and if that
     * read fails, nothing is written at all.
     */
    private suspend fun restoreInternal(snapshot: BankSnapshot, backupFirst: Boolean = true) {
        if (!ensureConnected()) return
        if (backupFirst) {
            val baseline = try {
                snapshotFromCamera()
            } catch (e: Exception) {
                noticed("Restore cancelled: could not back up the banks first (${e.message})")
                return
            }
            when (val result = backupStore.saveIfChanged(baseline)) {
                is SaveResult.Saved -> _backups.value = backupStore.list()
                // Undo restores the newest snapshot, and that is this one's twin.
                is SaveResult.Duplicate -> appendLog(
                    "Banks already backed up at ${formatWhen(result.existing.takenAt)}"
                )
            }
        }
        val baseProps = _banks.value.associate { it.slot to it.rawProps }
        val results = mutableListOf<SlotWriteResult>()
        for (bank in snapshot.banks.sortedBy { it.slot }) {
            progress("Restoring C${bank.slot}")
            results += try {
                camera.writeBank(bank.slot, bank.recipe, baseProps[bank.slot] ?: emptyMap())
            } catch (e: Exception) {
                SlotWriteResult(bank.slot, ok = false, rejected = listOf(e.message ?: "error"))
            }
        }
        // Undo is offered only when there is now a snapshot of the state this
        // restore replaced — after an undo there is not, and offering to undo
        // the undo would put back exactly what was just discarded.
        _report.value = WriteReport(results, undoAvailable = backupFirst)
        readBanksInternal()
        appendLog("Restored backup from ${formatWhen(snapshot.takenAt)}")
    }


    fun dismissReport() { _report.value = null }

    // -- Discovery ---------------------------------------------------------

    /**
     * Dump the raw bank properties. On any body other than the X100VI this is
     * the first thing to run: the property map is unverified elsewhere.
     */
    fun dumpBanks() = launchBusy("Dumping properties", BusyScope.DEBUG) {
        _dump.value = try {
            camera.dumpAll()
        } catch (e: Exception) {
            "Dump failed: ${e.message}"
        }
    }

    /**
     * The loaded RAF's D185 block, field by field.
     *
     * Needs a RAF open on Develop — with an empty conversion buffer the camera
     * answers <unsupported> and there is nothing to show.
     */
    fun dumpProfile() = launchBusy("Reading the profile", BusyScope.DEBUG) {
        _dump.value = try {
            camera.dumpProfile()
        } catch (e: Exception) {
            "Profile read failed: ${e.message}"
        }
    }

    fun dismissDump() { _dump.value = null }

    /** Read-only look at what the camera exposes as storages and objects. */
    fun dumpStorage() = launchBusy("Listing objects", BusyScope.DEBUG) {
        if (!ensureConnected()) return@launchBusy
        _dump.value = try {
            camera.dumpStorage()
        } catch (e: Exception) {
            "Storage listing failed: ${e.message}"
        }
    }

    // -- Finding unknown properties ---------------------------------------

    /**
     * A sweep of every responding property, kept so a later sweep can be
     * diffed against it. This is how a setting with no known property code —
     * the FS1-FS3 recipe slots, for instance — gets located: take a baseline,
     * change exactly one thing in the camera menu, then diff.
     */
    private val baselineFile = java.io.File(app.filesDir, "baseline.txt")

    private var baseline: Map<Int, ByteArray>? = loadBaseline()
    private var baselineTakenAt: Long = 0

    /**
     * The camera locks its controls while connected, so changing a setting means
     * unplugging it. The baseline therefore has to outlive the USB session — it
     * is written to disk as "propId:hexbytes" lines.
     */
    private fun saveBaseline(scan: Map<Int, ByteArray>) {
        runCatching {
            baselineFile.writeText(
                scan.entries.joinToString("\n") { (id, bytes) ->
                    "%04X:%s".format(id, bytes.joinToString("") { "%02x".format(it) })
                }
            )
        }.onFailure {
            // Silence here is the worst outcome: the next Diff would compare
            // against whatever stale baseline is still on disk and report
            // changes that are months old as if they had just happened.
            noticed("Baseline not saved: ${it.message} — the next Diff would use a stale one")
        }
    }

    private fun loadBaseline(): Map<Int, ByteArray>? = runCatching {
        if (!baselineFile.exists()) return@runCatching null
        baselineFile.readLines().mapNotNull { line ->
            val (idPart, hexPart) = line.split(":", limit = 2).takeIf { it.size == 2 }
                ?: return@mapNotNull null
            val id = idPart.toInt(16)
            val bytes = ByteArray(hexPart.length / 2) { i ->
                hexPart.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            id to bytes
        }.toMap().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun captureBaseline() = launchBusy("Scanning properties", BusyScope.DEBUG) {
        if (!ensureConnected()) return@launchBusy
        try {
            val scan = camera.scanProperties { done, total, found ->
                progress("Scanning $done/$total — $found found")
            }
            baseline = scan
            baselineTakenAt = System.currentTimeMillis()
            saveBaseline(scan)
            appendLog("Baseline: ${scan.size} properties responded")
            _dump.value = buildString {
                appendLine("Baseline captured: ${scan.size} properties responded.")
                appendLine()
                appendLine("Now change ONE setting on the camera — inside FS1, say")
                appendLine("Sharpness from 0 to +2 — then press Diff.")
                appendLine()
                appendLine("Responding property codes:")
                scan.keys.chunked(8).forEach { row ->
                    appendLine("  " + row.joinToString(" ") { "%04X".format(it) })
                }
            }
        } catch (e: Exception) {
            noticed("Scan failed: ${e.message}")
        }
    }

    /** Sweep again and report only what moved. */
    fun diffAgainstBaseline() = launchBusy("Scanning properties", BusyScope.DEBUG) {
        val before = baseline
        if (before == null) {
            noticed("No baseline yet — capture one first")
            return@launchBusy
        }
        if (!ensureConnected()) return@launchBusy
        try {
            val after = camera.scanProperties { done, total, found ->
                progress("Scanning $done/$total — $found found")
            }

            // A dead session reads as "everything vanished". That is a broken
            // connection, not a camera that changed, and saying so would be a lie.
            if (after.size < before.size / 2) {
                appendLog("Scan returned ${after.size} of ${before.size} — connection lost, not a change")
                _dump.value = "Scan returned only ${after.size} properties against " +
                    "${before.size} in the baseline.\n\n" +
                    "That is a dropped connection, not a change on the camera. " +
                    "Reconnect and press Diff again — the baseline is kept."
                return@launchBusy
            }

            val changed = after.filter { (id, bytes) ->
                before[id]?.contentEquals(bytes) == false
            }
            val appeared = after.keys - before.keys
            val vanished = before.keys - after.keys

            _dump.value = buildString {
                appendLine("Diff against baseline")
                appendLine("${before.size} properties before, ${after.size} after")
                appendLine()
                if (changed.isEmpty() && appeared.isEmpty() && vanished.isEmpty()) {
                    appendLine("Nothing moved.")
                    appendLine()
                    appendLine("Either the change did not reach a property this scan can")
                    appendLine("read, or the camera had not committed it yet. Try leaving")
                    appendLine("the menu, or half-pressing the shutter, before diffing.")
                } else {
                    appendLine("CHANGED (${changed.size}):")
                    changed.forEach { (id, now) ->
                        val was = before[id]!!
                        appendLine(
                            "  %04X %-20s [%s] -> [%s]   %d -> %d".format(
                                id, FujiProps.name(id),
                                was.toHex(), now.toHex(),
                                Codec.decodeInt(was), Codec.decodeInt(now),
                            )
                        )
                    }
                    if (appeared.isNotEmpty()) {
                        appendLine()
                        appendLine("NEWLY ANSWERING: " + appeared.joinToString(" ") { "%04X".format(it) })
                    }
                    if (vanished.isNotEmpty()) {
                        appendLine()
                        appendLine("STOPPED ANSWERING: " + vanished.joinToString(" ") { "%04X".format(it) })
                    }
                }
            }
            appendLog("Diff: ${changed.size} changed, ${appeared.size} new, ${vanished.size} gone")
            // The new sweep becomes the baseline, so changes can be chained.
            baseline = after
        } catch (e: Exception) {
            appendLog("Diff failed: ${e.message}")
        }
    }

    // -- Library -----------------------------------------------------------

    /** Keep what is in a camera bank as a recipe in the library. */
    fun saveBankAsRecipe(slot: Int, name: String) {
        val bank = _banks.value.firstOrNull { it.slot == slot }
        if (bank == null) {
            noticed("C$slot has not been read")
            return
        }
        saveRecipe(bank.recipe.copy(name = name.ifEmpty { "C$slot" }))
        noticed("Saved C$slot to the library")
    }

    // -- Sample frames -----------------------------------------------------

    /**
     * Read an image the picker or the clipboard handed over, shrink it, and
     * hand back the id to store on the recipe.
     *
     * The bytes are read here rather than on the screen because a full-size
     * frame off the camera roll is tens of megabytes and the decode belongs off
     * the main thread. [onSaved] runs with the new id once it is on disk.
     */
    fun importPhoto(uri: android.net.Uri, onSaved: (String) -> Unit) = viewModelScope.launch {
        // A 26 MP frame is tens of megabytes and the decode takes a visible
        // moment. Without the strip, the editor simply sits there after the
        // picker closes and nothing says the image is on its way.
        _busy.value = Busy("Reading the image", BusyScope.RECIPES)
        try {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                noticed("Could not read that image")
                return@launch
            }
            progress("Shrinking the frame")
            val id = withContext(Dispatchers.IO) { photoStore.save(bytes) }
            if (id == null) {
                noticed("That file is not an image this can read")
                return@launch
            }
            justImported += id
            onSaved(id)
        } finally {
            _busy.value = null
        }
    }

    /** Said when Paste is pressed with nothing usable on the clipboard. */
    fun sayNoImageInClipboard() {
        noticed("No image on the clipboard — copy one from the gallery or a browser first")
    }

    /**
     * Drop every sample frame nothing points at.
     *
     * Recipes share ids — a duplicate copies the reference, not the file — so
     * this asks the whole library and every saved set, never one recipe.
     */
    private fun sweepPhotos() = viewModelScope.launch {
        val keep = buildSet {
            addAll(referenced())
            // A frame attached in the editor is on disk before the recipe
            // holding it is saved. Nothing refers to it yet, so a sweep in
            // between would delete it out from under the open editor.
            addAll(justImported)
        }
        // Once a recipe actually holds the id, that reprieve has done its job
        // and has to end — otherwise deleting the recipe in the same session
        // would leave the frame on disk until the app restarted.
        justImported.removeAll(referenced())
        photoStore.sweep(keep)
    }

    /** Every sample frame the library and the saved sets point at. */
    private fun referenced(): Set<String> = buildSet {
        _recipes.value.forEach { it.photoId?.let(::add) }
        _savedPacks.value.forEach { pack -> pack.recipes.forEach { it.photoId?.let(::add) } }
    }

    /** Ids imported but not yet saved onto a recipe; see [sweepPhotos]. */
    private val justImported = mutableSetOf<String>()

    /**
     * Keep a recipe in the library.
     *
     * [say] is for the callers whose screen shows nothing afterwards — Develop
     * saves into a library that lives on another tab, so without a word it looks
     * like the button did nothing and gets pressed again.
     */
    fun saveRecipe(recipe: Recipe, say: String? = null) = viewModelScope.launch {
        _recipes.value = packs.addRecipe(recipe)
        if (say != null) noticed(say)
    }

    /**
     * Library recipes have no id, so they are addressed by value.
     *
     * By position they were addressed once, and duplicating a recipe inserts at
     * `index + 1` — every position below the copy shifted, and an edit landed on
     * the wrong recipe. Structural equality picks the first match, which for two
     * recipes identical in every field is the same thing either way.
     */
    fun updateRecipe(original: Recipe, edited: Recipe) = viewModelScope.launch {
        val updated = _recipes.value.toMutableList()
        val at = updated.indexOf(original)
        if (at < 0) return@launch
        updated[at] = edited
        packs.saveRecipes(updated)
        _recipes.value = updated
        sweepPhotos()
    }

    /** A copy to vary without losing the original. */
    fun duplicateRecipe(recipe: Recipe) = viewModelScope.launch {
        val updated = _recipes.value.toMutableList()
        val at = updated.indexOf(recipe)
        if (at < 0) return@launch
        updated.add(at + 1, recipe.copy(name = "${recipe.name.ifEmpty { "unnamed" }} copy"))
        packs.saveRecipes(updated)
        _recipes.value = updated
    }

    fun deleteRecipe(recipe: Recipe) = viewModelScope.launch {
        val updated = _recipes.value.toMutableList()
        if (!updated.remove(recipe)) return@launch
        packs.saveRecipes(updated)
        _recipes.value = updated
        sweepPhotos()
    }

    fun savePack(pack: RecipePack) = viewModelScope.launch {
        val updated = _savedPacks.value.filterNot { it.name == pack.name } + pack
        packs.savePacks(updated)
        _savedPacks.value = updated
        sweepPhotos()
    }

    fun deletePack(name: String) = viewModelScope.launch {
        val updated = _savedPacks.value.filterNot { it.name == name }
        packs.savePacks(updated)
        _savedPacks.value = updated
        sweepPhotos()
    }

    /** Turn what is currently in the camera into a saved pack. */
    fun packFromCurrentBanks(name: String) {
        val current = _banks.value
        if (current.isEmpty()) {
            noticed("Nothing to save — read the banks first")
            return
        }
        savePack(RecipePack(name, current.sortedBy { it.slot }.map { it.recipe }))
        noticed("Saved \"$name\" — ${current.size} banks")
    }

    /**
     * Bring the camera back if it was unplugged. Returns false, with something
     * in the log, when there is nothing to talk to.
     */
    private suspend fun ensureConnected(): Boolean {
        if (camera.isConnected) return true

        val device = usbManager.deviceList.values.firstOrNull { UsbTransport.isFujiCamera(it) }
        if (device == null) {
            noticed("No camera on USB — plug it back in")
            _cameraState.value = CameraState.NoDevice
            return false
        }
        if (!usbManager.hasPermission(device)) {
            pendingDevice = device
            _cameraState.value = CameraState.NeedsPermission(device)
            requestPermission(device)
            appendLog("Waiting for USB access")
            return false
        }
        return try {
            runCatching { camera.disconnect() }
            val info = camera.connect(usbManager, device)
            _cameraState.value = CameraState.Connected(
                info.model, info.deviceVersion, info.serialNumber,
                banks = camera.supportsBanks(),
            )
            appendLog("Reconnected to ${info.model}")
            true
        } catch (e: Exception) {
            noticed("Reconnect failed: ${e.message}")
            _cameraState.value = CameraState.Failed(e.message ?: "Reconnect failed")
            false
        }
    }

    /** Called when the system reports the camera plugged in or pulled out. */
    fun onDeviceAttached() {
        appendLog("Camera attached")
        findAndConnect()
    }

    fun onDeviceDetached() = viewModelScope.launch {
        appendLog("Camera detached")
        runCatching { camera.disconnect() }
        _cameraState.value = CameraState.NoDevice
    }

    // -- Darkroom ----------------------------------------------------------

    private val _rafName = MutableStateFlow<String?>(null)
    val rafName: StateFlow<String?> = _rafName.asStateFlow()

    private val _rafAsShot = MutableStateFlow<Recipe?>(null)
    /** The recipe the RAF was taken with, read out of the camera's own profile. */
    val rafAsShot: StateFlow<Recipe?> = _rafAsShot.asStateFlow()

    private val _preview = MutableStateFlow<ByteArray?>(null)
    val preview: StateFlow<ByteArray?> = _preview.asStateFlow()

    /**
     * Push a RAF into the camera and render it as it was shot.
     *
     * The camera does the processing, so the result is what the body itself
     * would have produced — the point of doing this over USB rather than
     * approximating the look on the phone.
     */
    fun loadRaf(name: String, bytes: ByteArray) = launchBusy("Uploading $name", BusyScope.DEVELOP) {
        if (!ensureConnected()) return@launchBusy
        try {
            val mb = bytes.size / 1024 / 1024
            // A chunk is 16 KB, so a 30 MB frame is close to two thousand
            // callbacks. Only whole percents reach the label.
            var lastPct = -1
            val asShot = camera.loadRaf(bytes) { sent, total ->
                val pct = if (total > 0) (sent * 100 / total).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    progress("Uploading $name — $pct% of $mb MB")
                }
            }
            _rafName.value = name
            _rafAsShot.value = asShot
            appendLog("Loaded $name (${bytes.size / 1024 / 1024} MB)")
            progress("Rendering")
            _preview.value = camera.convert(asShot)
        } catch (e: Exception) {
            noticed("RAF load failed: ${e.message}")
            _rafName.value = null
            _rafAsShot.value = null
            _preview.value = null
        }
    }

    fun renderWith(recipe: Recipe, exposureBias: Double = 0.0) = launchBusy("Rendering", BusyScope.DEVELOP) {
        if (!camera.rafLoaded) {
            noticed("Load a RAF first")
            return@launchBusy
        }
        try {
            _preview.value = camera.convert(recipe, exposureBias)
        } catch (e: Exception) {
            noticed("Render failed: ${e.message}")
        }
    }

    fun clearRaf() {
        _rafName.value = null
        _rafAsShot.value = null
        _preview.value = null
    }

    // -- Photos on the card ------------------------------------------------

    private val _photos = MutableStateFlow<List<ObjectInfo>>(emptyList())
    val photos: StateFlow<List<ObjectInfo>> = _photos.asStateFlow()

    private val _thumbs = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val thumbs: StateFlow<Map<Int, ByteArray>> = _thumbs.asStateFlow()

    /** True when the camera is in card-reader mode rather than RAW conv. */

    /**
     * List what is on the card.
     *
     * Only works in USB CARD READER mode. In RAW CONV. the card is not exposed
     * and the two storages are empty conversion buffers, so an empty result
     * there means the wrong mode, not an empty card.
     */
    fun loadPhotos() = launchBusy("Reading the card", BusyScope.PHOTOS) {
        if (!ensureConnected()) return@launchBusy
        try {
            val found = camera.listPhotos { done, total ->
                progress("Reading the card $done/$total")
            }
            _photos.value = found
            if (found.isEmpty()) {
                appendLog("No images — is the camera in USB CARD READER mode?")
            } else {
                appendLog("Card holds ${found.size} images")
                loadThumbnails(found)
            }
        } catch (e: Exception) {
            noticed("Card read failed: ${e.message}")
        }
    }

    /**
     * Thumbnails arrive one at a time so the grid fills in as it goes.
     *
     * Each one is its own round trip, so this is the slow half of reading a
     * card. It retitles the strip: without that, the label stayed on "Reading
     * the card 63/63" through a minute of further work, which reads as a hang.
     */
    private suspend fun loadThumbnails(list: List<ObjectInfo>) {
        var done = 0
        for (info in list) {
            done++
            if (_thumbs.value.containsKey(info.handle)) continue
            progress("Thumbnails $done/${list.size}")
            val thumb = runCatching { camera.getThumbnail(info.handle) }.getOrNull() ?: continue
            _thumbs.update { it + (info.handle to thumb) }
        }
    }

    /**
     * Copy the chosen frames to the phone.
     *
     * These are 15-17 MB each and a single bulk read is capped at 16 KB, so a
     * frame is a few thousand round trips — hence the per-file progress.
     */
    fun downloadPhotos(handles: Set<Int>) =
        fetchPhotos(handles) { name, bytes -> saveToGallery(name, bytes) }

    /**
     * The same frames, written wherever the phone's file picker was pointed.
     *
     * The gallery is right for JPEGs and wrong for everything else — a RAF in
     * Pictures is a file no gallery app will open — so the destination is a
     * choice, not a fixed folder.
     */
    fun savePhotosTo(handles: Set<Int>, tree: android.net.Uri) =
        fetchPhotos(handles) { name, bytes -> saveToTree(tree, name, bytes) }

    /**
     * Pull the chosen frames off the card and hand each one to [write].
     *
     * These are 15-17 MB each and a single bulk read is capped at 16 KB, so a
     * frame is a few thousand round trips — hence the per-file progress.
     */
    private fun fetchPhotos(
        handles: Set<Int>,
        write: suspend (String, ByteArray) -> Boolean,
    ) = launchBusy("Copying", BusyScope.PHOTOS) {
        if (!ensureConnected()) return@launchBusy
        val chosen = _photos.value.filter { it.handle in handles }
        var done = 0
        var firstFailure: String? = null
        for (info in chosen) {
            progress("Copying ${info.filename} (${done + 1}/${chosen.size})")
            var lastPct = -1
            val bytes = try {
                camera.getObjectWithProgress(info.handle, info.compressedSize) { got, total ->
                    val pct = if (total > 0) (got * 100 / total).toInt() else 0
                    if (pct != lastPct) {
                        lastPct = pct
                        progress("Copying ${info.filename} — $pct% (${done + 1}/${chosen.size})")
                    }
                }
            } catch (e: Exception) {
                appendLog("${info.filename}: ${e.message}")
                if (firstFailure == null) firstFailure = "${info.filename}: ${e.message}"
                continue
            }
            if (write(info.filename, bytes)) done++
        }
        val where = _lastDestination
        // "See the log" is not an answer when the log is behind a long press on
        // the title. Whatever went wrong first is said here, in full.
        _photoStatus.value = when {
            done == 0 -> "Nothing was saved — ${firstFailure ?: "the camera refused every frame"}"
            done < chosen.size -> "Saved $done of ${chosen.size} to $where — ${firstFailure ?: "the rest failed"}"
            else -> "Saved $done to $where"
        }
        appendLog("Copied $done of ${chosen.size} to $where")
    }

    fun clearPhotoStatus() { _photoStatus.value = null }

    private val _photoStatus = MutableStateFlow<String?>(null)
    /** What became of the last copy, said on the screen that asked for it. */
    val photoStatus: StateFlow<String?> = _photoStatus.asStateFlow()

    /** Where the files from the last copy landed, for the message afterwards. */
    private var _lastDestination: String = ""

    /** Put the rendered JPEG in the phone's gallery. */
    fun savePreviewToGallery(baseName: String) = viewModelScope.launch {
        val jpeg = _preview.value
        if (jpeg == null) {
            noticed("Nothing rendered yet")
            return@launch
        }
        if (saveToGallery("$baseName.jpg", jpeg)) {
            noticed("Saved $baseName.jpg to $_lastDestination")
        }
    }

    /**
     * Put a file in the phone's own storage.
     *
     * A JPEG goes to the gallery, where it is meant to be seen. A RAF does not:
     * MediaStore's image collection would take it under a JPEG's MIME type and
     * every gallery app would then choke on it, so raw files go to Downloads
     * under their own type instead.
     */
    private suspend fun saveToGallery(filename: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val jpeg = filename.substringAfterLast('.', "").lowercase() in JPEG_EXTENSIONS
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val q = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                val collection = when {
                    jpeg -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    q -> android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    // Before Q there is no Downloads collection to insert into;
                    // the image one at least keeps the bytes somewhere findable.
                    else -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val folder =
                    if (jpeg || !q) android.os.Environment.DIRECTORY_PICTURES + "/FujiBanks"
                    else android.os.Environment.DIRECTORY_DOWNLOADS + "/FujiBanks"
                _lastDestination = folder
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeFor(filename))
                    if (q) put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, folder)
                }
                val uri = resolver.insert(collection, values)
                    ?: error("MediaStore refused the insert")
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No output stream")
            }.onFailure { noticed("Save failed: ${it.message}") }.isSuccess
        }

    /** Write into a folder the file picker handed over, whatever it holds. */
    private suspend fun saveToTree(
        tree: android.net.Uri,
        filename: String,
        bytes: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
            )
            _lastDestination = tree.lastPathSegment?.substringAfterLast(':') ?: "the chosen folder"
            val doc = android.provider.DocumentsContract.createDocument(
                resolver, dir, mimeFor(filename), filename,
            ) ?: error("The folder refused the file")
            resolver.openOutputStream(doc)?.use { it.write(bytes) } ?: error("No output stream")
        }.onFailure { noticed("Save failed: ${it.message}") }.isSuccess
    }

    /** RAF has no registered type on Android; this is the one Fuji's tools use. */
    private fun mimeFor(filename: String): String =
        when (filename.substringAfterLast('.', "").lowercase()) {
            in JPEG_EXTENSIONS -> "image/jpeg"
            "raf" -> "image/x-fuji-raf"
            "mov" -> "video/quicktime"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }

    // -- Plumbing ----------------------------------------------------------

    private fun appendLog(line: String) {
        _log.update { (it + line).takeLast(LOG_LINES) }
    }

    /** Log it, and say it on screen too. For anything the user can act on. */
    private fun noticed(line: String) {
        appendLog(line)
        _notices.trySend(line)
    }

    /**
     * The same, for a screen that has caught something the view model never saw
     * — reading the file behind a picked `Uri` happens on the screen, because
     * only the screen has the `ContentResolver` the picker's grant is tied to.
     */
    fun say(line: String) = noticed(line)

    private fun launchBusy(label: String, scope: BusyScope, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = Busy(label, scope)
            try {
                block()
            } finally {
                _busy.value = null
            }
        }
    }

    /** Retitle the operation in flight; the scope it belongs to does not change. */
    private fun progress(label: String) {
        _busy.update { it?.copy(label = label) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { runCatching { camera.disconnect() } }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "org.nemo.fujibanks.USB_PERMISSION"
        private const val LOG_LINES = 200
        private val JPEG_EXTENSIONS = setOf("jpg", "jpeg")
    }
}

/** A bank scan that stopped early — see [BanksViewModel.snapshotFromCamera]. */
private class ShortReadException(got: Int) : Exception(
    "read only $got of ${FujiProps.SLOT_COUNT} banks"
)

/**
 * One way to write a backup's timestamp, shared with the Backup screen.
 *
 * There were two, with different formats, so a notice said "the backup of
 * 14:32" while the list two taps away called the same file "3 Aug 2026, 14:32"
 * — and with several backups a day, the short form did not identify anything.
 */
internal fun formatWhen(millis: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(millis))

/** Receives the USB permission broadcast and hands the answer to the model. */
class UsbPermissionReceiver(private val onResult: (Boolean) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BanksViewModel.ACTION_USB_PERMISSION) return
        onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
    }

    companion object {
        fun filter() = IntentFilter(BanksViewModel.ACTION_USB_PERMISSION)
    }
}
