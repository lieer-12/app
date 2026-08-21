package com.example.lifemanager.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.lifemanager.data.local.entity.TagEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef

@Dao
interface TodoTagDao {
    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN todo_tag_cross_ref ON tags.id = todo_tag_cross_ref.tagId
        WHERE todo_tag_cross_ref.todoId = :todoId
        ORDER BY tags.name ASC
        """,
    )
    suspend fun getTagsForTodo(todoId: Long): List<TagEntity>

    @Query("DELETE FROM todo_tag_cross_ref WHERE todoId = :todoId")
    suspend fun deleteForTodo(todoId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(refs: List<TodoTagCrossRef>)
}
