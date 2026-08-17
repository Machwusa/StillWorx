package com.machwusa.stillworx.sync

internal data class SyncedTask(
    val id: String,
    val title: String,
    val column: String,
    val updatedAt: Long,
    val deleted: Boolean,
)
