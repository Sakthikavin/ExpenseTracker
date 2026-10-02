package com.example.expensetracker.data.sms

import com.example.expensetracker.data.local.dao.LearnedPatternDao
import com.example.expensetracker.data.remoterules.RemoteRulesRepository

/**
 * One ordered rule list, then the review queue (`PARSING_ARCHITECTURE.md` §2).
 *
 * The app ships no rules and reads no message itself: everything that turns an SMS into a
 * transaction is published from the console and arrives by sync, so a bank whose wording changes is
 * a rule edit rather than an app release. What's left on the device is the order below, the
 * review-queue heuristics in `ReviewHeuristics.kt`, and the learned patterns — which come after the
 * rules, since a rule was reviewed against real samples while a learned pattern is a same-device
 * guess [PatternLearner] made from one confirmation.
 */
class SmsParser(
    private val learnedPatternDao: LearnedPatternDao,
    /** Null in tests that don't exercise the remote-rules tier. */
    private val remoteRulesRepository: RemoteRulesRepository? = null,
    /** Read fresh on every parse so a Settings change takes effect without restarting the app. */
    private val ignoreBelowMinor: () -> Long = { DEFAULT_IGNORE_BELOW_MINOR },
) {

    suspend fun parse(sender: String, body: String): ParseOutcome {
        if (PatternLearner.normaliseSender(sender) in ALWAYS_IGNORE_SENDERS) return ParseOutcome.IgnoredAsNoise
        // discardSenders from the published rule set (§5): a noisy sender one person reports
        // silences it for everyone on the next sync, not just the sender's ALWAYS_IGNORE list above.
        if (remoteRulesRepository?.isDiscardedSender(sender) == true) return ParseOutcome.IgnoredAsNoise
        // Published ignore rules (IGNORE_RULES.md §3) must come *before* the rules below:
        // "TXN DECLINED: Rs.500 ... HDFC Bank Debit Card" reads like a real spend, so running this
        // later would book a payment that never happened.
        if (remoteRulesRepository?.isIgnoredMessage(sender, body) == true) return ParseOutcome.IgnoredAsNoise

        // The published rule list, highest priority first. Nothing matches before the first sync.
        remoteRulesRepository?.tryMatch(sender, body)?.let { return ParseOutcome.Parsed(it) }

        // Try every pattern learned for this sender, not just the first — one sender can need a
        // separate pattern per message shape (a debit alert and a credit alert differ).
        learnedPatternDao.getForSender(PatternLearner.normaliseSender(sender))
            .firstNotNullOfOrNull { PatternLearner.applyToBody(it, body) }
            ?.let { return ParseOutcome.Parsed(it) }

        if (looksLikePreNotice(body)) return ParseOutcome.IgnoredAsNoise
        // Turned away by the heuristic — but if it mentioned money, keep the row so the mistake is
        // findable. A wording nothing recognised used to leave no trace at all, which is how
        // Canara's `Dr.` alerts went missing for months.
        if (!looksFinancial(body)) {
            return if (mentionsAmount(body)) ParseOutcome.Discarded else ParseOutcome.Ignored
        }

        // A rule or learned pattern can confidently read an unusual-but-real small amount (a ₹1
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
