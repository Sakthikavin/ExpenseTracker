package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.dao.LearnedPatternDao

/** Runs the three-tier design end to end: known templates, then learned patterns, then review. */
class SmsParser(private val learnedPatternDao: LearnedPatternDao) {

    suspend fun parse(sender: String, body: String): ParseOutcome {
        BankTemplates.findMatch(sender, body)?.let { return ParseOutcome.Parsed(it) }

        // Try every pattern learned for this sender, not just the first — one sender can need a
        // separate pattern per message shape (a debit alert and a credit alert differ).
        learnedPatternDao.getForSender(PatternLearner.normaliseSender(sender))
            .firstNotNullOfOrNull { PatternLearner.applyToBody(it, body) }
            ?.let { return ParseOutcome.Parsed(it) }

        return if (looksFinancial(body)) ParseOutcome.NeedsReview else ParseOutcome.Ignored
    }
}
