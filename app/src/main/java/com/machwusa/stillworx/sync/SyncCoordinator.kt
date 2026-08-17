package com.machwusa.stillworx.sync

import android.util.Log
import com.machwusa.stillworx.data.local.TaskDao
import com.machwusa.stillworx.data.local.TaskEntity
import com.machwusa.stillworx.data.repository.LocalChangeNotifier
import com.machwusa.stillworx.domain.model.ConnectionState
import com.machwusa.stillworx.domain.repository.SyncStatusRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class SyncCoordinator @Inject internal constructor(
    private val taskDao: TaskDao,
    private val crdtStore: CrdtStore,
    private val transport: SyncTransport,
    private val scope: CoroutineScope,
) : LocalChangeNotifier, SyncStatusRepository {
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val pendingRemoteDocuments = mutableListOf<ByteArray>()
    private val _syncApprovalRequired = MutableStateFlow(false)
    private var hasConnectedOnce = false
    private var wentOfflineAfterConnection = false
    private var syncPausedForApproval = false
    override val connectionState: StateFlow<ConnectionState> = transport.connectionState
    override val syncApprovalRequired: StateFlow<Boolean> = _syncApprovalRequired.asStateFlow()
    override val manualReconnectRequired: StateFlow<Boolean> = transport.manualReconnectRequired

    init {
        start()
    }

    private fun start() {
        scope.launch {
            transport.incomingDocuments.collect { events.send(Event.RemoteDocument(it)) }
        }
        scope.launch {
            transport.connectionState.collect { events.send(Event.ConnectionChanged(it)) }
        }
        scope.launch {
            runCatching {
                crdtStore.load()
                pushPendingRoomChanges()
                applyCrdtToRoom()
                transport.connect()
            }.onFailure { Log.e(TAG, "Could not initialize synchronization", it) }

            for (event in events) {
                runCatching {
                    when (event) {
                        Event.LocalChange -> pushPendingRoomChanges()
                        Event.ApproveSync -> applyStagedRemoteDocuments()
                        Event.RetryConnection -> transport.retryConnection()
                        is Event.ConnectionChanged -> handleConnectionState(event.state)
                        is Event.RemoteDocument -> handleRemoteDocument(event.bytes)
                    }
                }.onFailure { Log.e(TAG, "Synchronization event failed", it) }
            }
        }
    }

    override fun notifyLocalChange() {
        events.trySend(Event.LocalChange)
    }

    override fun approveSync() {
        events.trySend(Event.ApproveSync)
    }

    override fun retryConnection() {
        events.trySend(Event.RetryConnection)
    }

    suspend fun stop() {
        transport.close()
        crdtStore.persist()
        crdtStore.close()
        events.close()
    }

    private suspend fun pushPendingRoomChanges() {
        val pending = taskDao.getPending()
        if (pending.isEmpty()) return
        crdtStore.applyLocal(pending.map(TaskEntity::toSyncedTask))
        crdtStore.persist()
        taskDao.markSynced(pending.map(TaskEntity::id))
        if (connectionState.value == ConnectionState.CONNECTED && !syncPausedForApproval) {
            sendCurrentDocument()
        }
    }

    private suspend fun handleConnectionState(state: ConnectionState) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                if (hasConnectedOnce) wentOfflineAfterConnection = true
            }
            ConnectionState.CONNECTING -> Unit
            ConnectionState.CONNECTED -> {
                if (hasConnectedOnce && wentOfflineAfterConnection) {
                    syncPausedForApproval = true
                    _syncApprovalRequired.value = true
                } else {
                    hasConnectedOnce = true
                }
                wentOfflineAfterConnection = false
                sendCurrentDocument()
            }
        }
    }

    private suspend fun handleRemoteDocument(bytes: ByteArray) {
        if (syncPausedForApproval) {
            pendingRemoteDocuments += bytes.copyOf()
        } else {
            mergeRemote(bytes)
        }
    }

    private suspend fun applyStagedRemoteDocuments() {
        if (!syncPausedForApproval) return
        pushPendingRoomChanges()
        pendingRemoteDocuments.forEach { crdtStore.merge(it) }
        pendingRemoteDocuments.clear()
        crdtStore.persist()
        applyCrdtToRoom()
        syncPausedForApproval = false
        _syncApprovalRequired.value = false
        if (connectionState.value == ConnectionState.CONNECTED) sendCurrentDocument()
    }

    private suspend fun mergeRemote(bytes: ByteArray) {
        pushPendingRoomChanges()
        crdtStore.merge(bytes)
        crdtStore.persist()
        applyCrdtToRoom()
    }

    private suspend fun applyCrdtToRoom() {
        val merged = crdtStore.tasks().map(SyncedTask::toEntity)
        if (merged.isNotEmpty()) taskDao.upsertAll(merged)
    }

    private suspend fun sendCurrentDocument() {
        if (!transport.send(crdtStore.documentBytes())) {
            Log.i(TAG, "Document remains local until the next connection")
        }
    }

    private sealed interface Event {
        data object LocalChange : Event
        data object ApproveSync : Event
        data object RetryConnection : Event
        data class ConnectionChanged(val state: ConnectionState) : Event
        data class RemoteDocument(val bytes: ByteArray) : Event
    }

    private companion object {
        const val TAG = "SyncCoordinator"
    }
}

private fun TaskEntity.toSyncedTask() = SyncedTask(id, title, column, updatedAt, isDeleted)

private fun SyncedTask.toEntity() = TaskEntity(
    id = id,
    title = title,
    column = column,
    updatedAt = updatedAt,
    isDeleted = deleted,
    syncPending = false,
)
