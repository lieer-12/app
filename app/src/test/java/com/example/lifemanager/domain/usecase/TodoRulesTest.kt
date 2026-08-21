package com.example.lifemanager.domain.usecase

import com.example.lifemanager.domain.model.Todo
import com.example.lifemanager.domain.model.TodoPriority
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TodoRulesTest {
    @Test
    fun `blank title is rejected after trimming`() {
        val message = TodoRules.validateTitle("   ")

        assertEquals("标题不能为空", message)
    }

    @Test
    fun `non blank title is accepted`() {
        assertNull(TodoRules.validateTitle("整理资料"))
    }

    @Test
    fun `stats count completed pending and completion rate`() {
        val todos = listOf(
            Todo(id = 1, title = "已完成", isCompleted = true, completedAt = Instant.parse("2026-08-22T08:00:00Z")),
            Todo(id = 2, title = "未完成", priority = TodoPriority.HIGH),
            Todo(id = 3, title = "另一个未完成", priority = TodoPriority.LOW),
        )

        val stats = TodoRules.calculateStats(todos, LocalDate.of(2026, 8, 22))

        assertEquals(1, stats.completedCount)
        assertEquals(2, stats.pendingCount)
        assertEquals(1.0 / 3.0, stats.completionRate)
    }

    @Test
    fun `empty todo list has zero completion rate`() {
        val stats = TodoRules.calculateStats(emptyList(), LocalDate.of(2026, 8, 22))

        assertEquals(0, stats.completedCount)
        assertEquals(0, stats.pendingCount)
        assertEquals(0.0, stats.completionRate)
    }

    @Test
    fun `stats exclude todos due outside today`() {
        val todos = listOf(
            Todo(
                id = 1,
                title = "昨天完成",
                isCompleted = true,
                dueAt = Instant.parse("2026-08-21T08:00:00Z"),
            ),
            Todo(id = 2, title = "今天未完成"),
        )

        val stats = TodoRules.calculateStats(todos, LocalDate.of(2026, 8, 22))

        assertEquals(0, stats.completedCount)
        assertEquals(1, stats.pendingCount)
        assertEquals(0.0, stats.completionRate)
    }
}
