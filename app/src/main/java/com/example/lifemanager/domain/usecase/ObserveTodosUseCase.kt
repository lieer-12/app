package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.repository.TodoRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveTodosUseCase @Inject constructor(
    private val repository: TodoRepository,
) {
    operator fun invoke(filter: TodoFilter): Flow<List<Todo>> = repository.observeTodos(filter)
}
