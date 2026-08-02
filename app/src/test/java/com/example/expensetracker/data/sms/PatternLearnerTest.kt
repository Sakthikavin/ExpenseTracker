package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PatternLearnerTest {

    /** A format tier 1 deliberately doesn't parse, so it's a realistic review-queue candidate. */
    private val firstMessage =
        "Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000"

    private fun derive(
        body: String = firstMessage,
        amountMinor: Long = 59900,
        merchant: String = "NETFLIX.COM",
        direction: Direction = Direction.DEBIT,
        sender: String = "AD-ICICIB",
    ) = PatternLearner.derive(sender, body, amountMinor, merchant, direction)

    // --- the regression this rewrite exists for ---

    @Test
    fun `replays against a later message with a different date and balance`() {
        val pattern = requireNotNull(derive())

        val later = "Purchase of Rs 249.00 on your ICICI Card XX12 at SPOTIFY.COM. Avl Lmt Rs 44751"
        val parsed = requireNotNull(PatternLearner.applyToBody(pattern, later)) {
            "the learned pattern froze a volatile value and no longer matches"
        }

        assertEquals(24900, parsed.amountMinor)
        assertEquals("SPOTIFY.COM", parsed.merchant)
    }

    @Test
    fun `replays across a changed transaction date`() {
        val pattern = requireNotNull(
            derive(
                body = "Your a/c XX1 debited by Rs.320.00 on 02-08-26 at ZOMATO. Avl Bal 5000",
                amountMinor = 32000,
                merchant = "ZOMATO",
            ),
        )
        val later = "Your a/c XX1 debited by Rs.850.00 on 03-09-26 at SWIGGY. Avl Bal 4150"
        val parsed = requireNotNull(PatternLearner.applyToBody(pattern, later))
        assertEquals(85000, parsed.amountMinor)
        assertEquals("SWIGGY", parsed.merchant)
    }

    @Test
    fun `still refuses a genuinely different message shape`() {
        val pattern = requireNotNull(derive())
        assertNull(PatternLearner.applyToBody(pattern, "Rs 500 credited from RAHUL on 02-08-26"))
    }

    // --- direction ---

    @Test
    fun `replays the confirmed direction instead of assuming debit`() {
        val pattern = requireNotNull(
            derive(
                body = "Salary of Rs 50000.00 processed to a/c XX99 by ACME PAYROLL on 01Aug26",
                amountMinor = 5000000,
                merchant = "ACME PAYROLL",
                direction = Direction.CREDIT,
            ),
        )
        assertEquals(Direction.CREDIT, pattern.direction)

        val later = "Salary of Rs 52000.00 processed to a/c XX99 by ACME PAYROLL on 01Sep26"
        val parsed = requireNotNull(PatternLearner.applyToBody(pattern, later))
        assertEquals(Direction.CREDIT, parsed.direction)
        assertEquals(5200000, parsed.amountMinor)
    }

    // --- sender normalisation ---

    @Test
    fun `normalises the rotating operator prefix`() {
        assertEquals("FEDBNK", PatternLearner.normaliseSender("AD-FEDBNK"))
        assertEquals("FEDBNK", PatternLearner.normaliseSender("VM-FEDBNK"))
        assertEquals("FEDBNK", PatternLearner.normaliseSender("JD-FEDBNK-S"))
        assertEquals("HDFCBK", PatternLearner.normaliseSender("hdfcbk"))
    }

    @Test
    fun `stores the normalised sender on the pattern`() {
        assertEquals("ICICIB", requireNotNull(derive()).senderPattern)
    }

    // --- generalisation details ---

    @Test
    fun `keeps the fixed wording literal`() {
        val generalised = PatternLearner.generaliseLiteral(" on 02-08-26 at ")
        assertNotNull(Regex(generalised).find(" on 05-11-27 at "))
        assertNull(Regex(generalised).find(" from 05-11-27 at "))
    }

    @Test
    fun `does not derive a pattern when the amount is not in the body`() {
        // The user retyped a value that never appears verbatim; better no pattern than a wrong one.
        assertNull(derive(amountMinor = 12345))
    }

    @Test
    fun `derives a pattern even without a locatable merchant`() {
        val pattern = derive(merchant = "Retyped Merchant Name")
        assertNotNull(pattern)
        assertNull(requireNotNull(pattern).fieldMap["merchant"])
    }
}
