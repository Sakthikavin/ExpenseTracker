package com.example.expensetracker.ui.common

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun monthAbbrev(monthNumber: Int): String = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)[monthNumber - 1]

fun formatDate(date: LocalDate): String = "${date.dayOfMonth} ${monthAbbrev(date.monthNumber)} ${date.year}"

fun formatDate(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    formatDate(instant.toLocalDateTime(zone).date)

fun formatDateRange(start: LocalDate, end: LocalDate): String =
    if (start == end) formatDate(start) else "${formatDate(start)} – ${formatDate(end)}"

fun formatDateTime(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val dateTime = instant.toLocalDateTime(zone)
    val hour24 = dateTime.hour
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val minute = dateTime.minute.toString().padStart(2, '0')
    val amPm = if (hour24 < 12) "AM" else "PM"
    return "${formatDate(dateTime.date)}, $hour12:$minute $amPm"
}
