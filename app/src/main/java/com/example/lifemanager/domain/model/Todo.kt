package com.example.lifemanager.domain.model

import java.time.Instant

data class Todo(
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val priority: TodoPriority = TodoPriority.NONE,
    val dueAt: Instant? = null,
    val isCompleted: Boolean = false,
    val completedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val tagIds: Set<Long> = emptySet(),
    val tagNames: List<String> = emptyList(),
)
