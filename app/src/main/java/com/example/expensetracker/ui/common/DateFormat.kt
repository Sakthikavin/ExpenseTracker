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
