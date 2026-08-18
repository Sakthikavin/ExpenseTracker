package com.example.expensetracker.data.sms

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Reads the transaction date out of a bank SMS.
 *
 * Without this the pipeline stamps every transaction with the moment it was ingested, which is
 * wrong for anything delivered late or backfilled. When no date can be read we return null rather
 * than guessing, and the caller falls back to the message's receipt time.
 *
 * Indian bank alerts are not consistent, so all of these appear in the wild:
 * `01Aug26`, `01-Aug-26`, `02-08-26`, `02/08/2026`, `2026-08-01`, each optionally followed by a
 * `19:00` or `19:00:12` clock time.
 */
object SmsDateParser {

    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )

    /** ISO-ish, most specific first: `2026-08-01`. */
    private val ISO_DATE = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")

    /** `01Aug26`, `01Aug2026`, `01-Aug-26`, `01 Aug 2026`. */
    private val NAMED_MONTH_DATE = Regex("""(?i)\b(\d{1,2})[\s\-]?([A-Za-z]{3})[a-z]*[\s\-]?(\d{2,4})\b""")

    /** `02-08-26`, `02/08/2026` — day first, the Indian convention. */
    private val NUMERIC_DATE = Regex("""\b(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})\b""")

    private val TIME = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?\b""")

    /**
     * @return the instant named in [body], resolved in the device's timezone, or null when the
     * message carries no date we recognise.
     */
    fun parse(body: String, zone: TimeZone = TimeZone.currentSystemDefault()): Instant? {
        val date = findDate(body) ?: return null
        val time = findTimeAfter(body, date.second)
        return runCatching {
            LocalDateTime(
                year = date.first.year,
                monthNumber = date.first.monthNumber,
                dayOfMonth = date.first.dayOfMonth,
                hour = time?.first ?: 0,
                minute = time?.second ?: 0,
                second = time?.third ?: 0,
            ).toInstant(zone)
        }.getOrNull()
    }

    /**
     * @return the date and the index just past it, so a trailing clock time can be located.
     *
     * Considers a match from every pattern and keeps whichever starts earliest in [body], rather
     * than favouring one pattern type over another regardless of position. Card alerts routinely
     * trail the real transaction date with an unrelated ISO-shaped one — "Convert to EMI before
     * 2026-01-15" after "...on 15-Dec-25" — and trying ISO_DATE across the whole body first would
     * pick that later, unrelated date over the real one right after the amount.
     */
    private fun findDate(body: String): Pair<LocalDate, Int>? {
        val candidates = listOfNotNull(
            ISO_DATE.find(body)?.let { m ->
                val (y, mo, d) = m.destructured
                localDate(y.toInt(), mo.toInt(), d.toInt())?.let { m.range.first to (it to m.range.last + 1) }
            },
            NAMED_MONTH_DATE.find(body)?.let { m ->
                val (d, monthName, y) = m.destructured
                val month = MONTHS.indexOf(monthName.lowercase()) + 1
                if (month > 0) {
                    localDate(expandYear(y.toInt()), month, d.toInt())?.let { m.range.first to (it to m.range.last + 1) }
                } else {
                    null
                }
            },
            NUMERIC_DATE.find(body)?.let { m ->
                val (d, mo, y) = m.destructured
                localDate(expandYear(y.toInt()), mo.toInt(), d.toInt())?.let { m.range.first to (it to m.range.last + 1) }
            },
        )
        return candidates.minByOrNull { it.first }?.second
    }

    /**
     * Only a clock time sitting immediately after the date belongs to it — a `19:00` elsewhere in
     * the message is as likely to be a helpline's opening hours.
     */
    private fun findTimeAfter(body: String, from: Int): Triple<Int, Int, Int>? {
        if (from >= body.length) return null
        val match = TIME.find(body, from) ?: return null
        if (body.substring(from, match.range.first).isNotBlank()) return null
        val (h, min, sec) = match.destructured
        val hour = h.toInt()
        val minute = min.toInt()
        val second = sec.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return Triple(hour, minute, second)
    }

    /** Bank SMS write two-digit years; nothing here predates 2000. */
    private fun expandYear(year: Int): Int = if (year < 100) 2000 + year else year

    private fun localDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate(year, month, day) }.getOrNull()
}
