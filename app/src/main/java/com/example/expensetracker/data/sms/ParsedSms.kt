package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction

data class ParsedSms(
    val amountMinor: Long,
    val direction: Direction,
    val merchant: String,
    val accountLabel: String = "",
)

sealed interface ParseOutcome {
    data class Parsed(val parsed: ParsedSms) : ParseOutcome
    data object NeedsReview : ParseOutcome
    data object Ignored : ParseOutcome
}

fun parseAmountToMinorUnits(raw: String): Long {
    val cleaned = raw.replace(",", "").trim()
    val amount = cleaned.toDouble()
    return Math.round(amount * 100)
}