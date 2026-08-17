package com.machwusa.stillworx.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE isDeleted = 0 ORDER BY updatedAt ASC, id ASC")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY id ASC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE syncPending = 1 ORDER BY id ASC")
    suspend fun getPending(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("UPDATE tasks SET title = :title, updatedAt = :updatedAt, syncPending = 1 WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE tasks SET `column` = :column, updatedAt = :updatedAt, syncPending = 1 WHERE id = :id")
    suspend fun move(id: String, column: String, updatedAt: Long)

    @Query("UPDATE tasks SET isDeleted = 1, updatedAt = :updatedAt, syncPending = 1 WHERE id = :id")
    suspend fun tombstone(id: String, updatedAt: Long)

    @Query("UPDATE tasks SET syncPending = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
