package org.nemo.fujibanks.fuji

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nemo.fujibanks.usb.DeviceInfo
import org.nemo.fujibanks.usb.ObjectInfo
import org.nemo.fujibanks.usb.PropDesc
import org.nemo.fujibanks.usb.PtpException
import org.nemo.fujibanks.usb.PtpOp
import org.nemo.fujibanks.usb.PtpResp
import org.nemo.fujibanks.usb.UsbTransport
import org.nemo.fujibanks.usb.packPtpString
import org.nemo.fujibanks.usb.packU16
import org.nemo.fujibanks.usb.parseDeviceInfo
import org.nemo.fujibanks.usb.parseObjectInfo
import org.nemo.fujibanks.usb.parsePropDesc
import org.nemo.fujibanks.usb.parsePtpString
import org.nemo.fujibanks.usb.toHex

/** One property as it came off the camera: the bytes, plus a decoded view. */
data class RawProp(
    val propId: Int,
    val bytes: ByteArray,
    val intValue: Int,
    val stringValue: String?,
) {
    val name: String get() = FujiProps.name(propId)
    override fun equals(other: Any?) =
        other is RawProp && other.propId == propId && other.bytes.contentEquals(bytes)
    override fun hashCode() = propId * 31 + bytes.contentHashCode()
}

/** One bank exactly as read: name, decoded recipe, and the untouched bytes. */
data class BankRead(
    val slot: Int,
    val name: String,
    val recipe: Recipe,
    val rawProps: Map<Int, ByteArray>,
)

/** What went wrong (or didn't) when writing one bank. */
data class SlotWriteResult(
    val slot: Int,
    val ok: Boolean,
    val rejected: List<String> = emptyList(),
    val mismatched: List<String> = emptyList(),
)

/**
 * A PTP session with a Fujifilm body.
 *
 * All camera I/O is serialised behind a mutex — the transport is a single pair
 * of bulk endpoints, and two overlapping operations would interleave their
 * containers. Every public call suspends on Dispatchers.IO.
 */
class FujiCamera(private val log: (String) -> Unit = {}) {

    private val transport = UsbTransport(log)
    private val mutex = Mutex()
    private var sessionOpen = false
    private var manager: UsbManager? = null
    private var device: UsbDevice? = null

    var deviceInfo: DeviceInfo? = null
        private set

    val isConnected: Boolean get() = transport.isConnected && sessionOpen

    /** The camera answered, but not with the properties this app needs. */
    class WrongModeException : Exception(
        "Camera does not expose recipe banks. Set CONNECTION MODE to " +
            "USB RAW CONV./BACKUP RESTORE and reconnect."
    )

    // -- Connection --------------------------------------------------------

    suspend fun connect(manager: UsbManager, device: UsbDevice): DeviceInfo =
        io {
            this.manager = manager
            this.device = device
            transport.connect(manager, device)
            openSession()
            val info = parseDeviceInfo(command(PtpOp.GET_DEVICE_INFO, "GetDeviceInfo").data)
            deviceInfo = info
            log("${info.model} ${info.deviceVersion}, ${info.properties.size} properties")
            info
        }

    suspend fun disconnect() = io {
        if (sessionOpen) {
            runCatching { transport.sendCommand(PtpOp.CLOSE_SESSION) }
            sessionOpen = false
        }
        transport.disconnect()
        deviceInfo = null
    }

    private fun openSession() {
        val res = transport.sendCommand(PtpOp.OPEN_SESSION, listOf(SESSION_ID))
        when (res.code) {
            PtpResp.OK -> sessionOpen = true
            PtpResp.SESSION_ALREADY_OPEN -> {
                // A session left open by a previous run. Closing it clears the
                // camera's state but leaves our transaction ids out of step, so
                // re-establish the USB connection before retrying.
                log("Stale session, resetting connection")
                runCatching { transport.sendCommand(PtpOp.CLOSE_SESSION) }
                transport.reset(manager!!, device!!)
                val retry = transport.sendCommand(PtpOp.OPEN_SESSION, listOf(SESSION_ID))
                if (retry.code != PtpResp.OK) throw PtpException(retry.code, "OpenSession")
                sessionOpen = true
            }
            else -> throw PtpException(res.code, "OpenSession")
        }
    }

