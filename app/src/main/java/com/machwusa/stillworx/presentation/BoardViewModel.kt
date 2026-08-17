package com.machwusa.stillworx.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.machwusa.stillworx.domain.model.ConnectionState
import com.machwusa.stillworx.domain.model.Task
import com.machwusa.stillworx.domain.model.TaskColumn
import com.machwusa.stillworx.domain.usecase.CreateTask
import com.machwusa.stillworx.domain.usecase.DeleteTask
import com.machwusa.stillworx.domain.usecase.MoveTask
import com.machwusa.stillworx.domain.usecase.ApproveSync
import com.machwusa.stillworx.domain.usecase.ObserveConnectionState
import com.machwusa.stillworx.domain.usecase.ObserveSyncApprovalRequired
import com.machwusa.stillworx.domain.usecase.ObserveManualReconnectRequired
import com.machwusa.stillworx.domain.usecase.ObserveTasks
import com.machwusa.stillworx.domain.usecase.RetryConnection
import com.machwusa.stillworx.domain.usecase.UpdateTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BoardViewModel @Inject constructor(
    observeTasks: ObserveTasks,
    observeConnectionState: ObserveConnectionState,
    observeSyncApprovalRequired: ObserveSyncApprovalRequired,
    observeManualReconnectRequired: ObserveManualReconnectRequired,
    private val approveSync: ApproveSync,
    private val retrySyncConnection: RetryConnection,
    private val createTask: CreateTask,
    private val updateTask: UpdateTask,
    private val moveTask: MoveTask,
    private val deleteTask: DeleteTask,
) : ViewModel() {
    private val editor = MutableStateFlow<TaskEditor?>(null)
    private val deleteCandidate = MutableStateFlow<Task?>(null)
    private val message = MutableStateFlow<BoardMessage?>(null)

    private val syncState = combine(
        observeConnectionState(),
        observeSyncApprovalRequired(),
        observeManualReconnectRequired(),
    ) { connectionState, approvalRequired, reconnectRequired ->
        Triple(connectionState, approvalRequired, reconnectRequired)
    }

    val uiState: StateFlow<BoardUiState> = combine(
        observeTasks(),
        syncState,
        editor,
        deleteCandidate,
        message,
    ) { tasks, sync, currentEditor, candidate, currentMessage ->
        BoardUiState(
            tasks = tasks,
            connectionState = sync.first,
            syncApprovalRequired = sync.second,
            manualReconnectRequired = sync.third,
            editor = currentEditor,
            deleteCandidate = candidate,
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardUiState())

    fun beginCreate() {
        editor.value = TaskEditor()
    }

    fun syncNow() {
        approveSync()
    }

    fun retryConnection() {
        retrySyncConnection()
    }

    fun beginEdit(task: Task) {
        editor.value = TaskEditor(taskId = task.id, title = task.title)
    }

    fun updateEditorTitle(title: String) {
        editor.value = editor.value?.copy(title = title)
    }

    fun dismissEditor() {
        editor.value = null
    }

    fun saveEditor() {
        val form = editor.value?.takeIf(TaskEditor::canSave) ?: return
        viewModelScope.launch {
            runCatching {
                if (form.taskId == null) createTask(form.title) else updateTask(form.taskId, form.title)
            }.onSuccess { editor.value = null }
                .onFailure { message.value = BoardMessage.SAVE_FAILED }
        }
    }

    fun move(task: Task, column: TaskColumn) {
        if (task.column == column) return
        viewModelScope.launch {
            runCatching { moveTask(task.id, column) }
                .onFailure { message.value = BoardMessage.SAVE_FAILED }
        }
    }

    fun requestDelete(task: Task) {
        deleteCandidate.value = task
    }

    fun dismissDelete() {
        deleteCandidate.value = null
    }

    fun confirmDelete() {
        val task = deleteCandidate.value ?: return
        viewModelScope.launch {
            runCatching { deleteTask(task.id) }
                .onSuccess { deleteCandidate.value = null }
                .onFailure { message.value = BoardMessage.SAVE_FAILED }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

}
