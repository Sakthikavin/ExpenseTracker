package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.LearnedPatternEntity

/**
 * Tier 3 of the parsing design: turns one manually confirmed "needs review" message into a
 * reusable regex for that sender, so the next message parses on its own (spec §3).
 *
 * It works by locating the confirmed amount and merchant back inside the original message body and
 * replacing exactly those spans with capture groups. Everything between them is kept as literal
 * text **except** the parts that change from message to message — dates, clock times, reference
 * numbers and running balances — which are generalised into wildcards by [generaliseLiteral].
 *
 * That generalisation is the difference between a pattern that fires again and one that never
 * does: freezing "on 02-08-26 ... Avl Bal 5000" into the pattern guarantees no future message
 * matches it, because both of those values change every time.
 *
 * If the amount can't be located verbatim in the body (e.g. the user retyped it), no pattern is
 * derived; the transaction is still saved, so this only affects whether *future* messages
 * auto-parse.
 */
object PatternLearner {

    /**
     * Indian sender IDs carry a rotating operator/circle prefix and sometimes a trailing segment —
     * `AD-FEDBNK`, `VM-FEDBNK`, `JD-FEDBNK-S` are all the same bank. Keying patterns on the longest
     * alphabetic segment keeps a learned pattern working when the prefix changes.
     */
    fun normaliseSender(sender: String): String {
        val segments = sender.split('-', '.', ' ').filter { it.isNotBlank() }
        val candidate = segments
            .filter { it.any(Char::isLetter) }
            .maxByOrNull { it.length }
            ?: sender
        return candidate.uppercase()
    }

    fun derive(
        sender: String,
        body: String,
        amountMinor: Long,
        merchant: String,
        direction: Direction,
    ): LearnedPatternEntity? {
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
            regexBuilder.append(generaliseLiteral(body.substring(cursor, span.first)))
            groupIndex += 1
            val groupPattern = if (field == "amount") """([\d,]+(?:\.\d{1,2})?)""" else """(.+?)"""
            regexBuilder.append(groupPattern)
            fieldMap[field] = groupIndex
            cursor = span.last + 1
        }
        regexBuilder.append(generaliseLiteral(body.substring(cursor)))

        return LearnedPatternEntity(
            senderPattern = normaliseSender(sender),
            regex = regexBuilder.toString(),
            fieldMap = fieldMap,
            confirmedCount = 1,
            direction = direction,
        )
    }

    fun applyToBody(pattern: LearnedPatternEntity, body: String): ParsedSms? {
        val regex = runCatching { Regex(pattern.regex) }.getOrNull() ?: return null
        val match = regex.find(body) ?: return null
        val amountGroup = pattern.fieldMap["amount"] ?: return null
        val amountText = match.groupValues.getOrNull(amountGroup)?.takeIf { it.isNotEmpty() } ?: return null
        val amountMinor = parseAmountToMinorUnits(amountText) ?: return null
        val merchantGroup = pattern.fieldMap["merchant"]
        val merchant = merchantGroup?.let { match.groupValues.getOrNull(it) }?.trim().orEmpty()
        return ParsedSms(
            amountMinor = amountMinor,
            direction = pattern.direction,
            merchant = merchant,
            occurredAt = SmsDateParser.parse(body),
        )
    }

    /**
     * The values that differ between two otherwise identical alerts from the same sender. Ordered
     * most specific first — a bare number run would otherwise swallow half of a date.
     */
    private val VOLATILE = Regex(
        listOf(
            """\d{1,2}[-/]\d{1,2}[-/]\d{2,4}""", // 02-08-26, 02/08/2026
            """\d{4}-\d{1,2}-\d{1,2}""", // 2026-08-01
            """\d{1,2}[\s\-]?[A-Za-z]{3}[a-z]*[\s\-]?\d{2,4}""", // 01Aug26, 01-Aug-2026
            """\d{1,2}:\d{2}(?::\d{2})?""", // 19:00, 19:00:12
            """(?:rs\.?|inr)\s*\d[\d,]*(?:\.\d{1,2})?""", // Rs 42727.9
            """\d[\d,]*(?:\.\d{1,2})?""", // any remaining number: refs, balances, counters
        ).joinToString("|") { "(?:$it)" },
        RegexOption.IGNORE_CASE,
    )

    /**
     * Escapes [text] as a literal, except for volatile runs which become equivalent wildcards so
     * the pattern still matches next month's message.
     */
    internal fun generaliseLiteral(text: String): String {
        if (text.isEmpty()) return ""
        val builder = StringBuilder()
        var cursor = 0
        for (match in VOLATILE.findAll(text)) {
            if (match.range.first > cursor) {
                builder.append(Regex.escape(text.substring(cursor, match.range.first)))
            }
            builder.append(wildcardFor(match.value))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) builder.append(Regex.escape(text.substring(cursor)))
        return builder.toString()
    }

    /** Keeps the currency word literal so "Rs 5000" can't match a bare "5000" elsewhere. */
    private fun wildcardFor(volatile: String): String {
        val currency = Regex("""(?i)^(rs\.?|inr)\s*""").find(volatile)
        return if (currency != null) {
            Regex.escape(currency.value) + """[\d,.]+"""
        } else {
            """[\d,.:/\-]+"""
        }
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