    /** Whether this body advertises the recipe-bank properties. */
    fun supportsBanks(): Boolean {
        val info = deviceInfo ?: return false
        // An empty property list means the body didn't advertise them; probing
        // is then the only way to find out, so allow it through.
        return info.properties.isEmpty() || FujiProps.PRESET_SLOT in info.properties
    }

    // -- Property access ---------------------------------------------------

    /** Read one property, or null if the camera refuses it. */
    suspend fun readProp(propId: Int): RawProp? = io { readPropLocked(propId) }

    private fun readPropLocked(propId: Int): RawProp? {
        val res = try {
            transport.sendCommand(PtpOp.GET_DEVICE_PROP_VALUE, listOf(propId))
        } catch (e: Exception) {
            log("read ${FujiProps.name(propId)}: ${e.message}")
            return null
        }
        if (res.code != PtpResp.OK || res.data.isEmpty()) return null
        val bytes = res.data
        return RawProp(
            propId = propId,
            bytes = bytes,
            intValue = Codec.decodeInt(bytes),
            stringValue = asPtpString(bytes),
        )
    }

    /**
     * A property payload is a PTP string when its first byte is a plausible
     * character count for the bytes that follow. Numeric payloads are one, two
     * or four bytes and never satisfy that.
     */
    private fun asPtpString(bytes: ByteArray): String? {
        if (bytes.size < 3) return null
        val chars = bytes[0].toInt() and 0xFF
        val expected = 1 + chars * 2
        if (chars < 2 || (expected != bytes.size && expected != bytes.size + 1)) return null
        return runCatching { parsePtpString(bytes) }.getOrNull()
    }

    suspend fun readPropDesc(propId: Int): PropDesc? = io {
        val res = try {
            transport.sendCommand(PtpOp.GET_DEVICE_PROP_DESC, listOf(propId))
        } catch (e: Exception) {
            return@io null
        }
        if (res.code != PtpResp.OK || res.data.isEmpty()) null
        else runCatching { parsePropDesc(res.data) }.getOrNull()
    }

    private fun writePropLocked(propId: Int, bytes: ByteArray): Boolean {
        val res = try {
            transport.sendDataCommand(PtpOp.SET_DEVICE_PROP_VALUE, listOf(propId), bytes)
        } catch (e: Exception) {
            log("write ${FujiProps.name(propId)}: ${e.message}")
            return false
        }
        return res.code == PtpResp.OK
    }

    // -- Banks -------------------------------------------------------------

    /** Read every bank C1..C7. Restores the originally selected slot when done. */
    suspend fun readAllBanks(): List<BankRead> = io {
        if (!supportsBanks()) throw WrongModeException()

        val originalSlot = readPropLocked(FujiProps.PRESET_SLOT)?.intValue
        val banks = mutableListOf<BankRead>()
        try {
            for (slot in 1..FujiProps.SLOT_COUNT) {
                if (!writePropLocked(FujiProps.PRESET_SLOT, packU16(slot))) {
                    if (slot == 1) throw WrongModeException()
                    log("Slot $slot: selection refused, stopping bank scan")
                    break
                }
                // The camera needs a moment to swap the bank into the property
                // space; reading immediately returns the previous slot.
                delay(SLOT_SETTLE_MS)
                banks.add(readBankLocked(slot))
            }
        } finally {
            originalSlot?.let { writePropLocked(FujiProps.PRESET_SLOT, packU16(it)) }
        }
        banks
    }

    /** Read the bank currently selected by D18C. Assumes the mutex is held. */
    private fun readBankLocked(slot: Int): BankRead {
        val name = readPropLocked(FujiProps.PRESET_NAME)?.stringValue ?: ""
        val raw = mutableMapOf<Int, ByteArray>()
        val decoded = mutableMapOf<Int, Int>()
        for (propId in FujiProps.FIRST_PRESET_PROP..FujiProps.LAST_PRESET_PROP) {
            val prop = readPropLocked(propId) ?: continue
            raw[propId] = prop.bytes
            decoded[propId] = prop.intValue
        }
        return BankRead(slot, name, Codec.recipeFromProps(name, decoded), raw)
    }

