package com.example.lifemanager.ui.todo

import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.model.TodoPriority
import com.example.lifemanager.domain.model.TodoStats
import java.time.Instant

data class TodoEditorState(
    val isOpen: Boolean = false,
    val editingId: Long? = null,
    val title: String = "",
    val description: String = "",
    val priority: TodoPriority = TodoPriority.NONE,
    val dueAt: Instant? = null,
    val tagInput: String = "",
    val validationMessage: String? = null,
    val isSaving: Boolean = false,
)

data class TodoUiState(
    val todos: List<Todo> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val filter: TodoFilter = TodoFilter(),
    val stats: TodoStats = TodoStats(0, 0, 0.0),
    val editor: TodoEditorState = TodoEditorState(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)
