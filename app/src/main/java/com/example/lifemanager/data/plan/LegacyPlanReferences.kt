package com.example.lifemanager.data.plan

import com.example.lifemanager.data.local.entity.ScheduleExceptionEntity
import com.example.lifemanager.data.local.entity.TodoEntity
import com.example.lifemanager.data.local.entity.TodoTagCrossRef

fun interface LegacyPlanLookup {
    fun find(key: LegacyPlanKey): Long?
}

/** In-memory lookup for bounded backup payloads; database migration supplies a SQL lookup. */
class LegacyPlanReferences(references: List<LegacyPlanReference>) : LegacyPlanLookup {
    private val bySource: Map<LegacyPlanKey, Long>

    init {
        val mapped = HashMap<LegacyPlanKey, Long>()
        val targets = HashSet<Long>()
        for (reference in references) {
            require(reference.planId > 0) { "New plan id must be positive" }
            require(reference.key !in mapped) { "Duplicate legacy source identity: ${reference.key}" }
            require(targets.add(reference.planId)) { "Reused plan id: ${reference.planId}" }
            mapped[reference.key] = reference.planId
        }
        bySource = mapped
    }

    override fun find(key: LegacyPlanKey): Long? = bySource[key]
}

object LegacyPlanRelations {
    fun parent(todo: TodoEntity, lookup: LegacyPlanLookup): Long? {
        val parentId = todo.parentId ?: return null
        require(parentId != todo.id) { "A plan cannot be its own parent" }
        return requirePlan(LegacyPlanKey(LegacyPlanSource.TODO, parentId), lookup)
    }

    fun tag(link: TodoTagCrossRef, lookup: LegacyPlanLookup): PlanTagLink =
        PlanTagLink(requirePlan(LegacyPlanKey(LegacyPlanSource.TODO, link.todoId), lookup), link.tagId)

    fun exception(value: ScheduleExceptionEntity, lookup: LegacyPlanLookup): PlanExceptionRow =
        PlanExceptionRow(requirePlan(LegacyPlanKey(LegacyPlanSource.SCHEDULE, value.scheduleId), lookup), value.occurrenceDate, value.isCancelled)

    private fun requirePlan(key: LegacyPlanKey, lookup: LegacyPlanLookup): Long {
        val planId = requireNotNull(lookup.find(key)) { "Missing legacy plan: $key" }
        require(planId > 0) { "Invalid mapped plan id: $planId" }
        return planId
    }
}