    /**
     * Write one recipe into one bank and read it back.
     *
     * Slot selection and the name are fatal on failure — without them the write
     * would land somewhere unintended. Individual property rejections are
     * reported but not fatal, since which properties a body accepts varies.
     */
    suspend fun writeBank(slot: Int, recipe: Recipe, base: Map<Int, ByteArray> = emptyMap()):
        SlotWriteResult = io {
        require(slot in 1..FujiProps.SLOT_COUNT) { "Slot out of range: $slot" }

        if (!writePropLocked(FujiProps.PRESET_SLOT, packU16(slot))) {
            return@io SlotWriteResult(slot, ok = false, rejected = listOf("slot selection"))
        }
        delay(SLOT_SETTLE_MS)

        if (recipe.name.isNotEmpty() &&
            !writePropLocked(FujiProps.PRESET_NAME, packPtpString(recipe.name))
        ) {
            return@io SlotWriteResult(slot, ok = false, rejected = listOf("preset name"))
        }

        val writes = Codec.propsFromRecipe(recipe, base)
        val accepted = mutableMapOf<Int, ByteArray>()
        val rejected = mutableListOf<String>()
        for (w in writes) {
            if (writePropLocked(w.propId, w.bytes)) {
                accepted[w.propId] = w.bytes
            } else {
                rejected.add("${FujiProps.name(w.propId)} [${w.bytes.toHex()}]")
            }
        }

        // Read back only what the camera accepted — a rejected write has
        // nothing to verify against.
        val mismatched = mutableListOf<String>()
        for ((propId, expected) in accepted) {
            val actual = readPropLocked(propId)?.bytes ?: continue
            if (!actual.contentEquals(expected)) {
                mismatched.add(
                    "${FujiProps.name(propId)}: wrote [${expected.toHex()}] read [${actual.toHex()}]"
                )
            }
        }

        log("Slot $slot: ${accepted.size}/${writes.size} written, ${mismatched.size} mismatched")
        SlotWriteResult(slot, ok = mismatched.isEmpty(), rejected, mismatched)
    }

    // -- Discovery ---------------------------------------------------------

