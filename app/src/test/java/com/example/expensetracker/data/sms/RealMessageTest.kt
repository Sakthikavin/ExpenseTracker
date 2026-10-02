package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.dao.LearnedPatternDao
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.LearnedPatternEntity
import com.example.expensetracker.data.remoterules.TestRuleSets
import com.example.expensetracker.data.remoterules.repositoryWith
import com.example.expensetracker.data.remoterules.repositoryWithNoRules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verbatim messages in [RealMessages], read by the rules the console actually publishes
 * (`PARSING_ARCHITECTURE.md` §4) — there are no built-in templates left to test.
 *
 * When a new bank format shows up, add it to [RealMessages] first and watch it fail here. The fix
 * is then a rule on the console rather than a regex in this repository, and the refreshed
 * `src/test/resources` copy is what proves the published rule reads it.
 */
class RealMessageTest {

    private object NoLearnedPatterns : LearnedPatternDao {
        override suspend fun getAll() = emptyList<LearnedPatternEntity>()
        override suspend fun getForSender(sender: String) = emptyList<LearnedPatternEntity>()
        override suspend fun insert(pattern: LearnedPatternEntity) = 0L
        override suspend fun update(pattern: LearnedPatternEntity) = Unit
    }

    private val published = repositoryWith(TestRuleSets.ruleSetJson(TestRuleSets.GENERIC, TestRuleSets.BANK))

    private fun parse(body: String, sender: String) = runBlocking {
        SmsParser(learnedPatternDao = NoLearnedPatterns, remoteRulesRepository = published).parse(sender, body)
    }

    private fun parsed(body: String, sender: String): ParsedSms {
        val outcome = parse(body, sender)
        assertTrue("expected a parse, got $outcome", outcome is ParseOutcome.Parsed)
        return (outcome as ParseOutcome.Parsed).parsed
    }

    // --- the generic any-sender rules: what the built-in templates used to read ---

    /** `generic_block_scheme_debit_v1`: a multi-line auto-debit whose only name is its scheme. */
    @Test
    fun `reads the Axis auto-debit block format`() {
        val parsed = parsed(RealMessages.axisApy, "AD-AXISBK")
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals(29200, parsed.amountMinor)
        assertEquals("APY", parsed.merchant)
    }

    /**
     * The block rules match `^To …$` per line, which is what keeps the payee off the trailing
     * "WhatsApp BAL to 917036165000" — a helpline number is not a merchant.
     */
    @Test
    fun `never mistakes a helpline number for the payee`() {
        val merchant = parsed(RealMessages.axisApy, "AD-AXISBK").merchant
        assertTrue("merchant must not be a phone number, was '$merchant'", merchant.none(Char::isDigit))
    }

    /** `generic_block_debit_v1`: takes the payee from the `To` line, not the `From` line above it. */
    @Test
    fun `reads the HDFC UPI block format`() {
        val parsed = parsed(RealMessages.hdfcUpi, "VM-HDFCBK")
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals(5800, parsed.amountMinor)
        assertEquals("Google India Digital Serv", parsed.merchant)
    }

    @Test
    fun `reads a single-line debit, handle and all`() {
        val parsed = parsed(RealMessages.federalUpi, "VM-FEDBNK")
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals(100, parsed.amountMinor)
        assertEquals("KEERTHANA KU", parsed.merchant)
        assertNotNull(parsed.occurredAt)
    }

    /** The merchant ending that `_v2` added: a `.` inside a handle no longer ends the name. */
    @Test
    fun `keeps a UPI handle whole instead of stopping at its dot`() {
        val body = "Rs 12000 debited via UPI on 01-09-2026 09:05 to VPA landlord.ravi@ybl " +
            "No 427100394821.Small txns?Use UPI Lite!-Federal Bank"

        assertEquals("landlord.ravi@ybl", parsed(body, "VM-FEDBNK").merchant)
    }

    @Test
    fun `does not take the running balance as the amount`() {
        // "Avl bal:INR 84,966.79" trails the message and must not win.
        assertEquals(500000, parsed(RealMessages.npsDebit, "AD-HDFCBK").amountMinor)
    }

