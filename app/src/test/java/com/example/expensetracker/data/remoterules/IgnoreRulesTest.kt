package com.example.expensetracker.data.remoterules

import com.example.expensetracker.data.local.dao.LearnedPatternDao
import com.example.expensetracker.data.local.entity.LearnedPatternEntity
import com.example.expensetracker.data.sms.ParseOutcome
import com.example.expensetracker.data.sms.SmsParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DECLINED_SMS =
    "TXN DECLINED: Rs.500 on 29-09-26 at 19:00 on HDFC Bank Debit Card xx1234. " +
        "Reason: Online set Limit Exceeded. Modify:https://1.hdfc.bank.in/HDFCBK/s/a/E0WMgeP0"

/** A real HDFC spend, worded so the built-in templates read it. */
private const val SPEND_SMS = "Rs 450.00 debited to swiggy@icici on 29-09-26. Ref 4432112. -HDFC Bank"

class IgnoreRulesTest {

    private object NoLearnedPatterns : LearnedPatternDao {
        override suspend fun getAll() = emptyList<LearnedPatternEntity>()
        override suspend fun getForSender(sender: String) = emptyList<LearnedPatternEntity>()
        override suspend fun insert(pattern: LearnedPatternEntity) = 0L
        override suspend fun update(pattern: LearnedPatternEntity) = Unit
    }

    /** Seeds the repository through its disk cache, which is loaded and compiled on construction. */
    private fun repositoryWith(ignoreRulesJson: String?, rulesJson: String = "[]"): RemoteRulesRepository {
        val cached = buildString {
            append("""{"version":9,"updatedAt":"2026-09-29T00:00:00Z","discardSenders":[],"rules":$rulesJson""")
            if (ignoreRulesJson != null) append(""","ignoreRules":$ignoreRulesJson""")
            append("}")
        }
        return RemoteRulesRepository(RemoteRulesApi(), FakeSharedPreferences(mapOf("remote_rules_cached_set" to cached)))
    }

    private fun hdfcDeclinedRule(pattern: String = "^TXN DECLINED: ") =
        """[{"id":"hdfcbk_txn_declined_v1","senders":["HDFCBK"],"pattern":"$pattern","reason":"Declined transaction"}]"""

    private fun parse(repository: RemoteRulesRepository, sender: String, body: String) = runBlocking {
        SmsParser(learnedPatternDao = NoLearnedPatterns, remoteRulesRepository = repository).parse(sender, body)
    }

    @Test
    fun `takes a declined alert out of the review queue`() {
        // Without the rule this message reaches review — it's noise every phone has to dismiss by
        // hand. (No built-in template reads it today, so nothing books it either; the ordering
        // test below is what guards against a rule that would.)
        assertEquals(ParseOutcome.NeedsReview, parse(repositoryWith(null), "AD-HDFCBK", DECLINED_SMS))
        assertEquals(ParseOutcome.IgnoredAsNoise, parse(repositoryWith(hdfcDeclinedRule()), "AD-HDFCBK", DECLINED_SMS))
    }

    /**
     * IGNORE_RULES.md §3: ignore rules run before every parsing tier. Here a remote rule *does*
     * read the declined alert as a ₹500 spend, so getting this order wrong books a payment that
     * never happened.
     */
    @Test
    fun `an ignore rule beats a rule that would have parsed the same message`() {
        val greedyRule = """{"id":"hdfc_greedy","senders":["HDFCBK"],"direction":"debit",""" +
            """"pattern":"Rs\\.(?<amount>[\\d,.]+) on (?<date>\\S+)","fieldMap":{"amount":1},"priority":50}"""

        val withoutIgnore = repositoryWith(ignoreRulesJson = null, rulesJson = "[$greedyRule]")
        assertTrue(parse(withoutIgnore, "AD-HDFCBK", DECLINED_SMS) is ParseOutcome.Parsed)

        val withIgnore = repositoryWith(hdfcDeclinedRule(), rulesJson = "[$greedyRule]")
        assertEquals(ParseOutcome.IgnoredAsNoise, parse(withIgnore, "AD-HDFCBK", DECLINED_SMS))
    }

    @Test
    fun `a real spend from the same sender still parses`() {
        assertTrue(parse(repositoryWith(hdfcDeclinedRule()), "AD-HDFCBK", SPEND_SMS) is ParseOutcome.Parsed)
    }

    @Test
    fun `the same text from another sender is not ignored`() {
        val outcome = parse(repositoryWith(hdfcDeclinedRule()), "AD-ICICIB", DECLINED_SMS)

        assertTrue(outcome.toString(), outcome !is ParseOutcome.IgnoredAsNoise)
    }

    @Test
    fun `matches a sender carrying an operator prefix and suffix`() {
        assertTrue(repositoryWith(hdfcDeclinedRule()).isIgnoredMessage("BP-HDFCBK-S", DECLINED_SMS))
    }

    @Test
    fun `a pattern that cannot compile is skipped, not thrown`() {
        assertEquals(false, repositoryWith(hdfcDeclinedRule(pattern = "(")).isIgnoredMessage("AD-HDFCBK", DECLINED_SMS))
    }

    @Test
    fun `a cache written before ignore rules existed still loads`() {
        assertEquals(false, repositoryWith(null).isIgnoredMessage("AD-HDFCBK", DECLINED_SMS))
    }

    @Test
    fun `a malformed entry is dropped without losing the good one`() {
        val repository = repositoryWith(
            """[{"id":"broken","senders":[]},
               {"id":"hdfcbk_txn_declined_v1","senders":["HDFCBK"],"pattern":"^TXN DECLINED: ","reason":"Declined"}]""",
        )

        assertTrue(repository.isIgnoredMessage("AD-HDFCBK", DECLINED_SMS))
    }
}