    /**
     * Dump everything the camera will say about itself.
     *
     * The bank property map was confirmed on an X100VI and is unverified
     * elsewhere, and bodies with a film-simulation dial carry extra recipe
     * slots (FS1-FS3) whose property codes are not known at all. So this walks
     * every property the camera advertises, not just the range we expect the
     * banks to live in — the one it lands on is found by changing a setting in
     * the camera menu and diffing two dumps.
     */
    suspend fun dumpAll(): String = io {
        val sb = StringBuilder()
        val info = deviceInfo

        sb.appendLine("== Device ==")
        if (info != null) {
            sb.appendLine("model:    ${info.model}")
            sb.appendLine("firmware: ${info.deviceVersion}")
            sb.appendLine("serial:   ${info.serialNumber}")
            sb.appendLine("vendor:   ${info.vendorExtensionDesc}")
            sb.appendLine()
            sb.appendLine("operations (${info.operations.size}):")
            info.operations.chunked(8).forEach { row ->
                sb.appendLine("  " + row.joinToString(" ") { "%04X".format(it) })
            }
            sb.appendLine()
            sb.appendLine("properties advertised (${info.properties.size}):")
            info.properties.chunked(8).forEach { row ->
                sb.appendLine("  " + row.joinToString(" ") { "%04X".format(it) })
            }
            sb.appendLine()
            sb.appendLine("bank slot selector D18C advertised: ${FujiProps.PRESET_SLOT in info.properties}")
            val bankRange = info.properties.count {
                it in FujiProps.FIRST_PRESET_PROP..FujiProps.LAST_PRESET_PROP
            }
            sb.appendLine("bank properties D18E-D1A5 advertised: $bankRange of 24")
        }

        // Every advertised property, read as it stands right now. Diffing two
        // of these across a single menu change is what identifies a property.
        sb.appendLine()
        sb.appendLine("== All advertised properties ==")
        info?.properties?.forEach { propId ->
            sb.appendLine("  " + describeProp(propId))
        }

        // Does the slot selector actually do anything? Writing it returns OK
        // even when the banks never change, so the write is read back, and a
        // few signature properties are sampled to see whether they move.
        // Slots past seven are probed too: a body with a film-simulation dial
        // has FS1-FS3 on top of C1-C7, and they may share this selector.
        sb.appendLine()
        sb.appendLine("== Slot selector probe ==")
        val probeSlot = readPropLocked(FujiProps.PRESET_SLOT)?.intValue
        try {
            for (slot in 1..PROBE_SLOTS) {
                val accepted = writePropLocked(FujiProps.PRESET_SLOT, packU16(slot))
                delay(SLOT_SETTLE_MS)
                val readBack = readPropLocked(FujiProps.PRESET_SLOT)?.intValue
                val name = readPropLocked(FujiProps.PRESET_NAME)?.stringValue ?: ""
                val sim = readPropLocked(FujiProps.FILM_SIMULATION)?.intValue
                val dr = readPropLocked(FujiProps.DYNAMIC_RANGE)?.intValue
                val hl = readPropLocked(FujiProps.HIGHLIGHT_TONE)?.intValue
                val sharp = readPropLocked(FujiProps.SHARPNESS)?.intValue
                val grain = readPropLocked(FujiProps.GRAIN_EFFECT)?.intValue
                sb.appendLine(
                    "  write %2d -> %-8s readback=%-6s sim=%-4s dr=%-5s hl=%-4s sharp=%-4s grain=%-4s name=\"%s\"".format(
                        slot,
                        if (accepted) "ok" else "REFUSED",
                        readBack?.toString() ?: "?",
                        sim?.toString() ?: "-",
                        dr?.toString() ?: "-",
                        hl?.toString() ?: "-",
                        sharp?.toString() ?: "-",
                        grain?.toString() ?: "-",
                        name,
                    )
                )
            }
        } finally {
            probeSlot?.let { writePropLocked(FujiProps.PRESET_SLOT, packU16(it)) }
        }

        // Then the per-slot scan, which is only meaningful if D18C works.
        val originalSlot = readPropLocked(FujiProps.PRESET_SLOT)?.intValue
        sb.appendLine()
        sb.appendLine("== Banks C1-C7 ==")
        sb.appendLine("(slot selector currently reads: ${originalSlot ?: "<unreadable>"})")
        try {
            for (slot in 1..FujiProps.SLOT_COUNT) {
                val selected = writePropLocked(FujiProps.PRESET_SLOT, packU16(slot))
                delay(SLOT_SETTLE_MS)
                sb.appendLine("-- C$slot (select ${if (selected) "ok" else "REFUSED"}) --")
                if (!selected) continue
                sb.appendLine("  " + describeProp(FujiProps.PRESET_NAME))
                for (propId in FujiProps.FIRST_PRESET_PROP..FujiProps.LAST_PRESET_PROP) {
                    sb.appendLine("  " + describeProp(propId))
                }
            }
        } finally {
            originalSlot?.let { writePropLocked(FujiProps.PRESET_SLOT, packU16(it)) }
        }

        sb.toString()
    }

