package com.example.lifemanager.data.plan

import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef
import com.example.lifemanager.domain.model.TodoPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyPlanReferencesTest {
    @Test fun `same old id in two sources resolves to different new plans`() {
        val lookup = LegacyPlanReferences(listOf(ref(LegacyPlanSource.TODO, Long.MAX_VALUE, 1), ref(LegacyPlanSource.SCHEDULE, Long.MAX_VALUE, 2)))
        assertEquals(1L, lookup.find(LegacyPlanKey(LegacyPlanSource.TODO, Long.MAX_VALUE)))
        assertEquals(2L, lookup.find(LegacyPlanKey(LegacyPlanSource.SCHEDULE, Long.MAX_VALUE)))
        assertNull(lookup.find(LegacyPlanKey(LegacyPlanSource.TODO, 99)))
    }

    @Test fun `parent can have larger legacy id and maps through TODO source only`() {
        val lookup = LegacyPlanReferences(listOf(ref(LegacyPlanSource.TODO, 1, 1), ref(LegacyPlanSource.TODO, Long.MAX_VALUE, 2), ref(LegacyPlanSource.SCHEDULE, Long.MAX_VALUE, 3)))
        assertEquals(2L, LegacyPlanRelations.parent(todo().copy(parentId = Long.MAX_VALUE), lookup))
    }

    @Test fun `no parent remains null even with empty lookup`() {
        assertNull(LegacyPlanRelations.parent(todo(), LegacyPlanReferences(emptyList())))
    }

    @Test fun `missing TODO parent cannot silently attach to same id schedule`() {
        val lookup = LegacyPlanReferences(listOf(ref(LegacyPlanSource.SCHEDULE, 7, 1)))
        assertFailsWith<IllegalArgumentException> { LegacyPlanRelations.parent(todo().copy(parentId = 7), lookup) }
    }

    @Test fun `self parent is rejected before persistence`() {
        val lookup = LegacyPlanReferences(listOf(ref(LegacyPlanSource.TODO, 1, 1)))
        assertFailsWith<IllegalArgumentException> { LegacyPlanRelations.parent(todo().copy(parentId = 1), lookup) }
    }

    @Test fun `tag relationship preserves tag id while remapping todo id`() {
        val lookup = LegacyPlanReferences(listOf(ref(LegacyPlanSource.TODO, Long.MIN_VALUE, 1)))
        val result = runCatching { LegacyPlanRelations.tag(TodoTagCrossRef(Long.MIN_VALUE, Long.MAX_VALUE), lookup) }
        assertTrue(result.isSuccess)
        assertEquals(PlanTagLink(1, Long.MAX_VALUE), result.getOrThrow())
    }

    @Test fun `cancelled and retained non cancelled exceptions preserve date and flag`() {
        val lookup = LegacyPlanReferences(listOf(ref(LegacyPlanSource.SCHEDULE, 0, 2)))
        for (cancelled in listOf(false, true)) {
            val result = runCatching { LegacyPlanRelations.exception(ScheduleExceptionEntity(0, -1, cancelled), lookup) }
            assertTrue(result.isSuccess)
            assertEquals(PlanExceptionRow(2, -1, cancelled), result.getOrThrow())
        }
    }

    @Test fun `missing relation source rejects instead of dropping tag or exception`() {
        val empty = LegacyPlanReferences(emptyList())
        assertFailsWith<IllegalArgumentException> { LegacyPlanRelations.tag(TodoTagCrossRef(1, 1), empty) }
        assertFailsWith<IllegalArgumentException> { LegacyPlanRelations.exception(ScheduleExceptionEntity(1, 1, true), empty) }
    }

    @Test fun `duplicate source identity is rejected even if target id differs`() {
        assertFailsWith<IllegalArgumentException> { LegacyPlanReferences(listOf(ref(LegacyPlanSource.TODO, 1, 1), ref(LegacyPlanSource.TODO, 1, 2))) }
    }

    @Test fun `duplicate target id is rejected across different sources`() {
        assertFailsWith<IllegalArgumentException> { LegacyPlanReferences(listOf(ref(LegacyPlanSource.TODO, 1, 1), ref(LegacyPlanSource.SCHEDULE, 1, 1))) }
    }

    @Test fun `invalid target id from registry or SQL lookup is rejected`() {
        assertFailsWith<IllegalArgumentException> { LegacyPlanReferences(listOf(ref(LegacyPlanSource.TODO, 1, 0))) }
        assertFailsWith<IllegalArgumentException> { LegacyPlanRelations.tag(TodoTagCrossRef(1, 1), LegacyPlanLookup { -1 }) }
    }

    private fun ref(source: LegacyPlanSource, legacyId: Long, planId: Long) = LegacyPlanReference(source, legacyId, planId)
    private fun todo() = TodoEntity(1, "子项", null, TodoPriority.NONE, null, false, null, 0, 0, null, 0)
}
