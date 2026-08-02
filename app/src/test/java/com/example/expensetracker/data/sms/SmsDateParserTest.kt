package com.example.expensetracker.data.sms

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsDateParserTest {

    private val zone = TimeZone.of("Asia/Kolkata")

    private fun assertParsed(body: String, y: Int, mo: Int, d: Int, h: Int = 0, min: Int = 0, s: Int = 0) {
        val expected = LocalDateTime(y, mo, d, h, min, s).toInstant(zone)
        assertEquals(body, expected, SmsDateParser.parse(body, zone))
    }

    @Test
    fun `reads a compact named-month date`() =
        assertParsed("Debited Rs 1.00 on 01Aug26 to X", 2026, 8, 1)

    @Test
    fun `reads a hyphenated named-month date`() =
        assertParsed("Debited Rs 1.00 on 01-Aug-2026 to X", 2026, 8, 1)

    @Test
    fun `reads a day-first numeric date`() =
        assertParsed("Rs 450 debited to X on 02-08-26.", 2026, 8, 2)

    @Test
    fun `reads a slash-separated numeric date with a four digit year`() =
        assertParsed("Rs 450 debited to X on 02/08/2026.", 2026, 8, 2)

    @Test
    fun `reads an ISO date`() =
        assertParsed("Txn on 2026-08-01 for Rs 100", 2026, 8, 1)

    @Test
    fun `reads a trailing clock time`() =
        assertParsed("Debited Rs 1.00 from a/c X6686 on 01Aug26 19:00 via UPI", 2026, 8, 1, 19, 0)

    @Test
    fun `reads seconds when present`() =
        assertParsed("Txn on 02-08-2026 19:00:12 at SHOP", 2026, 8, 2, 19, 0, 12)

    @Test
    fun `ignores a clock time that does not follow the date`() {
        // The helpline hours here belong to no date and must not become the transaction time.
        assertParsed("Debited Rs 1.00 on 01Aug26 to X. Helpline open 09:00", 2026, 8, 1)
    }

    @Test
    fun `pivots two digit years into the 2000s`() =
        assertParsed("Txn on 05-01-05 for Rs 10", 2005, 1, 5)

    @Test
    fun `returns null when there is no date`() {
        assertNull(SmsDateParser.parse("Rs 250 debited towards ELECTRICITY BILL.", zone))
        assertNull(SmsDateParser.parse("Your OTP is 445566.", zone))
    }

    @Test
    fun `returns null for an impossible date rather than guessing`() {
        assertNull(SmsDateParser.parse("Txn on 32-13-26 for Rs 10", zone))
    }

    @Test
    fun `is not fooled by a long reference number`() {
        assertNull(SmsDateParser.parse("Ref 621312687340 for Rs 10", zone))
    }
}
