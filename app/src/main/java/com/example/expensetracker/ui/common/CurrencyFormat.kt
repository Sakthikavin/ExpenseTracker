package com.example.expensetracker.ui.common

/**
 * Minor units (paise) to a display string. Uses plain Western thousands grouping rather than
 * Indian lakh/crore grouping — good enough for v1, worth revisiting with a locale-aware
 * NumberFormat if that grouping matters later.
 */
fun formatMinorUnitsAsInr(amountMinor: Long): String {
    val negative = amountMinor < 0
    val absMinor = kotlin.math.abs(amountMinor)
    val rupees = absMinor / 100
    val paise = absMinor % 100
    val sign = if (negative) "-" else ""
    return "$sign₹%,d.%02d".format(rupees, paise)
}

fun parseInrInputToMinorUnits(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val amount = trimmed.toDoubleOrNull() ?: return null
    return Math.round(amount * 100)
}