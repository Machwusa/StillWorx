package com.machwusa.stillworx.domain.repository

import com.machwusa.stillworx.domain.model.Task
import com.machwusa.stillworx.domain.model.TaskColumn
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun observeTasks(): Flow<List<Task>>

    suspend fun create(title: String): String

    suspend fun updateTitle(id: String, title: String)

    suspend fun move(id: String, column: TaskColumn)

    suspend fun delete(id: String)
}
