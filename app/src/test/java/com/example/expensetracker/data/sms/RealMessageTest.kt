package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.entity.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-1 parsing over the verbatim messages in [RealMessages] — when a new bank format shows up,
 * add it there first and watch it fail here before touching a regex.
 */
class RealMessageTest {

    private fun parse(body: String, sender: String = "AD-TEST") = BankTemplates.findMatch(sender, body)

    // --- Axis: multi-line auto-debit with no payee line ---

    @Test
    fun `parses the Axis auto-debit block format`() {
        val parsed = requireNotNull(parse(RealMessages.axisApy, "AD-AXISBK"))
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals(29200, parsed.amountMinor)
        assertEquals("XX4795", parsed.accountLabel)
    }

    @Test
    fun `never mistakes a helpline number for the payee`() {
        // "WhatsApp BAL to 917036165000" sits after the amount and offers itself as a payee.
        val merchant = requireNotNull(parse(RealMessages.axisApy, "AD-AXISBK")).merchant
        assertTrue("merchant must not be a phone number, was '$merchant'", merchant.any(Char::isLetter))
        assertTrue(merchant.none(Char::isDigit))
        assertEquals("APY", merchant)
    }

    // --- HDFC UPI: multi-line with labelled From/To lines ---

    @Test
    fun `parses the HDFC UPI block format`() {
        val parsed = requireNotNull(parse(RealMessages.hdfcUpi, "VM-HDFCBK"))
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals(5800, parsed.amountMinor)
        assertEquals("Google India Digital Serv", parsed.merchant)
    }

    @Test
    fun `takes the payee from the To line, not the From line`() {
        // "From HDFC Bank A/C *3941" comes first and would otherwise win.
        assertEquals("Google India Digital Serv", requireNotNull(parse(RealMessages.hdfcUpi)).merchant)
    }

    @Test
    fun `a Sent message is never silently discarded`() {
        // It previously matched no parse verb *and* no review keyword, so it vanished entirely.
        assertTrue(looksFinancial(RealMessages.hdfcUpi))
    }

    // --- NPS: the pair that describes one payment ---

    @Test
    fun `takes the NEFT purpose instead of the source account`() {
        // "debited from HDFC Bank XX3941" offers the account the money left; the real purpose is
        // buried at the end of the Info: narration.
        val parsed = requireNotNull(parse(RealMessages.npsDebit, "AD-HDFCBK"))
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals(500000, parsed.amountMinor)
        assertEquals("NPS Contribution", parsed.merchant)
        assertEquals("XX3941", parsed.accountLabel)
    }

    @Test
    fun `does not take the running balance as the amount`() {
        // "Avl bal:INR 84,966.79" trails the message and must not win.
        assertEquals(500000, requireNotNull(parse(RealMessages.npsDebit, "AD-HDFCBK")).amountMinor)
    }

    @Test
    fun `both NPS messages carry the same bank reference`() {
        val debitRefs = SmsReferenceParser.referencesIn(RealMessages.npsDebit)
        val creditRefs = SmsReferenceParser.referencesIn(RealMessages.npsCredit)
        assertTrue("debit refs: $debitRefs", "HDFCH00842011992" in debitRefs)
        assertTrue("credit refs: $creditRefs", "HDFCH00842011992" in creditRefs)
        assertTrue("the pair must be linkable", debitRefs.intersect(creditRefs).isNotEmpty())
    }

    @Test
    fun `does not mistake an IFSC code for the transaction reference`() {
        // UTIB0CCH274 is the beneficiary bank's IFSC, not a transaction id.
        assertTrue("UTIB0CCH274" !in SmsReferenceParser.referencesIn(RealMessages.npsDebit))
    }

    @Test
    fun `reads the reference from a labelled Txn No`() {
        assertEquals("HDFCH00842011992", SmsReferenceParser.primaryReference(RealMessages.npsCredit))
    }

    @Test
    fun `reads a bare numeric Ref`() {
        assertEquals("119088866187", SmsReferenceParser.primaryReference(RealMessages.hdfcUpi))
    }

    // --- guards that must keep holding ---

    @Test
    fun `a promotional message is still ignored`() {
        val promo = "Get 50% off! Spend Rs 999 and get Rs 200 cashback. Shop now."
        assertNull(parse(promo))
        assertTrue(!looksFinancial(promo))
    }

    @Test
    fun `an OTP is still ignored`() {
        assertNull(parse("Your OTP is 445566. Do not share."))
        assertTrue(!looksFinancial("Your OTP is 445566. Do not share."))
    }

    @Test
    fun `single-line formats still parse after the block-format addition`() {
        val parsed = requireNotNull(parse("Rs 450.00 debited to swiggy@icici on 02-08-26. Ref 4432112."))
        assertEquals(45000, parsed.amountMinor)
        assertEquals("swiggy@icici", parsed.merchant)
        assertNotNull(parsed.occurredAt)
    }
}
