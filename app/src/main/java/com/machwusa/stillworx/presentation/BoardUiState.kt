package com.machwusa.stillworx.presentation

import com.machwusa.stillworx.domain.model.Task
import com.machwusa.stillworx.domain.model.ConnectionState

data class BoardUiState(
    val tasks: List<Task> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val syncApprovalRequired: Boolean = false,
    val manualReconnectRequired: Boolean = false,
    val editor: TaskEditor? = null,
    val deleteCandidate: Task? = null,
    val message: BoardMessage? = null,
)

data class TaskEditor(
    val taskId: String? = null,
    val title: String = "",
) {
    val isEditing: Boolean get() = taskId != null
    val canSave: Boolean get() = title.isNotBlank()
}

enum class BoardMessage {
    SAVE_FAILED,
}
