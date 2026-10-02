package com.example.lifemanager.notification

import com.example.lifemanager.domain.model.Subscription
import com.example.lifemanager.domain.usecase.SubscriptionRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class SubscriptionReminderOccurrence(val dueDate: LocalDate, val triggerAt: Instant)

object SubscriptionReminderRules {
    val supportedDays: Set<Int> = setOf(1, 3, 7)
    private val reminderTime = LocalTime.of(9, 0)

    fun nextReminder(
        subscription: Subscription,
        daysBefore: Int,
        now: Instant,
        zone: ZoneId,
    ): SubscriptionReminderOccurrence? {
        if (daysBefore !in supportedDays || !subscription.isActive) return null
        val localNow = now.atZone(zone)
        // Seek by reminder date, not billing date: a near bill can already have passed offsets.
        val firstReminderDate = if (localNow.toLocalTime().isBefore(reminderTime)) {
            localNow.toLocalDate()
        } else {
            localNow.toLocalDate().plusDays(1)
        }
        val dueDate = SubscriptionRules.effectiveNextBillingDate(
            subscription, firstReminderDate.plusDays(daysBefore.toLong()),
        ) ?: return null
        val triggerAt = dueDate.minusDays(daysBefore.toLong()).atTime(reminderTime).atZone(zone).toInstant()
        return SubscriptionReminderOccurrence(dueDate, triggerAt).takeIf { it.triggerAt > now }
    }

    fun matchesCurrentReminder(
        subscription: Subscription?,
        selectedDays: Set<Int>,
        daysBefore: Int,
        dueDate: LocalDate,
        now: Instant,
        zone: ZoneId,
    ): Boolean {
        if (subscription == null || daysBefore !in selectedDays) return false
        // Permit inexact delivery later on the same local day; reject historical/early payloads.
        val todayStart = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val occurrence = nextReminder(subscription, daysBefore, todayStart.minusNanos(1), zone) ?: return false
        return occurrence.dueDate == dueDate && occurrence.triggerAt <= now &&
            occurrence.triggerAt.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    }

    fun shouldPreservePending(
        subscription: Subscription,
        selectedDays: Set<Int>,
        daysBefore: Int,
        pending: SubscriptionReminderOccurrence?,
        now: Instant,
        zone: ZoneId,
    ): Boolean {
        if (pending == null || !matchesCurrentReminder(
                subscription, selectedDays, daysBefore, pending.dueDate, now, zone,
            )
        ) return false
        // Preserve only an existing, undelivered alarm at today's current local 09:00.
        // A timezone change can leave the same due-date identity pointing at a different instant.
        return pending.triggerAt == pending.dueDate.minusDays(daysBefore.toLong())
            .atTime(reminderTime).atZone(zone).toInstant()
    }
}
