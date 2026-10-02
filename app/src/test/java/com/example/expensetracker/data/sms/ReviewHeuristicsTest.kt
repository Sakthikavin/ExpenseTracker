package com.example.expensetracker.data.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heuristics that decide where an unmatched message goes. Carried over from the deleted
 * `BankTemplatesTest` when parsing moved to the published rule list — the templates went, but these
 * guards are about code that stayed, and each one marks a message that was once lost or once noise.
 */
class ReviewHeuristicsTest {

    // --- what must reach a person ---

    /**
     * Canara writes the direction as `Dr.`, so no keyword in the old closed list matched and the
     * message was discarded without a row — the review queue never saw it. Its structure says bank
     * alert three times over: a masked account, a reference, and a balance.
     */
    @Test
    fun `an alert that abbreviates the direction still looks financial`() {
        assertTrue(looksFinancial(RealMessages.canaraDebit))
    }

    @Test
    fun `a future-tense EMI notice still looks financial, and the pre-notice filter catches it`() {
        val body = "EMI of Rs 4,500 will be debited to your loan account on 05-08-26"
        assertTrue(looksFinancial(body))
        assertTrue("a notice of money that hasn't moved is not a transaction", looksLikePreNotice(body))
    }

    @Test
    fun `a message that also confirms a past debit is not a pre-notice`() {
        val body = "Last EMI of Rs 500.00 was debited on 01-08-26. Next EMI of Rs 500.00 will be debited on 01-09-26."
        assertFalse(looksLikePreNotice(body))
    }

    /** Every verb a published rule reads must also admit the message to review, or it vanishes. */
    @Test
    fun `every verb the published rules parse is also a financial hint`() {
        val ruleVerbs = listOf(
            "debited", "debit", "spent", "paid", "withdrawn", "sent", "transferred", "credited", "received",
        )

        for (verb in ruleVerbs) {
            assertTrue("rule verb not in the financial hint list: $verb", looksFinancial("Rs 100 $verb"))
        }
    }

    // --- what must not ---

    @Test
    fun `a promotional message is not financial`() {
        assertFalse(looksFinancial("Get 50% off! Spend Rs 999 and get Rs 200 cashback. Shop now."))
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

        assertFalse(looksFinancial(promo))
    }

    /** A balance enquiry's only amount is its balance, which is what separates it from an alert. */
    @Test
    fun `a balance enquiry is not financial`() {
        assertFalse(looksFinancial("Dear customer, your a/c balance is Rs 15,000.00 as on 02-08-26"))
    }

    @Test
    fun `an OTP is not financial and mentions no money`() {
        val body = "Your OTP is 445566. Do not share."
        assertFalse(looksFinancial(body))
        assertFalse("no amount means no row at all", mentionsAmount(body))
    }

    // --- the line between a skipped row and no row ---

    @Test
    fun `mentioning an amount is what earns a row`() {
        assertTrue(mentionsAmount("Reminder: your order of Rs 1,299 is out for delivery."))
        assertFalse(mentionsAmount("Your order has been delivered."))
    }

    // --- the amount floor reads loosely on purpose ---

    @Test
    fun `reads the first amount mentioned, however the bank wrote it`() {
        assertEquals(100L, looseAmountMinor("Received! INR 1.00 in HDFC Bank A/c xx3941"))
        assertEquals(12345600L, looseAmountMinor("Rs 1,23,456.00 credited"))
        assertNull(looseAmountMinor("Your order has been delivered."))
    }
}