    /**
     * Enumerate storages and objects.
     *
     * In backup/restore mode the camera may present its settings as an object
     * rather than as properties, which would explain why nothing in the
     * property space reflects the FS recipes. Everything here is read-only:
     * GetStorageIDs, GetStorageInfo, GetObjectHandles, GetObjectInfo.
     */
    suspend fun dumpStorage(): String = io {
        val sb = StringBuilder()
        sb.appendLine("== Storage ==")

        val storageIds = try {
            val res = transport.sendCommand(PtpOp.GET_STORAGE_IDS)
            if (res.code != PtpResp.OK) {
                sb.appendLine("GetStorageIDs: ${PtpResp.name(res.code)}")
                emptyList()
            } else {
                org.nemo.fujibanks.usb.PtpReader(res.data).u32Array()
            }
        } catch (e: Exception) {
            sb.appendLine("GetStorageIDs threw: ${e.message}")
            emptyList()
        }

        sb.appendLine("storage ids (${storageIds.size}): " +
            storageIds.joinToString(" ") { "%08X".format(it) })

        for (id in storageIds) {
            sb.appendLine()
            sb.appendLine("-- storage %08X --".format(id))

            runCatching {
                transport.sendCommand(PtpOp.GET_STORAGE_INFO, listOf(id.toInt()))
            }.getOrNull()?.let { res ->
                if (res.code == PtpResp.OK) {
                    val r = org.nemo.fujibanks.usb.PtpReader(res.data)
                    sb.appendLine("  storageType=${r.u16()} fsType=${r.u16()} access=${r.u16()}")
                    sb.appendLine("  maxCapacity=${r.u32()} freeSpace=${r.u32()}")
                } else {
                    sb.appendLine("  GetStorageInfo: ${PtpResp.name(res.code)}")
                }
            }

            runCatching {
                transport.sendCommand(PtpOp.GET_NUM_OBJECTS, listOf(id.toInt(), 0, 0))
            }.getOrNull()?.let { res ->
                sb.appendLine(
                    if (res.code == PtpResp.OK) "  numObjects=${res.params.firstOrNull()}"
                    else "  GetNumObjects: ${PtpResp.name(res.code)}"
                )
            }

            val handles = runCatching {
                val res = transport.sendCommand(
                    PtpOp.GET_OBJECT_HANDLES, listOf(id.toInt(), 0, 0),
                )
                if (res.code != PtpResp.OK) {
                    sb.appendLine("  GetObjectHandles: ${PtpResp.name(res.code)}")
                    emptyList()
                } else {
                    org.nemo.fujibanks.usb.PtpReader(res.data).u32Array().map { it.toInt() }
                }
            }.getOrDefault(emptyList())

            sb.appendLine("  handles (${handles.size})")
            // Only the first few: this is a probe, not a file listing.
            handles.take(MAX_LISTED_OBJECTS).forEach { handle ->
                val info = runCatching {
                    val res = transport.sendCommand(PtpOp.GET_OBJECT_INFO, listOf(handle))
                    if (res.code == PtpResp.OK) parseObjectInfo(handle, res.data) else null
                }.getOrNull()
                if (info == null) {
                    sb.appendLine("    %08X <no info>".format(handle))
                } else {
                    sb.appendLine(
                        "    %08X fmt=%04X size=%-9d \"%s\" %s".format(
                            handle, info.formatCode, info.compressedSize,
                            info.filename, info.captureDate,
                        )
                    )
                }
            }
            if (handles.size > MAX_LISTED_OBJECTS) {
                sb.appendLine("    … ${handles.size - MAX_LISTED_OBJECTS} more")
            }
        }

        sb.toString()
    }

    // -- Property hunting --------------------------------------------------

    /**
     * Read every property code in a range, keeping the ones the camera answers.
     *
     * The advertised property list is not the whole story — cameras routinely
     * answer codes they never list, and the recipe slots behind a film
     * simulation dial (FS1-FS3) are not in any public map. Sweeping the range
     * and diffing two sweeps across a single menu change is the only way to
     * find where a setting actually lives.
     */
    suspend fun scanProperties(
        from: Int = SCAN_FROM,
        to: Int = SCAN_TO,
        onProgress: (done: Int, total: Int, found: Int) -> Unit = { _, _, _ -> },
    ): Map<Int, ByteArray> = io {
        val found = LinkedHashMap<Int, ByteArray>()
        val total = to - from + 1
        for ((index, propId) in (from..to).withIndex()) {
            readPropLocked(propId)?.let { found[propId] = it.bytes }
            if (index % PROGRESS_EVERY == 0) onProgress(index, total, found.size)
        }
        onProgress(total, total, found.size)
        found
    }

