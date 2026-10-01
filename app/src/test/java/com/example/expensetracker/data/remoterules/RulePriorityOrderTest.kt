package com.example.expensetracker.data.remoterules

import com.example.expensetracker.data.local.dao.LearnedPatternDao
import com.example.expensetracker.data.local.entity.LearnedPatternEntity
import com.example.expensetracker.data.sms.ParseOutcome
import com.example.expensetracker.data.sms.SmsParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a remote rule runs relative to the built-in templates (`BANK_RULES_FIRST.md` §2).
 *
 * A reviewed, bank-specific rule has to beat the generic template, or it can never fix a message
 * the template already reads — badly. The generic copies of those same templates, which the
 * console publishes as rules at priority 1–4, have to lose to them, because the in-app template
 * reads more from the same message than any rule can carry.
 */
class RulePriorityOrderTest {

    /** The message the whole change is for: the template stops at the dot inside the UPI handle. */
    private val fedbnkUpiDebit = "Rs 12000 debited via UPI on 01-09-2026 09:05 to VPA " +
        "landlord.ravi@ybl No 427100394821.Small txns?Use UPI Lite!-Federal Bank"

    private val selfTransfer = "Rs 5000.00 debited from A/c XX1234 on 29-09-26 " +
        "To ICICI Bank A/C XX4795. Ref 427100394821"

    /** "deducted" is in no template's verb list, so only a rule can read this one. */
    private val walletDebit = "INR 100.00 towards ABCSTORE was deducted from your wallet."

    private object NoLearnedPatterns : LearnedPatternDao {
        override suspend fun getAll() = emptyList<LearnedPatternEntity>()
        override suspend fun getForSender(sender: String) = emptyList<LearnedPatternEntity>()
        override suspend fun insert(pattern: LearnedPatternEntity) = 0L
        override suspend fun update(pattern: LearnedPatternEntity) = Unit
    }

    /** Seeds the repository through its disk cache, which is loaded and compiled on construction. */
    private fun repositoryWith(vararg rules: String): RemoteRulesRepository {
        val cached = """{"version":14,"updatedAt":"2026-10-01T00:00:00Z","discardSenders":[],""" +
            """"rules":[${rules.joinToString(",")}],"ignoreRules":[]}"""
        return RemoteRulesRepository(RemoteRulesApi(), FakeSharedPreferences(mapOf("remote_rules_cached_set" to cached)))
    }

    private fun rule(id: String, pattern: String, priority: Int) =
        """{"id":"$id","senders":["FEDBNK"],"direction":"debit","pattern":"$pattern",""" +
            """"fieldMap":{"amount":1,"merchant":2},"priority":$priority}"""

    /** Reads the handle whole, which is the merchant the template gets wrong. */
    private fun fedbnkVpaRule(priority: Int) =
        rule("fedbnk_upi_debit_v1", """Rs (\\d[\\d,.]*) debited.*?to VPA (\\S+) No""", priority)

    /** The shape of a console-published `android_*` copy: amount, direction, merchant, nothing more. */
    private fun genericCopy(priority: Int) =
        rule("android_debit_v1", """Rs ([\\d,.]+) debited.*?[Tt]o (\\S+)""", priority)

    private fun parse(repository: RemoteRulesRepository, body: String, sender: String = "VM-FEDBNK") =
        runBlocking {
            SmsParser(learnedPatternDao = NoLearnedPatterns, remoteRulesRepository = repository).parse(sender, body)
        }

    private fun merchantOf(outcome: ParseOutcome): String {
        assertTrue(outcome.toString(), outcome is ParseOutcome.Parsed)
        return (outcome as ParseOutcome.Parsed).parsed.merchant
    }

    @Test
    fun `a bank rule reads the message the template reads badly`() {
        // Without the rule, the template's merchant stops at the dot in `landlord.ravi@ybl`.
        assertEquals("VPA landlord", merchantOf(parse(repositoryWith(), fedbnkUpiDebit)))

        val outcome = parse(repositoryWith(fedbnkVpaRule(priority = 10)), fedbnkUpiDebit)
        assertEquals("landlord.ravi@ybl", merchantOf(outcome))
        assertEquals(1200000L, (outcome as ParseOutcome.Parsed).parsed.amountMinor)
    }

    /** Below the reserved line a rule is a fallback, so the template still answers first. */
    @Test
    fun `the same rule published below priority 5 does not overtake the template`() {
        assertEquals("VPA landlord", merchantOf(parse(repositoryWith(fedbnkVpaRule(priority = 3)), fedbnkUpiDebit)))
    }

    /**
     * The reason the generic copies stay behind the templates: a rule yields amount, direction and
     * merchant, while the template also resolves the counterparty account `TransferMatcher` needs.
     */
    @Test
    fun `a generic copy never costs the template its richer reading`() {
        val outcome = parse(repositoryWith(genericCopy(priority = 4)), selfTransfer)

        assertEquals("ICICI Bank", merchantOf(outcome))
        assertNotNull(
            "the template resolves the destination account; no remote rule can",
            (outcome as ParseOutcome.Parsed).parsed.counterpartyAccount,
        )
    }

    @Test
    fun `a message only a generic copy reads is still parsed`() {
        val walletRule = rule(
            "android_wallet_debit_v1",
            """INR ([\\d,.]+) towards (\\S+) was deducted""",
            priority = 2,
        )

        // No template reads it and it carries no bank-alert structure, so unaided it only lands in
        // the skipped bin.
        assertEquals(ParseOutcome.Discarded, parse(repositoryWith(), walletDebit))
        assertEquals("ABCSTORE", merchantOf(parse(repositoryWith(walletRule), walletDebit)))
    }
}
