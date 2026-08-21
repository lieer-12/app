package com.example.lifemanager.data.repository

import androidx.room.withTransaction
import com.example.lifemanager.data.local.LifeManagerDatabase
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef
import com.example.lifemanager.domain.model.Tag
import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoDateFilter
import com.example.lifemanager.domain.model.TodoFilter
import com.example.lifemanager.domain.repository.TodoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class TodoRepositoryImpl @Inject constructor(
    private val database: LifeManagerDatabase,
) : TodoRepository {
    private val todoDao = database.todoDao()
    private val tagDao = database.tagDao()
    private val todoTagDao = database.todoTagDao()
    private val zone: ZoneId = ZoneId.systemDefault()

    override fun observeTodos(filter: TodoFilter): Flow<List<Todo>> =
        todoDao.observeAll().map { entities ->
            entities.map { entity ->
                entity.toDomain(todoTagDao.getTagsForTodo(entity.id))
            }.filter { todo -> todo.matches(filter) }
        }

    override fun observeTags(): Flow<List<Tag>> =
        tagDao.observeAll().map { tags -> tags.map { it.toDomain() } }

    override suspend fun getAllTodos(): List<Todo> =
        todoDao.getAll().map { entity ->
            entity.toDomain(todoTagDao.getTagsForTodo(entity.id))
        }

    override suspend fun saveTodo(todo: Todo, tagNames: List<String>): Long = database.withTransaction {
        val normalizedNames = tagNames.map(String::trim).filter(String::isNotEmpty).distinct()
        val now = Instant.now().toEpochMilli()
        val todoId = todoDao.upsert(todo.toEntity(now)).let { insertedId ->
            if (todo.id == 0L) insertedId else todo.id
        }
        val existingTags = tagDao.findByNames(normalizedNames)
        val existingNames = existingTags.map { it.name }.toSet()
        val missingTags = normalizedNames.filterNot(existingNames::contains).map { name ->
            TagEntity(name = name, color = defaultColor(name), createdAt = now)
        }
        if (missingTags.isNotEmpty()) tagDao.insertAll(missingTags)
        val allTags = tagDao.findByNames(normalizedNames)
        todoTagDao.deleteForTodo(todoId)
        todoTagDao.insertAll(allTags.map { tag -> TodoTagCrossRef(todoId, tag.id) })
        todoId
    }

    override suspend fun deleteTodo(id: Long) {
        todoDao.deleteById(id)
    }

    override suspend fun setCompleted(id: Long, completed: Boolean) {
        todoDao.setCompleted(
            id = id,
            completed = completed,
            completedAt = if (completed) Instant.now().toEpochMilli() else null,
            updatedAt = Instant.now().toEpochMilli(),
        )
    }

    private fun Todo.matches(filter: TodoFilter): Boolean {
        val query = filter.query.trim()
        if (query.isNotEmpty() && !title.contains(query, true) && !description.orEmpty().contains(query, true)) {
            return false
        }
        if (filter.priority != null && priority != filter.priority) return false
        if (filter.tagId != null && filter.tagId !in tagIds) return false
        val dueDate = dueAt?.atZone(zone)?.toLocalDate()
        val today = LocalDate.now(zone)
        return when (filter.dateFilter) {
            TodoDateFilter.ALL -> true
            TodoDateFilter.TODAY -> dueDate == null || dueDate == today
            TodoDateFilter.TOMORROW -> dueDate == today.plusDays(1)
            TodoDateFilter.THIS_WEEK -> {
                val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
                dueDate != null && !dueDate.isBefore(start) && !dueDate.isAfter(start.plusDays(6))
            }
        }
    }

    private fun defaultColor(name: String): Int =
        0xFF000000.toInt() or (name.hashCode() and 0x00FFFFFF)
}

private fun TodoEntity.toDomain(tags: List<TagEntity>): Todo = Todo(
    id = id,
    title = title,
    description = description,
    priority = priority,
    dueAt = dueAt?.let(Instant::ofEpochMilli),
    isCompleted = isCompleted,
    completedAt = completedAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    parentId = parentId,
    sortOrder = sortOrder,
    tagIds = tags.map { it.id }.toSet(),
    tagNames = tags.map { it.name },
)

private fun Todo.toEntity(now: Long): TodoEntity = TodoEntity(
    id = id,
    title = title.trim(),
    description = description?.trim()?.ifEmpty { null },
    priority = priority,
    dueAt = dueAt?.toEpochMilli(),
    isCompleted = isCompleted,
    completedAt = completedAt?.toEpochMilli(),
    createdAt = if (id == 0L) now else createdAt.toEpochMilli(),
    updatedAt = now,
    parentId = parentId,
    sortOrder = sortOrder,
)

private fun TagEntity.toDomain(): Tag = Tag(
    id = id,
    name = name,
    color = color,
    createdAt = Instant.ofEpochMilli(createdAt),
)