    /** One property as a single dump line: value, raw bytes, and its descriptor. */
    private fun describeProp(propId: Int): String {
        val prop = readPropLocked(propId)
            ?: return "%04X %-20s <unsupported>".format(propId, FujiProps.name(propId))

        val descResult = runCatching {
            transport.sendCommand(PtpOp.GET_DEVICE_PROP_DESC, listOf(propId))
        }.getOrNull()
        val desc = descResult?.takeIf { it.code == PtpResp.OK }?.data
            ?.let { runCatching { parsePropDesc(it) }.getOrNull() }
        // A missing descriptor is worth telling apart from an unwritable one.
        val descNote = when {
            desc != null -> ""
            descResult == null -> " desc:throw"
            descResult.code != PtpResp.OK -> " desc:${PtpResp.name(descResult.code)}"
            else -> " desc:unparsed"
        }

        val detail = when {
            desc?.range != null ->
                " range=${desc.range.first}..${desc.range.second} step ${desc.range.third}"
            desc?.allowed != null -> " enum=[${desc.allowed.joinToString(",")}]"
            else -> ""
        }
        val access = when (desc?.writable) {
            true -> "rw"
            false -> "ro"
            null -> "--"
        }
        val shown = prop.stringValue?.let { "\"$it\"" } ?: prop.intValue.toString()

        return "%04X %-20s = %-10s [%s] %s%s%s".format(
            propId, FujiProps.name(propId), shown, prop.bytes.toHex(), access, detail, descNote,
        )
    }

    // -- RAW conversion ----------------------------------------------------

    /** The profile the camera reported for the RAF currently loaded. */
    var baseProfile: ByteArray? = null
        private set

    val rafLoaded: Boolean get() = baseProfile != null

    /**
     * Upload a RAF into the camera's conversion buffer and read back the
     * profile it came in with.
     *
     * Two vendor operations: 0x900C carries the ObjectInfo, 0x900D the file.
     * The ObjectInfo has to be exactly right — a wrong format code is accepted
     * and then silently does nothing.
     */
    suspend fun loadRaf(
        data: ByteArray,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Recipe = io {
        baseProfile = null

        val info = transport.sendDataCommand(
            FUJI_SEND_OBJECT_INFO, listOf(0, 0, 0), Darkroom.rafObjectInfo(data.size),
        )
        if (info.code != PtpResp.OK) throw PtpException(info.code, "SendObjectInfo")

        val sent = transport.sendDataCommand(
            FUJI_SEND_OBJECT, emptyList(), data, RAF_UPLOAD_TIMEOUT_MS,
            onChunk = onProgress,
        )
        if (sent.code != PtpResp.OK) throw PtpException(sent.code, "SendObject")

        val profile = readProfileLocked()
        baseProfile = profile
        log("RAF loaded, profile ${profile.size} bytes")
        Darkroom.recipeFromProfile(profile)
    }

    private fun readProfileLocked(): ByteArray {
        val res = transport.sendCommand(PtpOp.GET_DEVICE_PROP_VALUE, listOf(FujiProps.RAW_CONV_PROFILE))
        if (res.code != PtpResp.OK) throw PtpException(res.code, "GetDevicePropValue(D185)")
        if (res.data.isEmpty()) {
            throw IllegalStateException("Profile is empty — no RAF loaded")
        }
        return res.data
    }

    /**
     * The loaded RAF's conversion profile, parameter by parameter.
     *
     * D185 answers `<unsupported>` until a RAF is in the buffer, so this is the
     * only way to see the block, and the property dump cannot show it — the
     * parameters live at the *end* of a 600-odd byte block and a dump prints
     * the first bytes. Reading it back is how an index gets confirmed instead
     * of assumed: patch one control, dump, and see which number moved.
     */
    suspend fun dumpProfile(): String = io {
        val profile = baseProfile
            ?: return@io "No RAF loaded — open one on Develop first. D185 reports " +
                "<unsupported> with an empty conversion buffer, which is normal."

        val values = Darkroom.fields(profile)
        buildString {
            appendLine("== D185 conversion profile ==")
            appendLine("${profile.size} bytes, ${values.size} parameters")
            appendLine()
            appendLine("first 16 bytes: ${profile.copyOf(16).toHex()}")
            appendLine()
            values.forEachIndexed { i, v ->
                val name = Darkroom.FIELD_NAMES[i] ?: "?"
                appendLine(
                    "  [%2d] %-26s = %-12d 0x%08X".format(i, name, v, v) +
                        if (v == SENTINEL_32) "  <- not set" else ""
                )
            }
        }
    }

    /**
     * Render the loaded RAF through a recipe and bring back the JPEG.
     *
     * The camera does the processing, so the result is what the body itself
     * would have written to the card — not an approximation of it.
     */
    suspend fun convert(recipe: Recipe, exposureBias: Double = 0.0): ByteArray = io {
        val base = baseProfile ?: throw IllegalStateException("No RAF loaded")

        val patched = Darkroom.patch(base, recipe, exposureBias)
        val set = transport.sendDataCommand(
            PtpOp.SET_DEVICE_PROP_VALUE, listOf(FujiProps.RAW_CONV_PROFILE), patched,
        )
        if (set.code != PtpResp.OK) throw PtpException(set.code, "SetDevicePropValue(D185)")

        val trigger = transport.sendDataCommand(
            PtpOp.SET_DEVICE_PROP_VALUE, listOf(FujiProps.START_RAW_CONVERSION), packU16(0),
        )
        if (trigger.code != PtpResp.OK) throw PtpException(trigger.code, "StartRawConversion")

        awaitConvertedJpeg()
    }

    /** Poll the conversion buffer until the JPEG shows up, then take it. */
    private suspend fun awaitConvertedJpeg(): ByteArray {
        val deadline = System.currentTimeMillis() + CONVERT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val res = transport.sendCommand(PtpOp.GET_OBJECT_HANDLES, listOf(-1, 0, 0))
            if (res.code != PtpResp.OK) throw PtpException(res.code, "GetObjectHandles")

            val handles = if (res.data.size >= 8) {
                org.nemo.fujibanks.usb.PtpReader(res.data).u32Array()
            } else emptyList()

            if (handles.isNotEmpty()) {
                val handle = handles.first().toInt()
                val jpeg = transport.sendCommand(PtpOp.GET_OBJECT, listOf(handle), OBJECT_TIMEOUT_MS)
                if (jpeg.code != PtpResp.OK) throw PtpException(jpeg.code, "GetObject")
                // The temporary object is ours to clean up; a failure here is
                // not worth losing the render over.
                runCatching { transport.sendCommand(PtpOp.DELETE_OBJECT, listOf(handle)) }
                log("Converted: ${jpeg.data.size / 1024} KB")
                return jpeg.data
            }
            delay(CONVERT_POLL_MS)
        }
        throw IllegalStateException("Conversion timed out")
    }

