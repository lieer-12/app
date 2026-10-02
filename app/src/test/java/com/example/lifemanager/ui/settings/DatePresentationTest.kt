package com.example.lifemanager.ui.settings

import com.example.lifemanager.domain.model.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class DatePresentationTest {
    @Test fun dateFormatsUseLiteralUnambiguousDayAndMonthPositions() {
        val day = LocalDate.of(2026, 10, 2)
        assertEquals("2026-10-02", formatDisplayDate(day, DateFormat.YMD))
        assertEquals("10-02-2026", formatDisplayDate(day, DateFormat.MDY))
        assertEquals("02-10-2026", formatDisplayDate(day, DateFormat.DMY))
    }

    @Test fun instantDisplayUsesSelectedZoneWithoutChangingTheStoredInstant() {
        val instant = Instant.parse("2026-10-01T23:30:00Z")
        assertEquals("02-10-2026 07:30", formatDisplayDateTime(instant, DateFormat.DMY, ZoneId.of("Asia/Shanghai")))
        assertEquals("10-01-2026 23:30", formatDisplayDateTime(instant, DateFormat.MDY, ZoneId.of("UTC")))
        assertEquals(Instant.parse("2026-10-01T23:30:00Z"), instant)
    }
}