    /**
     * The one regression the move cost (`PARSING_ARCHITECTURE.md` §4): the merchant used to be
     * `NPS Contribution`, guessed out of the NEFT narration by code that no longer exists. A
     * published HDFCBK rule is what reads it now, and until one exists the generic rule names the
     * source account instead.
     */
    @Test
    fun `names the source account until a bank rule reads the NEFT narration`() {
        assertEquals("HDFC Bank XX3941", parsed(RealMessages.npsDebit, "AD-HDFCBK").merchant)
    }

    @Test
    fun `reads the credit leg of the same payment`() {
        val parsed = parsed(RealMessages.npsCredit, "AD-HDFCBK")
        assertEquals(Direction.CREDIT, parsed.direction)
        assertEquals(500000, parsed.amountMinor)
        assertEquals("SAKTHI KAVIN S S", parsed.merchant)
    }

    // --- a published bank rule, and the account label only a bank rule fills ---

    @Test
    fun `a bank rule reads the TMB pair the generic rules cannot`() {
        val credit = parsed(RealMessages.tmbCredit, "AD-TMBANK")
        assertEquals(Direction.CREDIT, credit.direction)
        assertEquals(500000, credit.amountMinor)
        assertEquals("sakthikavincit-2@okaxis", credit.merchant)
        assertEquals("the rule's account group, as captured", "0017", credit.accountLabel)

        val debit = parsed(RealMessages.tmbDebit, "AD-TMBANK")
        assertEquals(Direction.DEBIT, debit.direction)
        assertEquals(178360, debit.amountMinor)
        assertEquals("pinelabs.11093315@pineaxis", debit.merchant)
    }

    @Test
    fun `a generic rule leaves the account label blank`() {
        assertEquals("", parsed(RealMessages.federalUpi, "VM-FEDBNK").accountLabel)
    }

    // --- what still has to reach a person ---

    /** Canara writes `Dr.`, which no rule reads yet — so it waits in review rather than vanishing. */
    @Test
    fun `an unreadable bank alert goes to review`() {
        assertEquals(ParseOutcome.NeedsReview, parse(RealMessages.canaraDebit, "AD-CANBNK"))
    }

    /**
     * A phone that has never synced has no rules at all. Every real alert then waits in review,
     * which the first successful sync walks again (`SmsRepository.reparseNeedsReview`).
     *
     * `federalUpi` is left out: it's a ₹1.00 payment, which the amount floor turns away before the
     * review queue ever sees it — correct, but it says nothing about having no rules.
     */
    @Test
    fun `before the first sync every real alert waits in review`() {
        val unsynced = SmsParser(learnedPatternDao = NoLearnedPatterns, remoteRulesRepository = repositoryWithNoRules())

        runBlocking {
            for (message in listOf(RealMessages.hdfcUpi, RealMessages.tmbDebit, RealMessages.npsDebit)) {
                assertEquals(message, ParseOutcome.NeedsReview, unsynced.parse("AD-BANK", message))
            }
        }
    }

    // --- guards that must keep holding ---

    @Test
    fun `a promotional message is still ignored`() {
        val promo = "Get 50% off! Spend Rs 999 and get Rs 200 cashback. Shop now."
        assertEquals(ParseOutcome.Discarded, parse(promo, "AD-SHOPNO"))
        assertTrue(!looksFinancial(promo))
    }

    @Test
    fun `an OTP is still ignored`() {
        assertEquals(ParseOutcome.Ignored, parse("Your OTP is 445566. Do not share.", "AD-BANK"))
        assertTrue(!looksFinancial("Your OTP is 445566. Do not share."))
    }

    @Test
    fun `both NPS messages carry the same bank reference`() {
        // Nothing stores a reference any more, but a labelled one is still a bank-alert marker.
        val debitRefs = SmsReferenceParser.referencesIn(RealMessages.npsDebit)
        val creditRefs = SmsReferenceParser.referencesIn(RealMessages.npsCredit)
        assertTrue("debit refs: $debitRefs", "HDFCH00842011992" in debitRefs)
        assertTrue("credit refs: $creditRefs", "HDFCH00842011992" in creditRefs)
    }

    @Test
    fun `does not mistake an IFSC code for the transaction reference`() {
        // UTIB0CCH274 is the beneficiary bank's IFSC, not a transaction id.
        assertTrue("UTIB0CCH274" !in SmsReferenceParser.referencesIn(RealMessages.npsDebit))
    }
}
