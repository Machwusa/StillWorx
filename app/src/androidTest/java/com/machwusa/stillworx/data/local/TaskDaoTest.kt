package com.machwusa.stillworx.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.machwusa.stillworx.domain.model.TaskColumn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: TaskDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.taskDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun insertUpdateMoveAndTombstoneRemainOfflineFirst() = runBlocking {
        dao.upsert(task("one", "Draft", TaskColumn.TODO, 1))
        assertEquals("Draft", dao.observeActive().first().single().title)

        dao.updateTitle("one", "Write article", 2)
        assertEquals("Write article", dao.observeActive().first().single().title)

        dao.move("one", TaskColumn.DOING.name, 3)
        assertEquals(TaskColumn.DOING.name, dao.observeActive().first().single().column)
        assertTrue(dao.getAll().single().syncPending)

        dao.tombstone("one", 4)
        assertTrue(dao.observeActive().first().isEmpty())
        val tombstone = dao.getAll().single()
        assertTrue(tombstone.isDeleted)
        assertTrue(tombstone.syncPending)
    }

    @Test
    fun activeFlowExcludesDeletedTasks() = runBlocking {
        dao.upsertAll(
            listOf(
                task("active", "Visible", TaskColumn.TODO, 1),
                task("deleted", "Hidden", TaskColumn.DONE, 2).copy(isDeleted = true),
            ),
        )

        val visible = dao.observeActive().first()
        assertEquals(listOf("active"), visible.map { it.id })
        assertFalse(visible.any { it.isDeleted })
    }

    private fun task(id: String, title: String, column: TaskColumn, updatedAt: Long) = TaskEntity(
        id = id,
        title = title,
        column = column.name,
        updatedAt = updatedAt,
    )
}
