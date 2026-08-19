package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.dao.LearnedPatternDao

/** Runs the three-tier design end to end: known templates, then learned patterns, then review. */
class SmsParser(
    private val learnedPatternDao: LearnedPatternDao,
    /** Read fresh on every parse so a Settings change takes effect without restarting the app. */
    private val ignoreBelowMinor: () -> Long = { DEFAULT_IGNORE_BELOW_MINOR },
) {

    suspend fun parse(sender: String, body: String): ParseOutcome {
        if (PatternLearner.normaliseSender(sender) in ALWAYS_IGNORE_SENDERS) return ParseOutcome.IgnoredAsNoise

        BankTemplates.findMatch(sender, body)?.let { return ParseOutcome.Parsed(it) }

        // Try every pattern learned for this sender, not just the first — one sender can need a
        // separate pattern per message shape (a debit alert and a credit alert differ).
        learnedPatternDao.getForSender(PatternLearner.normaliseSender(sender))
            .firstNotNullOfOrNull { PatternLearner.applyToBody(it, body) }
            ?.let { return ParseOutcome.Parsed(it) }

        if (looksLikePreNotice(body)) return ParseOutcome.IgnoredAsNoise
        if (!looksFinancial(body)) return ParseOutcome.Ignored

        // A template/learned pattern can confidently read an unusual-but-real small amount (a ₹1
        // UPI payment is still a real payment); this only guards the loose, unconfirmed heuristic
        // that would otherwise surface a bare verification ping for review.
        val looseAmount = looseAmountMinor(body)
        if (looseAmount != null && looseAmount < ignoreBelowMinor()) return ParseOutcome.IgnoredAsNoise

        return ParseOutcome.NeedsReview
    }

    companion object {
        /**
         * Confirmation-only senders whose messages just restate a debit already captured under a
         * different alert — an NPS contribution's actual debit comes from the bank; this is only the
         * trustee/registrar's own separate confirmation of that same movement. A new noisy sender is
         * a one-line addition here, not a rule the user authors in the app.
         */
        val ALWAYS_IGNORE_SENDERS = setOf("NPSCRA", "PTNNPS", "AXISMF", "ITDCPC")

        /** ₹2 in paise — verification pings ("Received! INR 1.00…") never mean anything as spending. */
        const val DEFAULT_IGNORE_BELOW_MINOR = 200L
        const val PREF_IGNORE_BELOW_MINOR = "ignore_transactions_below_minor"
    }
}
