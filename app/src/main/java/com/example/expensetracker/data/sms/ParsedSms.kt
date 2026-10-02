package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction
import java.math.BigDecimal
import kotlinx.datetime.Instant

/**
 * Everything a matched rule yields (`PARSING_ARCHITECTURE.md` §2) — four fields read from the
 * message plus the date, and nothing computed afterwards.
 */
data class ParsedSms(
    val amountMinor: Long,
    val direction: Direction,
    val merchant: String,
    /**
     * The rule's `account` group, as captured — blank when the rule doesn't map one, which is the
     * case for all the generic any-sender rules.
     */
    val accountLabel: String = "",
    /** The date named in the message, when one could be read; null means "fall back to receipt time". */
    val occurredAt: Instant? = null,
)

sealed interface ParseOutcome {
    data class Parsed(val parsed: ParsedSms) : ParseOutcome
    data object NeedsReview : ParseOutcome

    /**
     * Filtered out by a known-noise rule (sender, pre-notice wording, or too small to matter) after
     * the message otherwise looked financial enough for review. Persisted with
     * [com.example.expensetracker.data.local.entity.ParseStatus.IGNORED] — the same status a manual
     * dismissal from the review queue produces — so it stays auditable, just hidden from the queue.
     */
    data object IgnoredAsNoise : ParseOutcome

    /**
     * Mentions money, but carries none of the structure [looksFinancial] recognises as a bank
     * alert. Persisted with [com.example.expensetracker.data.local.entity.ParseStatus.DISCARDED]
     * and pruned to the newest few hundred: this is the bucket that makes the heuristic's mistakes
     * findable, since a message it turns away used to leave no trace of any kind.
     */
    data object Discarded : ParseOutcome

    /** Doesn't look financial at all, and doesn't even mention money; never touches raw_sms. */
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
