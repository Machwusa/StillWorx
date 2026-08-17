package com.machwusa.stillworx.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomergeCrdtStoreTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "crdt-tests").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Test
    fun createsMutatesSavesAndLoadsDocument() = runBlocking {
        val file = File(directory, "board.automerge")
        AutomergeCrdtStore(file).also { store ->
            store.load()
            store.applyLocal(listOf(task("one", "Draft", "TODO", 1)))
            assertEquals("Draft", store.tasks().single().title)
            store.applyLocal(listOf(task("one", "Write", "DOING", 2)))
            assertEquals("DOING", store.tasks().single().column)
            store.persist()
            store.close()
        }

        AutomergeCrdtStore(file).also { reloaded ->
            reloaded.load()
            assertEquals(SyncedTask("one", "Write", "DOING", 2, false), reloaded.tasks().single())
            reloaded.close()
        }
        Unit
    }

    @Test
    fun divergentReplicasKeepIndependentChanges() = runBlocking {
        val baseFile = File(directory, "base.automerge")
        val base = AutomergeCrdtStore(baseFile)
        base.load()
        base.applyLocal(listOf(task("seed", "Write article", "TODO", 1)))
        val commonBytes = base.documentBytes()
        base.close()

        val a = AutomergeCrdtStore(File(directory, "a.automerge"))
        val b = AutomergeCrdtStore(File(directory, "b.automerge"))
        a.load()
        b.load()
        // Merge the common document into two new documents so each replica keeps
        // its own actor ID, matching two independently installed Android apps.
        a.merge(commonBytes)
        b.merge(commonBytes)
        a.applyLocal(listOf(task("a", "Research CRDTs", "TODO", 2)))
        b.applyLocal(listOf(task("b", "Review sources", "TODO", 3)))

        a.merge(b.documentBytes())
        b.merge(a.documentBytes())

        assertEquals(setOf("seed", "a", "b"), a.tasks().map { it.id }.toSet())
        assertEquals(a.tasks().toSet(), b.tasks().toSet())
        a.close()
        b.close()
    }

    @Test
    fun concurrentMovesConvergeWithoutApplicationPreference() = runBlocking {
        val seed = AutomergeCrdtStore(File(directory, "seed.automerge"))
        seed.load()
        seed.applyLocal(listOf(task("one", "Write article", "TODO", 1)))
        val commonBytes = seed.documentBytes()
        seed.close()

        val a = AutomergeCrdtStore(File(directory, "move-a.automerge"))
        val b = AutomergeCrdtStore(File(directory, "move-b.automerge"))
        a.load()
        b.load()
        a.merge(commonBytes)
        b.merge(commonBytes)
        a.applyLocal(listOf(task("one", "Write article", "DOING", 2)))
        b.applyLocal(listOf(task("one", "Write article", "DONE", 3)))
        a.merge(b.documentBytes())
        b.merge(a.documentBytes())

        val chosenByAutomerge = a.tasks().single().column
        assertTrue(chosenByAutomerge == "DOING" || chosenByAutomerge == "DONE")
        assertEquals(chosenByAutomerge, b.tasks().single().column)
        a.close()
        b.close()
    }

    private fun task(id: String, title: String, column: String, updatedAt: Long) =
        SyncedTask(id, title, column, updatedAt, deleted = false)
}
