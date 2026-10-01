package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corpus-driven tests for tier 1 and tier 3. Every message here is a real-world wording shape;
 * add to these lists rather than tweaking a regex blind when a new bank format shows up.
 */
class BankTemplatesTest {

    private fun parse(body: String) = BankTemplates.findMatch(sender = "AD-ANYBNK", body = body)

    private fun assertParsed(body: String, direction: Direction, amountMinor: Long, merchant: String) {
        val parsed = requireNotNull(parse(body)) { "expected a match for: $body" }
        assertEquals(body, direction, parsed.direction)
        assertEquals(body, amountMinor, parsed.amountMinor)
        assertEquals(body, merchant, parsed.merchant)
    }

    // --- amount before the verb (HDFC/ICICI style) ---

    @Test
    fun `parses amount-first debit with to`() = assertParsed(
        "Rs 450.00 debited to swiggy@icici on 02-08-26. Ref 4432112. -HDFC Bank",
        Direction.DEBIT, 45000, "swiggy@icici",
    )

    @Test
    fun `parses passive has been debited`() = assertParsed(
        "INR 1,250.50 has been debited to AMAZON PAY on 01-08-26.",
        Direction.DEBIT, 125050, "AMAZON PAY",
    )

    @Test
    fun `parses debit with at`() = assertParsed(
        "Rs.99 debited at STARBUCKS INDIA on 02-08-26",
        Direction.DEBIT, 9900, "STARBUCKS INDIA",
    )

    @Test
    fun `parses debit with towards`() = assertParsed(
        "Rs 250 debited towards ELECTRICITY BILL.",
        Direction.DEBIT, 25000, "ELECTRICITY BILL",
    )

    @Test
    fun `parses credit`() = assertParsed(
        "Rs 5000 credited from RAHUL SHARMA on 01-08-26. Avl bal Rs 21000",
        Direction.CREDIT, 500000, "RAHUL SHARMA",
    )

    // --- verb before the amount (Federal/SBI style) ---

    @Test
    fun `parses verb-first debit and skips the source account`() = assertParsed(
        "Debited Rs 1.00 from a/c X6686 on 01Aug26 19:00 via UPI to KEERTHANA KU. " +
            "Ref 621312687340.Bal Rs 42727.9. Not you?Call 18004251199 -Federal Bank",
        Direction.DEBIT, 100, "KEERTHANA KU",
    )

    @Test
    fun `parses debited by with the amount after the connector`() = assertParsed(
        "Your a/c XX1234 is debited by Rs.320.00 on 02-08-26 at ZOMATO. Avl Bal 5000",
        Direction.DEBIT, 32000, "ZOMATO",
    )

    // --- verbs beyond debited/credited ---

    @Test
    fun `parses spent`() = assertParsed(
        "You have spent Rs 780 on your HDFC Credit Card at BIGBASKET",
        Direction.DEBIT, 78000, "BIGBASKET",
    )

    @Test
    fun `parses paid and stops the merchant at via`() = assertParsed(
        "Rs 1200 paid to Uber India via UPI",
        Direction.DEBIT, 120000, "Uber India",
    )

    @Test
    fun `parses withdrawn with from`() = assertParsed(
        "Rs.2000 withdrawn from ATM SBI Anna Nagar on 02-08-26",
        Direction.DEBIT, 200000, "ATM SBI Anna Nagar",
    )

    // --- the source account must never be mistaken for the merchant ---

    @Test
    fun `skips a slash c and takes the real payee`() = assertParsed(
        "INR 350 debited from a/c XX99 to VPA merchant@ybl on 02Aug26",
        Direction.DEBIT, 35000, "VPA merchant@ybl",
    )

    // --- messages that must NOT auto-parse ---

    @Test
    fun `does not parse a future-tense EMI notice`() {
        val body = "EMI of Rs 4,500 will be debited to your loan account on 05-08-26"
        assertNull(parse(body))
        assertTrue("should still reach the review queue", looksFinancial(body))
    }

    @Test
    fun `ignores promotional messages`() {
        val body = "Get 50% off! Spend Rs 999 and get Rs 200 cashback. Shop now."
        assertNull(parse(body))
        assertFalse(looksFinancial(body))
    }

    @Test
    fun `ignores an OTP`() {
        val body = "Your OTP is 445566. Do not share."
        assertNull(parse(body))
        assertFalse(looksFinancial(body))
    }

    // --- the financial heuristic reads structure, not a list of verbs ---

