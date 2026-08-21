package com.example.lifemanager.domain.repository

import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoFilter
import kotlinx.coroutines.flow.Flow

interface TodoRepository {
    fun observeTodos(filter: TodoFilter): Flow<List<Todo>>
    fun observeTags(): Flow<List<Tag>>
    suspend fun getAllTodos(): List<Todo>
    suspend fun saveTodo(todo: Todo, tagNames: List<String>): Long
    suspend fun deleteTodo(id: Long)
    suspend fun setCompleted(id: Long, completed: Boolean)
}
