package com.example.lifemanager.domain.model

enum class TodoDateFilter {
    TODAY,
    TOMORROW,
    THIS_WEEK,
    ALL,
}

data class TodoFilter(
    val dateFilter: TodoDateFilter = TodoDateFilter.TODAY,
    val priority: TodoPriority? = null,
    val tagId: Long? = null,
    val query: String = "",
)
