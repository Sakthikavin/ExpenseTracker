package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.LearnedPatternEntity

/**
 * Tier 3 of the parsing design: turns one manually confirmed "needs review" message into a
 * reusable regex for that sender, so the next message parses on its own (spec §3).
 *
 * Best-effort by nature — it works by locating the confirmed amount and merchant text back
 * inside the original message body and replacing exactly those spans with capture groups,
 * escaping everything else literally. If either value can't be located verbatim in the body
 * (e.g. the user retyped the merchant name), no pattern is derived; the transaction is still
 * saved, so this only affects whether *future* messages from that sender auto-parse.
 */
object PatternLearner {
    fun derive(sender: String, body: String, amountMinor: Long, merchant: String): LearnedPatternEntity? {
        val amountSpan = findAmountSpan(body, amountMinor) ?: return null
        val merchantSpan = merchant.takeIf { it.isNotBlank() }?.let { findMerchantSpan(body, it) }

        val spans = listOfNotNull(
            amountSpan to "amount",
            merchantSpan?.let { it to "merchant" },
        ).sortedBy { it.first.first }

        val regexBuilder = StringBuilder()
        val fieldMap = mutableMapOf<String, Int>()
        var cursor = 0
        var groupIndex = 0
        for ((span, field) in spans) {
            if (span.first < cursor) continue // overlapping spans; skip the second one
            regexBuilder.append(Regex.escape(body.substring(cursor, span.first)))
            groupIndex += 1
            val groupPattern = if (field == "amount") """([\d,]+(?:\.\d{1,2})?)""" else """(.+?)"""
            regexBuilder.append(groupPattern)
            fieldMap[field] = groupIndex
            cursor = span.last + 1
        }
        regexBuilder.append(Regex.escape(body.substring(cursor)))

        return LearnedPatternEntity(
            senderPattern = sender,
            regex = regexBuilder.toString(),
            fieldMap = fieldMap,
            confirmedCount = 1,
        )
    }

    fun applyToBody(pattern: LearnedPatternEntity, body: String): ParsedSms? {
        val regex = runCatching { Regex(pattern.regex) }.getOrNull() ?: return null
        val match = regex.find(body) ?: return null
        val amountGroup = pattern.fieldMap["amount"] ?: return null
        val amountText = match.groupValues.getOrNull(amountGroup)?.takeIf { it.isNotEmpty() } ?: return null
        val merchantGroup = pattern.fieldMap["merchant"]
        val merchant = merchantGroup?.let { match.groupValues.getOrNull(it) }?.trim().orEmpty()
        return ParsedSms(
            amountMinor = parseAmountToMinorUnits(amountText),
            // Learned patterns don't currently distinguish direction; confirmed reviews
            // default to debit, the far more common case for unrecognised senders.
            direction = com.example.expensetracker.data.local.entity.Direction.DEBIT,
            merchant = merchant,
        )
    }

    private fun findAmountSpan(body: String, amountMinor: Long): IntRange? {
        val rupees = amountMinor / 100
        val paise = amountMinor % 100
        val candidates = listOf(
            "%d.%02d".format(rupees, paise),
            "%,d.%02d".format(rupees, paise),
            rupees.toString(),
        )
        for (candidate in candidates) {
            val idx = body.indexOf(candidate)
            if (idx >= 0) return idx until (idx + candidate.length)
        }
        return null
    }

    private fun findMerchantSpan(body: String, merchant: String): IntRange? {
        val idx = body.indexOf(merchant, ignoreCase = true)
        return if (idx >= 0) idx until (idx + merchant.length) else null
    }
}