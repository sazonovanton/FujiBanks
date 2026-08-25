package org.nemo.fujibanks

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nemo.fujibanks.data.PhotoStore
import org.nemo.fujibanks.fuji.Recipe

/**
 * The sample-frame reference on a recipe.
 *
 * It is a field on a model that is already on disk in every library and every
 * backup, so the thing worth pinning down is that adding it did not invalidate
 * what is already saved — and that it is a reference, never the image itself.
 */
class RecipePhotoFieldTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a library file written before photos existed still loads`() {
        val old = """{"name":"Kodacolor VR 200","filmSimulation":3,"dynamicRange":200}"""
        val recipe = json.decodeFromString<Recipe>(old)

        assertEquals("Kodacolor VR 200", recipe.name)
        assertNull("a recipe from before the field has no frame", recipe.photoId)
    }

    @Test
    fun `the id survives a round trip and the bytes are not in it`() {
        val recipe = Recipe(name = "Copenhagen Negative", photoId = "abc-123")
        val text = json.encodeToString(Recipe.serializer(), recipe)

        assertEquals(recipe, json.decodeFromString<Recipe>(text))
        // A recipe file is small enough to read and share; an embedded frame
        // would make it neither.
        assert(text.length < 2000) { "recipe JSON should stay small, was ${text.length}" }
    }

    @Test
    fun `duplicating a recipe shares the frame rather than copying it`() {
        val source = Recipe(name = "Eterna", photoId = "frame-1")
        val copy = source.copy(name = "Eterna copy")

        assertEquals(source.photoId, copy.photoId)
    }

    @Test
    fun `a frame does not make two otherwise equal recipes the same`() {
        val plain = Recipe(name = "Provia")
        val withFrame = plain.copy(photoId = "frame-1")

        // Equality is what the Recipes screen ticks with and what Develop's
        // chips compare, so this is worth stating rather than assuming.
        assert(plain != withFrame)
    }

    // -- The sweep, which is what actually deletes a frame ------------------
    //
    // Re-encoding needs Android's BitmapFactory and cannot run here, but the
    // deletion rule is plain file work — and it is the part that loses data if
    // it is wrong, in either direction.

    private fun store(): Pair<PhotoStore, java.io.File> {
        val dir = temp.newFolder()
        return PhotoStore(dir) to dir
    }

    private fun PhotoStore.plant(id: String) = file(id).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `a frame nothing points at is deleted`() = runBlocking {
        val (photos, _) = store()
        val orphan = photos.plant("gone")
        val kept = photos.plant("held")

        photos.sweep(setOf("held"))

        assertFalse("the recipe holding it was deleted", orphan.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun `a frame two recipes share survives one of them going`() = runBlocking {
        val (photos, _) = store()
        // Duplicate copies the reference, so deleting the original must not
        // take the frame out from under the copy.
        val shared = photos.plant("frame-1")

        photos.sweep(setOf("frame-1"))

        assertTrue(shared.exists())
    }

    @Test
    fun `sweeping an empty library clears the folder`() = runBlocking {
        val (photos, dir) = store()
        photos.plant("a")
        photos.plant("b")

        photos.sweep(emptySet())

        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
