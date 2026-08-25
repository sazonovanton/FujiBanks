package org.nemo.fujibanks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nemo.fujibanks.data.BackupStore
import org.nemo.fujibanks.data.SaveResult
import org.nemo.fujibanks.fuji.Bank
import org.nemo.fujibanks.fuji.BankSnapshot
import org.nemo.fujibanks.fuji.FilmSim
import org.nemo.fujibanks.fuji.Recipe

/**
 * The duplicate-suppression rule, which exists because a pile of byte-identical
 * backups told the photographer nothing about what the camera held.
 */
class BackupStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = BackupStore(temp.newFolder())

    private fun snapshot(takenAt: Long, vararg banks: Bank) = BankSnapshot(
        cameraModel = "X-T30 III",
        serialNumber = "SN1",
        takenAt = takenAt,
        banks = banks.toList(),
    )

    private fun bank(slot: Int, sim: Int = FilmSim.PROVIA) =
        Bank(slot, Recipe(name = "C$slot", filmSimulation = sim))

    @Test
    fun `identical banks are not saved twice`() = runBlocking {
        val store = store()
        val first = snapshot(1000, bank(1), bank(2))
        assertTrue(store.saveIfChanged(first) is SaveResult.Saved)

        val again = store.saveIfChanged(snapshot(2000, bank(1), bank(2)))
        assertTrue(again is SaveResult.Duplicate)
        assertEquals(1000L, (again as SaveResult.Duplicate).existing.takenAt)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a changed recipe is saved`() = runBlocking {
        val store = store()
        store.saveIfChanged(snapshot(1000, bank(1), bank(2)))
        val changed = snapshot(2000, bank(1), bank(2, FilmSim.ACROS))
        assertTrue(store.saveIfChanged(changed) is SaveResult.Saved)
        assertEquals(2, store.list().size)
        assertEquals(2000L, store.latest()!!.takenAt)
    }

    @Test
    fun `slot order does not count as a change`() = runBlocking {
        val store = store()
        store.saveIfChanged(snapshot(1000, bank(1), bank(2)))
        assertTrue(store.saveIfChanged(snapshot(2000, bank(2), bank(1))) is SaveResult.Duplicate)
    }

    @Test
    fun `a different slot set is saved even when the recipes match`() = runBlocking {
        val store = store()
        store.saveIfChanged(snapshot(1000, bank(1), bank(2)))
        assertTrue(store.saveIfChanged(snapshot(2000, bank(1))) is SaveResult.Saved)
    }

    /**
     * Undo restores whatever [BackupStore.latest] returns, so a save may only
     * be skipped while the newest file on disk is the twin of the one skipped —
     * never merely some older file with the same banks.
     */
    @Test
    fun `a duplicate of an older backup is still saved when a newer one differs`() = runBlocking {
        val store = store()
        store.saveIfChanged(snapshot(1000, bank(1), bank(2)))
        store.saveIfChanged(snapshot(2000, bank(1), bank(2, FilmSim.ACROS)))

        val backToTheOldBanks = snapshot(3000, bank(1), bank(2))
        assertTrue(store.saveIfChanged(backToTheOldBanks) is SaveResult.Saved)
        assertEquals(3000L, store.latest()!!.takenAt)
    }

    @Test
    fun `a snapshot from another body is never a duplicate`() = runBlocking {
        val store = store()
        store.saveIfChanged(snapshot(1000, bank(1)))
        val otherBody = snapshot(2000, bank(1)).copy(serialNumber = "SN2")
        assertTrue(store.saveIfChanged(otherBody) is SaveResult.Saved)
    }
}
