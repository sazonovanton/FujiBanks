package org.nemo.fujibanks.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nemo.fujibanks.data.PhotoStore
import org.nemo.fujibanks.fuji.Recipe

/**
 * The sample frame a recipe can carry, and the two ways one gets attached.
 *
 * A recipe's identity on screen is the computed artwork — that is a
 * characterisation of the settings and stays that way. A sample frame is a
 * different claim: this is a photograph someone took with these settings. So it
 * replaces the artwork where it exists, and is labelled as what it is rather
 * than being left to look like a render.
 */

/** Decoded frames, keyed by id and the width they were decoded for. */
private val photoCache = LruCache<String, ImageBitmap>(24)

/**
 * The recipe's sample frame, decoded no larger than it will be drawn.
 *
 * The stored file is already capped at 1600px, but a library row draws it 52dp
 * wide; decoding the whole thing for that on every scroll is the difference
 * between a list that moves and one that stutters.
 */
@Composable
fun rememberRecipePhoto(id: String?, width: Dp): ImageBitmap? {
    if (id == null) return null
    val context = LocalContext.current
    val widthPx = with(LocalDensity.current) { width.roundToPx() }.coerceAtLeast(1)
    val key = "$id@$widthPx"

    val state by produceState<ImageBitmap?>(photoCache.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = PhotoStore(context).file(id)
                if (!file.exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
                BitmapFactory
                    .decodeFile(
                        file.path,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    ?.asImageBitmap()
            }.getOrNull()
        }?.also { photoCache.put(key, it) }
    }
    return state
}

/**
 * What stands for a recipe in a frame: its sample photo when there is one, the
 * computed artwork when there is not.
 */
@Composable
fun RecipeFrame(
    recipe: Recipe,
    modifier: Modifier = Modifier,
    width: Dp = 240.dp,
    showCurve: Boolean = true,
) {
    val photo = rememberRecipePhoto(recipe.photoId, width)
    if (photo != null) {
        Image(
            photo,
            contentDescription = "Sample frame for ${recipe.name.ifEmpty { "this recipe" }}",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        RecipeArtwork(recipe, modifier.fillMaxSize(), showCurve = showCurve)
    }
}

/**
 * Attach, replace or drop a sample frame.
 *
 * Two ways in, because a recipe usually arrives one of two ways: the frame is
 * already in the phone's gallery, or it has just been copied out of a browser
 * alongside the text of the recipe itself.
 */
@Composable
fun PhotoAttachRow(
    current: String?,
    viewModel: BanksViewModel,
    onAttached: (String?) -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importPhoto(uri) { onAttached(it) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) {
            Text(if (current == null) "Add a photo" else "Replace")
        }
        OutlinedButton(onClick = {
            val uri = clipboardImage(context)
            if (uri == null) viewModel.sayNoImageInClipboard()
            else viewModel.importPhoto(uri) { onAttached(it) }
        }) { Text("Paste") }
        if (current != null) {
            TextButton(onClick = { onAttached(null) }) {
                Text("Remove", color = Film.TextMuted)
            }
        }
    }
}

/**
 * An image on the clipboard, if there is one.
 *
 * Copying a picture puts a `content://` URI on the clipboard, not the bytes.
 * Some apps put the same URI there as text instead, which is why a text item is
 * worth a second look before giving up — and why the type is checked through
 * the resolver rather than trusted from the clip description.
 */
private fun clipboardImage(context: Context): Uri? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = manager?.primaryClip ?: return null
    for (i in 0 until clip.itemCount) {
        val item = clip.getItemAt(i)
        val candidate = item.uri ?: item.text?.toString()?.takeIf {
            it.startsWith("content://") || it.startsWith("file://")
        }?.let(Uri::parse)
        if (candidate == null) continue
        val type = runCatching { context.contentResolver.getType(candidate) }.getOrNull()
        if (type?.startsWith("image/") == true) return candidate
    }
    return null
}
