package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.TransactionEntity
import kotlin.math.abs
import kotlinx.datetime.Instant

/**
 * Decides whether two transactions are the two legs of one transfer between the user's own
 * accounts.
 *
 * Moving ₹10,000 from HDFC to ICICI produces a debit alert and a credit alert. Both legs are real —
 * each account's balance genuinely changed — so neither is discarded, but together they are not
 * spending or income: the money never left the user's control. Pairing them is what keeps the
 * dashboard honest.
 *
 * This is a different problem from the one [SmsRepository.reconcileWithExisting] solves. There, one
 * event was *reported twice by one bank* and the extra row is noise. Here there are two genuine
 * legs in two different accounts, and collapsing them would corrupt per-account history.
 */
object TransferMatcher {

    /** How far apart the two legs may be. Inter-bank credits can lag the debit by a day or more. */
    private const val WINDOW_HOURS = 72L

    /**
     * Transfer fees mean the legs need not be identical: ₹10,005 out, ₹10,000 in. Anything larger
     * than this is a different payment that merely looks similar.
     */
    private const val FEE_TOLERANCE_MINOR = 2500L

    enum class Confidence {
        /** Safe to link without asking: a shared reference, or both accounts known to be the user's. */
        AUTOMATIC,

        /** Plausible, but needs a human to confirm — surfaced as a suggestion. */
        SUGGESTED,
    }

    data class Match(val counterpart: TransactionEntity, val confidence: Confidence)

    /**
     * @param candidates transactions already stored that could be the other leg.
     * @param ownAccountLabels the masked labels the user has claimed, upper-cased.
     */
    fun match(
        transaction: TransactionEntity,
        candidates: List<TransactionEntity>,
        ownAccountLabels: Set<String>,
        counterpartyAccount: String? = null,
    ): Match? {
        val viable = candidates.filter { it.isViableCounterpartFor(transaction) }
        if (viable.isEmpty()) return null

        // A shared bank reference is proof, not a guess: the two banks are describing one rail
        // movement with the same UTR.
        viable.firstOrNull { sharesReference(it, transaction) }
            ?.let { return Match(it, Confidence.AUTOMATIC) }

        val bothAccountsAreMine = viable.filter { candidate ->
            transaction.accountLabel.normalised() in ownAccountLabels &&
                candidate.accountLabel.normalised() in ownAccountLabels
        }
        bothAccountsAreMine.firstOrNull()?.let { return Match(it, Confidence.AUTOMATIC) }

        // The debit named a destination the user has claimed, even if that leg's own SMS hasn't
        // been seen — still strong enough to link without asking.
        if (counterpartyAccount?.normalised() in ownAccountLabels && counterpartyAccount != null) {
            viable.firstOrNull()?.let { return Match(it, Confidence.AUTOMATIC) }
        }

        return Match(viable.first(), Confidence.SUGGESTED)
    }

    /**
     * Candidates for the manual "mark as transfer" picker — deliberately looser than [match],
     * because a human is choosing.
     */
    fun manualCandidates(
        transaction: TransactionEntity,
        all: List<TransactionEntity>,
    ): List<TransactionEntity> = all
        .filter { it.id != transaction.id && it.direction != transaction.direction }
        .filter { it.transferGroupId == null }
        .filter { withinDays(it.occurredAt, transaction.occurredAt, days = 7) }
        .sortedBy { abs(it.amountMinor - transaction.amountMinor) }
        .take(10)

    /** A group id shared by both legs. Opaque; only equality matters. */
    fun newGroupId(): String = java.util.UUID.randomUUID().toString()

    private fun TransactionEntity.isViableCounterpartFor(other: TransactionEntity): Boolean =
        id != other.id &&
            transferGroupId == null &&
            direction != other.direction &&
            abs(amountMinor - other.amountMinor) <= FEE_TOLERANCE_MINOR &&
            withinHours(occurredAt, other.occurredAt, WINDOW_HOURS)

    private fun sharesReference(a: TransactionEntity, b: TransactionEntity): Boolean {
        val left = a.referenceId ?: return false
        val right = b.referenceId ?: return false
        return left.equals(right, ignoreCase = true)
    }

    private fun withinHours(a: Instant, b: Instant, hours: Long): Boolean =
        abs(a.toEpochMilliseconds() - b.toEpochMilliseconds()) <= hours * 60 * 60 * 1000

    private fun withinDays(a: Instant, b: Instant, days: Long): Boolean =
        withinHours(a, b, days * 24)

    private fun String?.normalised(): String = this?.trim()?.uppercase().orEmpty()
}

/** True when this transaction is one leg of a transfer and so must not count as spend or income. */
val TransactionEntity.isTransfer: Boolean get() = transferGroupId != null
