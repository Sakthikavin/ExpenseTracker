package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.dao.LearnedPatternDao

/** Runs the three-tier design end to end: known templates, then learned patterns, then review. */
class SmsParser(private val learnedPatternDao: LearnedPatternDao) {

    suspend fun parse(sender: String, body: String): ParseOutcome {
        BankTemplates.findMatch(sender, body)?.let { return ParseOutcome.Parsed(it) }

        learnedPatternDao.getForSender(sender)?.let { pattern ->
            PatternLearner.applyToBody(pattern, body)?.let { return ParseOutcome.Parsed(it) }
        }

        return if (looksFinancial(body)) ParseOutcome.NeedsReview else ParseOutcome.Ignored
    }
}