    // -- Photos ------------------------------------------------------------

    suspend fun listStorageIds(): List<Long> = io {
        val res = command(PtpOp.GET_STORAGE_IDS, "GetStorageIDs")
        org.nemo.fujibanks.usb.PtpReader(res.data).u32Array()
    }

    suspend fun listObjects(storageId: Long): List<Int> = io {
        // 0x00000000 format = all, 0xFFFFFFFF parent = root of the store.
        val res = command(
            PtpOp.GET_OBJECT_HANDLES,
            "GetObjectHandles",
            listOf(storageId.toInt(), 0, -1),
        )
        org.nemo.fujibanks.usb.PtpReader(res.data).u32Array().map { it.toInt() }
    }

    suspend fun objectInfo(handle: Int): ObjectInfo? = io {
        val res = try {
            transport.sendCommand(PtpOp.GET_OBJECT_INFO, listOf(handle))
        } catch (e: Exception) {
            return@io null
        }
        if (res.code != PtpResp.OK) null
        else runCatching { parseObjectInfo(handle, res.data) }.getOrNull()
    }

    /**
     * Everything on the card worth showing, newest first.
     *
     * Only available in USB CARD READER mode — in RAW CONV. the card is not
     * exposed at all and the storages are empty conversion buffers. Folder
     * objects (format 0x3001) are skipped: they are the DCIM tree, not pictures.
     */
    suspend fun listPhotos(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }):
        List<ObjectInfo> = io {
        val storages = runCatching {
            val res = transport.sendCommand(PtpOp.GET_STORAGE_IDS)
            if (res.code == PtpResp.OK) org.nemo.fujibanks.usb.PtpReader(res.data).u32Array()
            else emptyList()
        }.getOrDefault(emptyList())

        val handles = mutableListOf<Int>()
        for (id in storages) {
            val res = runCatching {
                transport.sendCommand(PtpOp.GET_OBJECT_HANDLES, listOf(id.toInt(), 0, 0))
            }.getOrNull() ?: continue
            if (res.code != PtpResp.OK) continue
            handles += org.nemo.fujibanks.usb.PtpReader(res.data).u32Array().map { it.toInt() }
        }

        val photos = mutableListOf<ObjectInfo>()
        handles.forEachIndexed { index, handle ->
            onProgress(index, handles.size)
            val res = runCatching {
                transport.sendCommand(PtpOp.GET_OBJECT_INFO, listOf(handle))
            }.getOrNull() ?: return@forEachIndexed
            if (res.code != PtpResp.OK) return@forEachIndexed
            val info = runCatching { parseObjectInfo(handle, res.data) }.getOrNull()
                ?: return@forEachIndexed
            if (info.formatCode == FORMAT_ASSOCIATION) return@forEachIndexed
            photos += info
        }
        onProgress(handles.size, handles.size)
        log("Card holds ${photos.size} images")
        photos.sortedByDescending { it.captureDate }
    }

    /**
     * Download one object, reporting progress.
     *
     * Frames off this body run 15-17 MB and RAF larger still, and usbfs caps a
     * single bulk read at 16 KB, so this is a few thousand round trips — the
     * caller needs to be able to show that something is happening.
     */
    suspend fun getObjectWithProgress(
        handle: Int,
        expectedSize: Long,
        onProgress: (received: Long, total: Long) -> Unit,
    ): ByteArray = io {
        onProgress(0, expectedSize)
        val res = transport.sendCommand(
            PtpOp.GET_OBJECT, listOf(handle), OBJECT_TIMEOUT_MS,
            // Against the container's own length, not the size the card
            // reported: they should agree, and the one being read is the one
            // the bar is actually measuring.
            onChunk = { got, total -> onProgress(got, total) },
        )
        if (res.code != PtpResp.OK) throw PtpException(res.code, "GetObject")
        onProgress(res.data.size.toLong(), expectedSize)
        res.data
    }

    /** Download one object's bytes. Large for RAF — expect 25-55 MB. */
    suspend fun getObject(handle: Int): ByteArray = io {
        command(PtpOp.GET_OBJECT, "GetObject", listOf(handle), timeoutMs = OBJECT_TIMEOUT_MS).data
    }

    suspend fun getThumbnail(handle: Int): ByteArray? = io {
        val res = try {
            transport.sendCommand(PtpOp.GET_THUMB, listOf(handle))
        } catch (e: Exception) {
            return@io null
        }
        if (res.code == PtpResp.OK) res.data else null
    }

    // -- Plumbing ----------------------------------------------------------

    private fun command(
        op: Int,
        opName: String,
        params: List<Int> = emptyList(),
        timeoutMs: Int? = null,
    ): UsbTransport.Result {
        val res = if (timeoutMs != null) transport.sendCommand(op, params, timeoutMs)
        else transport.sendCommand(op, params)
        if (res.code != PtpResp.OK) throw PtpException(res.code, opName)
        return res
    }

    private suspend fun <T> io(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    companion object {
        private const val SESSION_ID = 1
        /**
         * "Not set" in a 32-bit profile field: the camera's 0x8000 sentinel
         * sign-extended into the wider slot. Marking these in the dump matters
         * because a field left alone is how the RAF's own EXIF keeps winning.
         */
        private const val SENTINEL_32 = -32768
        /** The camera needs a moment after a slot switch before it accepts writes. */
        private const val SLOT_SETTLE_MS = 250L
        /** C1-C7 plus room for whatever else the selector may address. */
        private const val PROBE_SLOTS = 12
        /** Fuji keeps its vendor properties in this range. */
        private const val SCAN_FROM = 0xD000
        private const val SCAN_TO = 0xD3FF
        private const val PROGRESS_EVERY = 16
        private const val MAX_LISTED_OBJECTS = 40
        /** PTP association object — a folder, not a picture. */
        private const val FORMAT_ASSOCIATION = 0x3001
        private const val OBJECT_TIMEOUT_MS = 120_000
        private const val RAF_UPLOAD_TIMEOUT_MS = 120_000
        private const val CONVERT_TIMEOUT_MS = 60_000L
        private const val CONVERT_POLL_MS = 250L
        /** Fuji vendor operations for pushing a RAF into the conversion buffer. */
        private const val FUJI_SEND_OBJECT_INFO = 0x900C
        private const val FUJI_SEND_OBJECT = 0x900D
    }
}
