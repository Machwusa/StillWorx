package com.machwusa.stillworx.domain.model

data class Task(
    val id: String,
    val title: String,
    val column: TaskColumn,
    val updatedAt: Long,
)
