package com.example.lifemanager.ui.settings

import com.example.lifemanager.domain.model.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalDateFormat = compositionLocalOf { DateFormat.YMD }

private fun DateFormat.pattern(): String = when (this) {
    DateFormat.YMD -> "uuuu-MM-dd"
    DateFormat.MDY -> "MM-dd-uuuu"
    DateFormat.DMY -> "dd-MM-uuuu"
}

fun formatDisplayDate(date: LocalDate, format: DateFormat): String =
    date.format(DateTimeFormatter.ofPattern(format.pattern(), Locale.ROOT))

fun formatDisplayDateTime(instant: Instant, format: DateFormat, zone: ZoneId): String =
    instant.atZone(zone).format(DateTimeFormatter.ofPattern("${format.pattern()} HH:mm", Locale.ROOT))

@Composable fun displayDate(date: LocalDate): String = formatDisplayDate(date, LocalDateFormat.current)
@Composable fun displayDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    displayDate(instant.atZone(zone).toLocalDate())
@Composable fun displayDateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    formatDisplayDateTime(instant, LocalDateFormat.current, zone)
