package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.dao.LearnedPatternDao
import com.example.expensetracker.data.local.entity.LearnedPatternEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a message ends up when no tier reads it.
 *
 * The distinction these tests hold is the one that failed: a bank alert whose wording nothing
 * recognised used to leave no row at all, so it was absent from transactions, from the review
 * queue, and from anywhere a person could look. Now anything that mentions money keeps a row, and
 * only messages that never mentioned money are dropped outright — which is what stops the
 * skipped-message list from becoming a second copy of the SMS inbox.
 */
class SmsParserRoutingTest {

    private object NoLearnedPatterns : LearnedPatternDao {
        override suspend fun getAll() = emptyList<LearnedPatternEntity>()
        override suspend fun getForSender(sender: String) = emptyList<LearnedPatternEntity>()
        override suspend fun insert(pattern: LearnedPatternEntity) = 0L
        override suspend fun update(pattern: LearnedPatternEntity) = Unit
    }

    private fun parse(body: String, sender: String = "AD-SOMEBK") = runBlocking {
        SmsParser(learnedPatternDao = NoLearnedPatterns).parse(sender, body)
    }

    @Test
    fun `the canara alert that used to vanish now reaches the review queue`() {
        assertEquals(ParseOutcome.NeedsReview, parse(RealMessages.canaraDebit, sender = "AD-CANBNK"))
    }

    @Test
    fun `a message that mentions money but looks nothing like an alert is kept as skipped`() {
        assertEquals(
            ParseOutcome.Discarded,
            parse("Reminder: your order of Rs 1,299 is out for delivery. Track it in the app."),
        )
    }

    @Test
    fun `a message with no amount at all is dropped without a row`() {
        assertEquals(ParseOutcome.Ignored, parse("Your order has been delivered."))
        assertEquals(ParseOutcome.Ignored, parse("Your OTP is 445566. Do not share."))
    }

    /** A balance enquiry mentions money, so it's kept — but it must not reach the review queue. */
    @Test
    fun `a balance enquiry is skipped rather than queued`() {
        assertEquals(
            ParseOutcome.Discarded,
            parse("Dear customer, your a/c balance is Rs 15,000.00 as on 02-08-26"),
        )
    }
}