    /**
     * Canara writes the direction as `Dr.`, so no keyword in the old closed list matched and the
     * message was discarded without a row — the review queue never saw it. Its structure says bank
     * alert three times over: a masked account, a reference, and a balance.
     */
    @Test
    fun `an alert that abbreviates the direction still reaches the review queue`() {
        assertNull(parse(RealMessages.canaraDebit))
        assertTrue(looksFinancial(RealMessages.canaraDebit))
    }

    /**
     * The hint list exists to catch what the parse verbs miss, so a verb it doesn't cover is a
     * message discarded with no trace. This is the invariant the old KDoc asked a human to hold.
     */
    @Test
    fun `every parse verb is also a financial hint`() {
        for (verb in parseVerbs()) {
            assertTrue("parse verb not in the financial hint list: $verb", looksFinancial("Rs 100 $verb"))
        }
    }

    /**
     * "WhatsApp BAL to 917036165000" is the helpline Axis puts at the end of its messages, not a
     * balance. Read as one it gave every such message a bank-alert marker, so a promotion quoting
     * an amount qualified on structure alone and reached the review queue.
     */
    @Test
    fun `a promotion is not a bank alert just because it offers a BAL helpline`() {
        val promo = "Pre-approved personal loan of Rs 5,00,000 awaits you! " +
            "WhatsApp BAL to 917036165000 Query? Call 18604195555"
        assertNull(parse(promo))
        assertFalse(looksFinancial(promo))
    }

    @Test
    fun `ignores a balance enquiry`() {
        val body = "Dear customer, your a/c balance is Rs 15,000.00 as on 02-08-26"
        assertNull(parse(body))
        assertFalse(looksFinancial(body))
    }

    // --- sender routing ---

    @Test
    fun `routes an unknown sender to the generic fallback`() {
        val parsed = requireNotNull(
            BankTemplates.findMatch("AD-SOMEBANK", "Rs 100 debited to SHOP on 02-08-26"),
        )
        assertEquals(10000, parsed.amountMinor)
    }

    @Test
    fun `a matching bank template that cannot extract still falls back`() {
        // "HDFC" claims this sender, but the body is a format no template understands; the
        // fallback must still get its turn rather than the match being abandoned.
        assertNull(BankTemplates.findMatch("VM-HDFCBK", "Your OTP is 4455. Do not share."))
        val parsed = requireNotNull(
            BankTemplates.findMatch("VM-HDFCBK", "Rs 75 debited to CHAI POINT on 02-08-26"),
        )
        assertEquals(7500, parsed.amountMinor)
    }

    // --- account label ---

    @Test
    fun `captures the source account as the account label`() {
        val parsed = requireNotNull(
            parse("Debited Rs 1.00 from a/c X6686 on 01Aug26 to KEERTHANA KU."),
        )
        assertEquals("X6686", parsed.accountLabel)
        assertEquals("KEERTHANA KU", parsed.merchant)
    }

    @Test
    fun `captures a card label`() {
        val parsed = requireNotNull(
            parse("Your a/c XX1234 is debited by Rs.320.00 on 02-08-26 at ZOMATO."),
        )
        assertEquals("XX1234", parsed.accountLabel)
    }

    // --- transaction date ---

    @Test
    fun `takes the date from the message, not the clock`() {
        val parsed = requireNotNull(
            parse("Debited Rs 1.00 from a/c X6686 on 01Aug26 19:00 via UPI to KEERTHANA KU."),
        )
        val expected = LocalDateTime(2026, 8, 1, 19, 0, 0).toInstant(TimeZone.currentSystemDefault())
        assertEquals(expected, parsed.occurredAt)
    }

    @Test
    fun `leaves occurredAt null when the message has no date`() {
        val parsed = requireNotNull(parse("Rs 250 debited towards ELECTRICITY BILL."))
        assertNull(parsed.occurredAt)
    }

    // --- amount conversion ---

    @Test
    fun `converts amounts to integer paise`() {
        assertEquals(45000L, parseAmountToMinorUnits("450.00"))
        assertEquals(125050L, parseAmountToMinorUnits("1,250.50"))
        assertEquals(100L, parseAmountToMinorUnits("1.00"))
        assertEquals(9900L, parseAmountToMinorUnits("99"))
    }

    @Test
    fun `returns null rather than throwing on an unparseable amount`() {
        // This runs inside a BroadcastReceiver; a throw here would take the app down.
        assertNull(parseAmountToMinorUnits(","))
        assertNull(parseAmountToMinorUnits(""))
        assertNull(parseAmountToMinorUnits("abc"))
    }

    @Test
    fun `keeps large amounts exact`() {
        assertEquals(9999999999L, parseAmountToMinorUnits("99999999.99"))
    }
}
