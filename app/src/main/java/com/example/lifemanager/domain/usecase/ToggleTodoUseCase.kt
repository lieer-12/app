package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.repository.TodoRepository
import javax.inject.Inject

class ToggleTodoUseCase @Inject constructor(
    private val repository: TodoRepository,
) {
    suspend operator fun invoke(id: Long, completed: Boolean) = repository.setCompleted(id, completed)
}
