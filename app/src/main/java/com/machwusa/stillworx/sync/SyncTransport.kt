package com.machwusa.stillworx.sync

import com.machwusa.stillworx.domain.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SyncTransport {
    val connectionState: StateFlow<ConnectionState>
    val manualReconnectRequired: StateFlow<Boolean>
    val incomingDocuments: Flow<ByteArray>

    fun connect()

    fun retryConnection()

    fun send(document: ByteArray): Boolean

    fun close()
}
