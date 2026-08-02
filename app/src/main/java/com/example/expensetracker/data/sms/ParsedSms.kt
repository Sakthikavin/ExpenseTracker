package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction
import java.math.BigDecimal
import kotlinx.datetime.Instant

data class ParsedSms(
    val amountMinor: Long,
    val direction: Direction,
    val merchant: String,
    val accountLabel: String = "",
    /** The date named in the message, when one could be read; null means "fall back to receipt time". */
    val occurredAt: Instant? = null,
    /**
     * The bank's own transaction reference, when the message carries one. Two messages describing
     * the same movement of money share it, which is how a duplicate is recognised.
     */
    val referenceId: String? = null,
    /**
     * The account on the *other* side of the movement — the destination of a transfer. When this is
     * one of the user's own accounts the transaction is a self-transfer, not spending.
     */
    val counterpartyAccount: String? = null,
)

sealed interface ParseOutcome {
    data class Parsed(val parsed: ParsedSms) : ParseOutcome
    data object NeedsReview : ParseOutcome
    data object Ignored : ParseOutcome
}

/**
 * Money is stored as integer paise, never a float. [BigDecimal] rather than [Double] so the
 * conversion is exact at any magnitude instead of relying on a rounding step.
 *
 * Returns null on anything unparseable — this runs inside a [android.content.BroadcastReceiver],
 * where a thrown exception takes the app down, and a message we can't read the amount of should
 * degrade into the review queue instead.
 */
fun parseAmountToMinorUnits(raw: String): Long? {
    val cleaned = raw.replace(",", "").trim()
    if (cleaned.isEmpty()) return null
    val amount = cleaned.toBigDecimalOrNull() ?: return null
    return runCatching { amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }
        .getOrNull()
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
