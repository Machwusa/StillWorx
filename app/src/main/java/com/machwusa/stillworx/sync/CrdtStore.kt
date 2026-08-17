package com.machwusa.stillworx.sync

internal interface CrdtStore {
    suspend fun load()

    suspend fun applyLocal(tasks: List<SyncedTask>)

    suspend fun merge(remoteDocument: ByteArray)

    suspend fun tasks(): List<SyncedTask>

    suspend fun documentBytes(): ByteArray

    suspend fun persist()

    suspend fun close()
}
