package com.machwusa.stillworx.domain.repository

import com.machwusa.stillworx.domain.model.ConnectionState
import kotlinx.coroutines.flow.StateFlow

interface SyncStatusRepository {
    val connectionState: StateFlow<ConnectionState>
    val syncApprovalRequired: StateFlow<Boolean>
    val manualReconnectRequired: StateFlow<Boolean>

    fun approveSync()

    fun retryConnection()
}
