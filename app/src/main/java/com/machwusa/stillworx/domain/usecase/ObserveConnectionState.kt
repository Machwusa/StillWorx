package com.machwusa.stillworx.domain.usecase

import com.machwusa.stillworx.domain.repository.SyncStatusRepository
import javax.inject.Inject

class ObserveConnectionState @Inject constructor(
    private val repository: SyncStatusRepository,
) {
    operator fun invoke() = repository.connectionState
}

class ObserveSyncApprovalRequired @Inject constructor(
    private val repository: SyncStatusRepository,
) {
    operator fun invoke() = repository.syncApprovalRequired
}

class ApproveSync @Inject constructor(
    private val repository: SyncStatusRepository,
) {
    operator fun invoke() = repository.approveSync()
}

class ObserveManualReconnectRequired @Inject constructor(
    private val repository: SyncStatusRepository,
) {
    operator fun invoke() = repository.manualReconnectRequired
}

class RetryConnection @Inject constructor(
    private val repository: SyncStatusRepository,
) {
    operator fun invoke() = repository.retryConnection()
}
