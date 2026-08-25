package org.nemo.fujibanks.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nemo.fujibanks.fuji.Bank
import org.nemo.fujibanks.fuji.BankSnapshot
import org.nemo.fujibanks.fuji.Recipe
import org.nemo.fujibanks.fuji.RecipePack
import java.io.File

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Snapshots of what the camera held before a write.
 *
 * Every write takes one first, so undo is always available — this is the only
 * thing standing between an experiment and a lost set of banks.
 */
class BackupStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "backups"))

    init { dir.mkdirs() }

    suspend fun save(snapshot: BankSnapshot): File = withContext(Dispatchers.IO) {
        val file = File(dir, "${snapshot.takenAt}-${snapshot.cameraModel.sanitised()}.json")
        file.writeText(json.encodeToString(snapshot))
        file
    }

    /**
     * Save, unless the newest snapshot already holds exactly this.
     *
     * Pressing the button twice, or installing two sets in a row without
     * touching the camera in between, produced a pile of byte-identical files
     * that told the photographer nothing.
     *
     * The comparison is against [latest] — the newest snapshot of any slot set,
     * not the newest one covering the same slots. Undo restores whatever
     * [latest] returns, so skipping a save is only safe while the file that
     * stays newest is the one that would have been written.
     */
    suspend fun saveIfChanged(snapshot: BankSnapshot): SaveResult {
        val newest = latest()
        if (newest != null && newest.holdsSameAs(snapshot)) return SaveResult.Duplicate(newest)
        save(snapshot)
        return SaveResult.Saved(snapshot)
    }

    /** Newest first. */
    suspend fun list(): List<BankSnapshot> = withContext(Dispatchers.IO) {
        dir.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<BankSnapshot>(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.takenAt }
            ?: emptyList()
    }

    /** The snapshot a single undo would restore. */
    suspend fun latest(): BankSnapshot? = list().firstOrNull()

    suspend fun delete(takenAt: Long) = withContext(Dispatchers.IO) {
        dir.listFiles()?.firstOrNull { it.name.startsWith("$takenAt-") }?.delete()
        Unit
    }
}

/** What [BackupStore.saveIfChanged] did. */
sealed interface SaveResult {
    data class Saved(val snapshot: BankSnapshot) : SaveResult
    /** Nothing was written; [existing] already holds the same banks. */
    data class Duplicate(val existing: BankSnapshot) : SaveResult
}

/**
 * Same camera, same slots, same recipes — everything but the clock.
 *
 * [Recipe] is a plain data class with no wire bytes on it, so structural
 * equality is the whole comparison.
 */
private fun BankSnapshot.holdsSameAs(other: BankSnapshot): Boolean =
    cameraModel == other.cameraModel &&
        serialNumber == other.serialNumber &&
        banks.sortedBy { it.slot } == other.banks.sortedBy { it.slot }

/** Named sets of up to seven recipes, plus the loose recipe library. */
class PackStore(context: Context) {

    private val packFile = File(context.filesDir, "packs.json")
    private val recipeFile = File(context.filesDir, "recipes.json")

    suspend fun loadPacks(): List<RecipePack> = withContext(Dispatchers.IO) {
        if (!packFile.exists()) emptyList()
        else runCatching { json.decodeFromString<List<RecipePack>>(packFile.readText()) }
            .getOrDefault(emptyList())
    }

    suspend fun savePacks(packs: List<RecipePack>) = withContext(Dispatchers.IO) {
        packFile.writeText(json.encodeToString(packs))
    }

    suspend fun loadRecipes(): List<Recipe> = withContext(Dispatchers.IO) {
        if (!recipeFile.exists()) emptyList()
        else runCatching { json.decodeFromString<List<Recipe>>(recipeFile.readText()) }
            .getOrDefault(emptyList())
    }

    suspend fun saveRecipes(recipes: List<Recipe>) = withContext(Dispatchers.IO) {
        recipeFile.writeText(json.encodeToString(recipes))
    }

    suspend fun addRecipe(recipe: Recipe): List<Recipe> {
        val updated = loadRecipes() + recipe
        saveRecipes(updated)
        return updated
    }
}

/**
 * Sample frames, one file per photo, named by the id a recipe carries.
 *
 * These are previews and nothing else: the point of a sample frame is to see
 * what the recipe does, and a 17 MB original tells you no more about that than
 * a 1600px one does while costing a slow decode every time a list scrolls. So
 * everything is re-encoded on the way in — scaled down, JPEG, stripped of
 * whatever the source carried — and the original is never kept.
 */
class PhotoStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "photos"))

    init { dir.mkdirs() }

    fun file(id: String): File = File(dir, "$id.jpg")

    /**
     * Re-encode [bytes] into the store and return the new id, or null if it
     * could not be read as an image at all.
     *
     * Decoding happens twice on purpose: once for the bounds alone, so the full
     * bitmap is never held at its original size — a 40 MP frame is 160 MB as
     * ARGB_8888 and would take the app down before it could be scaled.
     */
    suspend fun save(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching null

            // inSampleSize only halves, so it gets us to within 2x of the
            // target cheaply; the exact fit comes after.
            var sample = 1
            while (longest / sample > MAX_EDGE * 2) sample *= 2

            val decoded = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@runCatching null

            val scaled = scaleToFit(decoded)
            val id = java.util.UUID.randomUUID().toString()
            file(id).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            id
        }.getOrNull()
    }

    private fun scaleToFit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Delete every file no recipe points at any more.
     *
     * Duplicating a recipe shares its photo id, so a photo is deletable only
     * once nothing refers to it — which is a question about the whole library,
     * not about the recipe being deleted.
     */
    suspend fun sweep(keep: Set<String>) = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension !in keep) file.delete()
        }
        Unit
    }

    private companion object {
        /**
         * Long edge in pixels, and the JPEG quality to write it at.
         *
         * The ceiling that matters is the editor header — full screen width,
         * 150dp tall, cropped — which is about 1280px on this phone. 1024 sits
         * just under it, for a 25% upscale in the one place a frame is drawn
         * largest and none at all in the lists.
         *
         * Measured on a 26 MP frame: 1024/q70 lands around 100 KB, against
         * 360 KB at the 1600/q85 this started at. q70 is where JPEG artefacts
         * are still invisible at this size; going much below either number
         * starts to show on that strip.
         */
        const val MAX_EDGE = 1024
        const val QUALITY = 70
    }
}

/** Serialisation for the share-a-pack-as-a-file path. */
object PackSerialisation {
    fun encodePack(pack: RecipePack): String = json.encodeToString(pack)
    fun decodePack(text: String): RecipePack = json.decodeFromString(text)

    fun encodeSnapshot(snapshot: BankSnapshot): String = json.encodeToString(snapshot)
    fun decodeSnapshot(text: String): BankSnapshot = json.decodeFromString(text)

    /**
     * A snapshot read as a pack, so a backup can be loaded straight back into
     * the camera or used as the starting point for a new set.
     */
    fun snapshotAsPack(snapshot: BankSnapshot): RecipePack = RecipePack(
        name = "${snapshot.cameraModel} backup",
        recipes = snapshot.banks.sortedBy { it.slot }.map { it.recipe },
        createdAt = snapshot.takenAt,
    )

    fun packAsBanks(pack: RecipePack): List<Bank> =
        pack.recipes.mapIndexed { index, recipe -> Bank(index + 1, recipe) }
}

private fun String.sanitised() = replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "camera" }
