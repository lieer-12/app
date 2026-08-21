package com.example.lifemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.domain.model.TodoPriority
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class TodoDaoTest {
    private lateinit var database: LifeManagerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LifeManagerDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndCompleteTodoPersists() = runBlocking {
        val now = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli()
        val id = database.todoDao().upsert(
            TodoEntity(
                title = "测试待办",
                description = null,
                priority = TodoPriority.MEDIUM,
                dueAt = now,
                isCompleted = false,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
                parentId = null,
                sortOrder = 0,
            ),
        )

        database.todoDao().setCompleted(id, completed = true, completedAt = now, updatedAt = now)

        assertEquals(true, database.todoDao().getAll().single().isCompleted)
    }

    @Test
    fun tagCrossReferenceIsUnique() = runBlocking {
        val now = Instant.now().toEpochMilli()
        val todoId = database.todoDao().upsert(
            TodoEntity(
                title = "标签任务",
                description = null,
                priority = TodoPriority.NONE,
                dueAt = null,
                isCompleted = false,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
                parentId = null,
                sortOrder = 0,
            ),
        )
        val tagId = database.tagDao().insertAll(listOf(TagEntity(name = "工作", color = 0xFF00695C.toInt(), createdAt = now))).single()
        val ref = TodoTagCrossRef(todoId, tagId)
        database.todoTagDao().insertAll(listOf(ref, ref))

        assertEquals(1, database.todoTagDao().getTagsForTodo(todoId).size)
    }
}
