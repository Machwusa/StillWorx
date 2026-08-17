package com.machwusa.stillworx.domain.usecase

import com.machwusa.stillworx.domain.model.TaskColumn
import com.machwusa.stillworx.domain.repository.TaskRepository
import javax.inject.Inject

class ObserveTasks @Inject constructor(private val repository: TaskRepository) {
    operator fun invoke() = repository.observeTasks()
}

class CreateTask @Inject constructor(private val repository: TaskRepository) {
    suspend operator fun invoke(title: String): String = repository.create(title.trim())
}

class UpdateTask @Inject constructor(private val repository: TaskRepository) {
    suspend operator fun invoke(id: String, title: String) = repository.updateTitle(id, title.trim())
}

class MoveTask @Inject constructor(private val repository: TaskRepository) {
    suspend operator fun invoke(id: String, column: TaskColumn) = repository.move(id, column)
}

class DeleteTask @Inject constructor(private val repository: TaskRepository) {
    suspend operator fun invoke(id: String) = repository.delete(id)
}
