package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction

/**
 * Tier 1 of the three-tier parsing design (see spec §3): a built-in regex per known sender.
 *
 * The body regex here is a generic "Rs X debited/credited ... to/from Y" shape that covers
 * the common wording across most Indian bank and UPI alerts. It is a starting point, not a
 * verified-against-real-traffic parser — refine per bank once real message samples are in
 * hand. Anything it misses still degrades gracefully into the review queue (tier 2).
 */
interface BankTemplate {
    val name: String
    fun matchesSender(sender: String): Boolean
    fun extract(body: String): ParsedSms?
}

private val DEBIT_REGEX = Regex(
    """(?i)(?:rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)\s+(?:has\s+been\s+)?debited.*?(?:to|at|towards)\s+([A-Za-z0-9@._\-\s]{2,40}?)(?:\s+on\b|\s+ref\b|[.,]|$)""",
)

private val CREDIT_REGEX = Regex(
    """(?i)(?:rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)\s+(?:has\s+been\s+)?credited.*?(?:from|by)\s+([A-Za-z0-9@._\-\s]{2,40}?)(?:\s+on\b|\s+ref\b|[.,]|$)""",
)

/** Shared extraction logic every per-bank template delegates to until it needs to diverge. */
private fun extractGeneric(body: String): ParsedSms? {
    DEBIT_REGEX.find(body)?.let { match ->
        return ParsedSms(
            amountMinor = parseAmountToMinorUnits(match.groupValues[1]),
            direction = Direction.DEBIT,
            merchant = match.groupValues[2].trim(),
        )
    }
    CREDIT_REGEX.find(body)?.let { match ->
        return ParsedSms(
            amountMinor = parseAmountToMinorUnits(match.groupValues[1]),
            direction = Direction.CREDIT,
            merchant = match.groupValues[2].trim(),
        )
    }
    return null
}

private class GenericBankTemplate(
    override val name: String,
    private val senderHints: List<String>,
) : BankTemplate {
    override fun matchesSender(sender: String): Boolean =
        senderHints.any { sender.contains(it, ignoreCase = true) }

    override fun extract(body: String): ParsedSms? = extractGeneric(body)
}

object BankTemplates {
    val all: List<BankTemplate> = listOf(
        GenericBankTemplate("HDFC", listOf("HDFC")),
        GenericBankTemplate("SBI", listOf("SBI", "SBIINB", "SBIBNK")),
        GenericBankTemplate("ICICI", listOf("ICICI")),
        GenericBankTemplate("Axis", listOf("AXIS", "AXISBK")),
        GenericBankTemplate("Kotak", listOf("KOTAK")),
        // Generic UPI alerts don't share a consistent sender ID across PSPs, so this one
        // matches on any sender and relies entirely on body content.
        GenericBankTemplate("Generic UPI", listOf("")),
    )

    fun findMatch(sender: String, body: String): ParsedSms? =
        all.firstOrNull { it.matchesSender(sender) }?.extract(body)
}

private val AMOUNT_HINT = Regex("""(?i)(?:rs\.?|inr)\s*[\d,]+""")
private val TRANSACTIONAL_KEYWORD_HINT = Regex(
    """(?i)\b(debited|credited|debit|credit|spent|paid|received|withdrawn|purchase)\b""",
)

/** Heuristic for tier 2: looks financial enough to surface for manual review. */
fun looksFinancial(body: String): Boolean =
    AMOUNT_HINT.containsMatchIn(body) && TRANSACTIONAL_KEYWORD_HINT.containsMatchIn(body)