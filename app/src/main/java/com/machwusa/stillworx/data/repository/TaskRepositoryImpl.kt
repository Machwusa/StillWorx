package com.machwusa.stillworx.data.repository

import com.machwusa.stillworx.data.local.TaskDao
import com.machwusa.stillworx.data.local.TaskEntity
import com.machwusa.stillworx.domain.model.Task
import com.machwusa.stillworx.domain.model.TaskColumn
import com.machwusa.stillworx.domain.repository.TaskRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val taskDao: TaskDao,
    private val localChangeNotifier: LocalChangeNotifier,
) : TaskRepository {
    private val now: () -> Long = System::currentTimeMillis
    private val newId: () -> String = { UUID.randomUUID().toString() }
    override fun observeTasks() = taskDao.observeActive().map { tasks -> tasks.map(TaskEntity::toDomain) }

    override suspend fun create(title: String): String {
        require(title.isNotBlank())
        val id = newId()
        taskDao.upsert(
            TaskEntity(
                id = id,
                title = title,
                column = TaskColumn.TODO.name,
                updatedAt = now(),
            ),
        )
        localChangeNotifier.notifyLocalChange()
        return id
    }

    override suspend fun updateTitle(id: String, title: String) {
        require(title.isNotBlank())
        taskDao.updateTitle(id, title, now())
        localChangeNotifier.notifyLocalChange()
    }

    override suspend fun move(id: String, column: TaskColumn) {
        taskDao.move(id, column.name, now())
        localChangeNotifier.notifyLocalChange()
    }

    override suspend fun delete(id: String) {
        taskDao.tombstone(id, now())
        localChangeNotifier.notifyLocalChange()
    }
}

internal fun TaskEntity.toDomain() = Task(
    id = id,
    title = title,
    column = TaskColumn.valueOf(column),
    updatedAt = updatedAt,
)
