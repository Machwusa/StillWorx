package com.machwusa.stillworx.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val column: String,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val syncPending: Boolean = true,
)
