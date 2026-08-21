package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.repository.TodoRepository
import javax.inject.Inject

class SaveTodoUseCase @Inject constructor(
    private val repository: TodoRepository,
) {
    suspend operator fun invoke(todo: Todo, tagNames: List<String>): Long =
        repository.saveTodo(todo, tagNames)
}
