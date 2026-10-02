package com.example.expensetracker.data.remoterules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the one rule list picks a rule (`PARSING_ARCHITECTURE.md` §2–§4): the any-sender wildcard,
 * priority order across both kinds of rule, and what happens on a phone that has never synced.
 */
class RemoteRulesRepositoryTest {

    private val debit = "Rs 450.00 debited to SWIGGY. Avl bal Rs 1,000.00"

    /** Amount only, no merchant group — so a match from this one leaves the merchant blank. */
    private fun wildcardRule(priority: Int, id: String = "generic") =
        """{"id":"$id","senders":["*"],"direction":"debit","pattern":"Rs ([\\d,.]+) debited",""" +
            """"fieldMap":{"amount":1},"priority":$priority}"""

    /** A rule written for one sender, knowing where that sender puts the payee. */
    private fun bankRule(sender: String, priority: Int, id: String = "bank") =
        """{"id":"$id","senders":["$sender"],"direction":"debit",""" +
            """"pattern":"Rs ([\\d,.]+) debited to (\\S+)\\.","fieldMap":{"amount":1,"merchant":2},""" +
            """"priority":$priority}"""

    private fun repository(vararg rules: String) =
        repositoryWith(TestRuleSets.ruleSetJson(extraRules = "[${rules.joinToString(",")}]"))

    @Test
    fun `a wildcard rule applies to every sender`() {
        val repository = repository(wildcardRule(priority = 4))

        for (sender in listOf("VM-FEDBNK", "AD-HDFCBK", "JX-SOMETHINGNEW")) {
            assertEquals(sender, 45000L, repository.tryMatch(sender, debit)?.amountMinor)
        }
    }

    /**
     * The reason priority still matters without the old tier split: a rule written for one sender
     * has to beat the wildcard that would also match, because it was written knowing that sender's
     * wording — here, it's the only one that gets the payee out.
     */
    @Test
    fun `a bank rule outranks a wildcard rule`() {
        val repository = repository(wildcardRule(priority = 4), bankRule("HDFCBK", priority = 10))

        assertEquals("SWIGGY", repository.tryMatch("AD-HDFCBK", debit)?.merchant)
        // Any other sender falls through to the wildcard, which reads the amount and nothing else.
        assertEquals("", repository.tryMatch("AD-OTHER", debit)?.merchant)
        assertEquals(45000L, repository.tryMatch("AD-OTHER", debit)?.amountMinor)
    }

    /** And published below the wildcard, it loses — the list is ordered by priority, nothing else. */
    @Test
    fun `a bank rule published below a wildcard rule loses to it`() {
        val repository = repository(wildcardRule(priority = 6), bankRule("HDFCBK", priority = 2))

        assertEquals("", repository.tryMatch("AD-HDFCBK", debit)?.merchant)
    }

    @Test
    fun `a sender-specific rule does not leak to other senders`() {
        val repository = repository(bankRule("TMBANK", priority = 10))

        assertEquals("SWIGGY", repository.tryMatch("AD-TMBANK", debit)?.merchant)
        assertNull(repository.tryMatch("AD-HDFCBK", debit))
    }

    @Test
    fun `an ignore rule can be published for every sender too`() {
        val ignoreForAll = """[{"id":"promo","senders":["*"],"pattern":"debited","reason":"test"}]"""
        val cached = """{"version":16,"updatedAt":"x","discardSenders":[],"rules":[],""" +
            """"ignoreRules":$ignoreForAll}"""

        assertEquals(true, repositoryWith(cached).isIgnoredMessage("AD-ANYBANK", debit))
    }

    /**
     * No cached set is the state of a fresh install before its first sync. Nothing matches, nothing
     * is ignored, and no sender is discarded — the app ships no rules to fall back on, by design.
     */
    @Test
    fun `with no cached rule set nothing matches`() {
        val repository = repositoryWithNoRules()

        assertNull(repository.tryMatch("AD-HDFCBK", debit))
        assertEquals(false, repository.isIgnoredMessage("AD-HDFCBK", debit))
        assertEquals(false, repository.isDiscardedSender("AD-HDFCBK"))
        assertNull(repository.cachedVersion)
    }

    /** `"*"` is deliberately not honoured here: it would silence every message on every phone. */
    @Test
    fun `a wildcard in discardSenders is not honoured`() {
        val cached = """{"version":16,"updatedAt":"x","discardSenders":["*"],"rules":[],"ignoreRules":[]}"""

        assertEquals(false, repositoryWith(cached).isDiscardedSender("AD-HDFCBK"))
    }

    /**
     * A rule whose pattern doesn't compile is dropped on load, which is silent by design (§10) —
     * so the only way to notice the whole set didn't load is to watch a rule still read a message.
     */
    @Test
    fun `the published rules in the test resources load and match`() {
        val repository = repositoryWith(TestRuleSets.ruleSetJson(TestRuleSets.GENERIC, TestRuleSets.BANK))

        assertEquals(45000L, repository.tryMatch("AD-ANYBANK", debit)?.amountMinor)
        assertEquals("SWIGGY", repository.tryMatch("AD-ANYBANK", debit)?.merchant)
        assertEquals(16, repository.cachedVersion)
    }

    @Test
    fun `a rule that cannot compile is skipped rather than thrown`() {
        val broken = """{"id":"broken","senders":["*"],"direction":"debit","pattern":"(",""" +
            """"fieldMap":{"amount":1},"priority":99}"""
        val repository = repository(broken, wildcardRule(priority = 1))

        assertEquals(45000L, repository.tryMatch("AD-HDFCBK", debit)?.amountMinor)
    }
}
