package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.dao.LearnedPatternDao
import com.example.expensetracker.data.local.dao.RawSmsDao
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.LocalIds
import com.example.expensetracker.data.local.entity.ParseStatus
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.data.local.entity.TransactionSource
import com.example.expensetracker.data.sms.ParseOutcome
import com.example.expensetracker.data.sms.ParsedSms
import com.example.expensetracker.data.sms.PatternLearner
import com.example.expensetracker.data.sms.SmsDateParser
import com.example.expensetracker.data.sms.SmsParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Which bucket [SmsRepository.ingest] sorted a message into — what the SMS history importer counts. */
enum class IngestResult { IMPORTED, NEEDS_REVIEW, IGNORED, DISCARDED }

/** What [SmsRepository.reparseNeedsReview] did: how many queued messages it re-read, and how many left the queue. */
data class ReparseOutcome(val checked: Int, val cleared: Int)

class SmsRepository(
    private val rawSmsDao: RawSmsDao,
    private val learnedPatternDao: LearnedPatternDao,
    private val transactionRepository: TransactionRepository,
    private val parser: SmsParser,
    /** Optional so tests can exercise ingestion without the transfer machinery. */
    private val transferRepository: TransferRepository? = null,
    /** Optional so tests can exercise ingestion without merchant-rule machinery. */
    private val merchantCategoryRuleRepository: MerchantCategoryRuleRepository? = null,
    /** Overridable so a test can cross page boundaries without queueing thousands of rows. */
    private val reparsePageSize: Int = REPARSE_PAGE_SIZE,
    /** Overridable for the same reason — a test shouldn't have to ingest 200 messages to see the cap. */
    private val discardedKeep: Int = DISCARDED_KEEP,
) {
    fun observeNeedsReview(): Flow<List<RawSmsEntity>> = rawSmsDao.observeByStatus(ParseStatus.NEEDS_REVIEW)

    /** The original message behind a transaction, for "view original message" on the transactions list. */
    suspend fun getRawSmsById(id: Long): RawSmsEntity? = rawSmsDao.getById(id)

    /**
     * Entry point from [com.example.expensetracker.data.sms.SmsReceiver] and from the SMS history
     * importer. The [IngestResult] tells the caller which bucket this message fell into; the
     * importer uses it to tally progress, the live receiver ignores it.
     */
    suspend fun ingest(sender: String, body: String, receivedAt: Instant): IngestResult {
        when (val outcome = parser.parse(sender, body)) {
            is ParseOutcome.Parsed -> {
                val rawSmsId = rawSmsDao.insert(
                    RawSmsEntity(sender = sender, body = body, receivedAt = receivedAt, parseStatus = ParseStatus.PARSED),
                )
                // -1 means the unique index rejected this as a duplicate broadcast. Returning here
                // is what stops the same alert being counted as a second transaction.
                if (rawSmsId == DUPLICATE_ROW_ID) return IngestResult.IMPORTED

                recordParsed(
                    RawSmsEntity(
                        id = rawSmsId,
                        sender = sender,
                        body = body,
                        receivedAt = receivedAt,
                        parseStatus = ParseStatus.PARSED,
                    ),
                    outcome.parsed,
                )
                return IngestResult.IMPORTED
            }
            ParseOutcome.NeedsReview -> {
                rawSmsDao.insert(
                    RawSmsEntity(sender = sender, body = body, receivedAt = receivedAt, parseStatus = ParseStatus.NEEDS_REVIEW),
                )
                return IngestResult.NEEDS_REVIEW
            }
            // Financial-looking enough to have reached review, but caught by a known-noise rule —
            // kept for audit (same status a manual dismissal produces), just hidden from the queue.
            ParseOutcome.IgnoredAsNoise -> {
                rawSmsDao.insert(
                    RawSmsEntity(sender = sender, body = body, receivedAt = receivedAt, parseStatus = ParseStatus.IGNORED),
                )
                return IngestResult.IGNORED
            }
            // Mentions money but didn't look like a bank alert. Kept, capped, and visible in
            // Settings — the heuristic is a guess, and this is the only place its misses show up.
            ParseOutcome.Discarded -> {
                val rowId = rawSmsDao.insert(
                    RawSmsEntity(sender = sender, body = body, receivedAt = receivedAt, parseStatus = ParseStatus.DISCARDED),
                )
                if (rowId != DUPLICATE_ROW_ID) rawSmsDao.pruneStatusToNewest(ParseStatus.DISCARDED, discardedKeep)
                return IngestResult.DISCARDED
            }
            // Doesn't look financial at all, and never mentioned money — not worth a row.
            ParseOutcome.Ignored -> return IngestResult.IGNORED
        }
    }

    /**
     * One-time cleanup for the review-queue backlog that predates [SmsParser.ALWAYS_IGNORE_SENDERS]:
     * routes any still-queued row from those senders into IGNORED, exactly like the permanent
     * parser-level filter now does for new messages. Safe to run more than once — a row already
     * IGNORED is no longer NEEDS_REVIEW, so a second run has nothing left to match.
     *
     * @return how many rows were updated.
     */
    suspend fun ignoreConfirmationOnlyBacklog(): Int {
        val stale = rawSmsDao.observeByStatus(ParseStatus.NEEDS_REVIEW).first()
            .filter { PatternLearner.normaliseSender(it.sender) in SmsParser.ALWAYS_IGNORE_SENDERS }
        stale.forEach { rawSmsDao.update(it.copy(parseStatus = ParseStatus.IGNORED)) }
        return stale.size
    }

    /**
     * Manual confirmation from the "needs review" queue; also attempts to learn a pattern.
     *
     * If [rawSms] is already linked to a transaction — a re-confirmation, or a double-tap on Save
     * before the item left the queue — that transaction is updated in place rather than inserting
     * a second row that the raw SMS would then silently stop pointing at.
     */
    suspend fun confirmReview(
        rawSms: RawSmsEntity,
        amountMinor: Long,
        direction: Direction,
        merchant: String,
        categoryId: Long?,
        accountLabel: String,
    ): CategorizeOutcome {
        // The message's own date if it has one, else when we received it — never "now", which
        // would date a message confirmed days later to the day it was confirmed.
        val occurredAt = SmsDateParser.plausibleOccurredAt(SmsDateParser.parse(rawSms.body), rawSms.receivedAt)
        val existing = rawSms.linkedTransactionId?.let { transactionRepository.getById(it) }
        val transactionId = if (existing != null) {
            transactionRepository.update(
                existing.copy(
                    amountMinor = amountMinor,
                    direction = direction,
                    merchant = merchant,
                    occurredAt = occurredAt,
                    categoryId = categoryId,
                    accountLabel = accountLabel,
                ),
            )
            existing.id
        } else {
            createTransaction(
                amountMinor = amountMinor,
                direction = direction,
                merchant = merchant,
                rawSmsId = rawSms.id,
                occurredAt = occurredAt,
                categoryId = categoryId,
                accountLabel = accountLabel,
            )
        }
        rawSmsDao.update(rawSms.copy(parseStatus = ParseStatus.PARSED, linkedTransactionId = transactionId))

        learnPattern(rawSms, amountMinor, merchant, direction)

        // The category was already written above (as part of create/update), so this is the
        // learn-only half of Addendum 4's shared function — the transaction-writing half doesn't
        // apply here since confirmReview does its own create-or-update reconciliation.
        return if (categoryId != null) {
            merchantCategoryRuleRepository?.learnFromCategorization(merchant, categoryId) ?: CategorizeOutcome.Applied
        } else {
            CategorizeOutcome.Applied
        }
    }

    /**
     * Records [parsed] against an existing `raw_sms` row: the shared tail of first-time ingestion
     * and of a re-parse once a new rule finally reads a message that was sitting in review.
     */
    private suspend fun recordParsed(rawSms: RawSmsEntity, parsed: ParsedSms) {
        // Two SMS can describe one movement of money — a debit alert and the transfer confirmation
        // that follows it. Recording both would double-count the payment.
        reconcileWithExisting(parsed, rawSms.sender, rawSms.body, rawSms.receivedAt, rawSms.id)?.let { existing ->
            rawSmsDao.update(rawSms.copy(parseStatus = ParseStatus.PARSED, linkedTransactionId = existing))
            return
        }

        // Silent per Addendum 4, decision #3: a transaction from an already-known merchant is born
        // categorized, no confirmation needed. Pure lookup — never learns, never prompts; that only
        // happens where a human actually chooses a category.
        val ruleCategoryId = merchantCategoryRuleRepository?.categoryForMerchant(parsed.merchant)
        val transactionId = createTransaction(
            amountMinor = parsed.amountMinor,
            direction = parsed.direction,
            merchant = parsed.merchant,
            rawSmsId = rawSms.id,
            occurredAt = SmsDateParser.plausibleOccurredAt(parsed.occurredAt, rawSms.receivedAt),
            categoryId = ruleCategoryId,
            accountLabel = parsed.accountLabel,
            referenceId = parsed.referenceId,
        )
        // Close the raw <-> transaction link; without this the auto-parsed majority of rows point
        // nowhere and only manually confirmed ones are traceable.
        rawSmsDao.update(rawSms.copy(parseStatus = ParseStatus.PARSED, linkedTransactionId = transactionId))

        // Money moved between the user's own accounts produces two legs; pairing them here keeps
        // both rows while stopping either from counting as spending or income.
        transferRepository?.tryPair(
            transaction = transactionRepository.getById(transactionId) ?: return,
            counterpartyAccount = parsed.counterpartyAccount,
        )
    }

    /**
     * Re-runs the parser over the review queue, which is what a freshly pulled rule set is for
     * (§8.1): a rule written for a message already sitting in review should clear it, without
     * waiting for the bank to send another one.
     *
     * Covers the **whole** queue, newest first, a page at a time. It used to stop at the newest 200
     * rows, which left the oldest of a long queue permanently stuck: those rows are never re-read,
     * so no rule published afterwards can ever reach them. The rule regexes are compiled once per
     * sync, so a few thousand rows is milliseconds of regex work, and this already runs off the
     * main thread alongside the sync that triggered it.
     */
    suspend fun reparseNeedsReview(): ReparseOutcome {
        val review = walkUnparsed(ParseStatus.NEEDS_REVIEW)
        // Skipped messages get the same second chance. A rule can read a message the tier-2
        // heuristic didn't think was financial at all, and without this pass the bin is a dead end:
        // the one bucket a published rule could never reach.
        val discarded = walkUnparsed(ParseStatus.DISCARDED)
        return ReparseOutcome(
            checked = review.checked + discarded.checked,
            cleared = review.cleared + discarded.cleared,
        )
    }

    /**
     * Re-parses every row in [status], newest first, a page at a time.
     *
     * A row only ever moves *forward* here — into a transaction, into review, or into ignored.
     * Nothing is demoted, so a row already in the review queue stays there even if today's
     * heuristic would no longer have admitted it: the user has seen it, and taking it away again
     * would be the same disappearing act this whole mechanism exists to prevent.
     */
    private suspend fun walkUnparsed(status: ParseStatus): ReparseOutcome {
        var checked = 0
        var cleared = 0
        // Walk by keyset rather than offset: rows cleared below leave the status while the walk
        // is still going, and an offset would then step over that many rows it never looked at.
        var beforeAt = Instant.DISTANT_FUTURE.toEpochMilliseconds()
        var beforeId = Long.MAX_VALUE

        while (true) {
            val page = rawSmsDao.getPageByStatus(status, beforeAt, beforeId, reparsePageSize)
            if (page.isEmpty()) break
            for (rawSms in page) {
                checked++
                when (val outcome = parser.parse(rawSms.sender, rawSms.body)) {
                    is ParseOutcome.Parsed -> {
                        recordParsed(rawSms, outcome.parsed)
                        cleared++
                    }
                    // A sender newly added to discardSenders: the queue should stop showing its noise.
                    ParseOutcome.IgnoredAsNoise -> {
                        rawSmsDao.update(rawSms.copy(parseStatus = ParseStatus.IGNORED))
                        cleared++
                    }
                    // A skipped message the heuristic now recognises — it leaves the bin for the
                    // queue. Already-queued rows are untouched; this is the promotion case.
                    ParseOutcome.NeedsReview -> if (status == ParseStatus.DISCARDED) {
                        rawSmsDao.update(rawSms.copy(parseStatus = ParseStatus.NEEDS_REVIEW))
                        cleared++
                    }
                    ParseOutcome.Discarded, ParseOutcome.Ignored -> Unit
                }
            }
            val last = page.last()
            beforeAt = last.receivedAt.toEpochMilliseconds()
            beforeId = last.id
            if (page.size < reparsePageSize) break
        }
        return ReparseOutcome(checked = checked, cleared = cleared)
    }

    /** The skipped-message bucket, newest first — what Settings → "Messages I skipped" shows. */
    fun observeDiscarded(): Flow<List<RawSmsEntity>> = rawSmsDao.observeByStatus(ParseStatus.DISCARDED)

    fun observeDiscardedCount(): Flow<Int> = rawSmsDao.observeCountByStatus(ParseStatus.DISCARDED)

    /**
     * Moves a skipped message into the review queue by hand — the escape hatch for when the
     * heuristic was wrong and you can see that it was. From there it behaves like any queued
     * message: confirmable into a transaction, or submittable for a rule.
     */
    suspend fun moveToReview(rawSms: RawSmsEntity) {
        rawSmsDao.update(rawSms.copy(parseStatus = ParseStatus.NEEDS_REVIEW))
    }

    /** Remembers that this message's template went off for a rule, so it isn't offered again. */
    suspend fun markSubmitted(rawSms: RawSmsEntity, at: Instant = Clock.System.now()) {
        rawSmsDao.update(rawSms.copy(submittedAt = at))
    }

    /**
     * Re-dates SMS transactions that a misread body date pushed past the arrival of the message
     * reporting them — see [SmsDateParser.plausibleOccurredAt]. A one-off repair for rows written
     * before that check existed; the parse is redone from the stored body, so a row whose date was
     * merely late (not wrong) keeps it.
     *
     * @return how many transactions were corrected.
     */
    suspend fun repairDatesAheadOfTheirSms(): Int {
        var corrected = 0
        for (transaction in transactionRepository.findDatedAfterTheirSms(SmsDateParser.FUTURE_TOLERANCE.inWholeMilliseconds)) {
            val rawSms = transaction.rawSmsId?.let { rawSmsDao.getById(it) } ?: continue
            val occurredAt = SmsDateParser.plausibleOccurredAt(SmsDateParser.parse(rawSms.body), rawSms.receivedAt)
            if (occurredAt != transaction.occurredAt) {
                transactionRepository.update(transaction.copy(occurredAt = occurredAt))
                corrected++
            }
        }
        return corrected
    }

    /** Manual dismissal from the "needs review" queue; creates no transaction. */
    suspend fun dismissReview(rawSms: RawSmsEntity) {
        rawSmsDao.update(rawSms.copy(parseStatus = ParseStatus.IGNORED))
    }

    /**
     * Decides whether [parsed] is a second message about a payment already recorded.
     *
     * Banks commonly send two SMS for one transfer — an NPS contribution produces a debit alert and
     * a NEFT confirmation, both carrying the reference `HDFCH00842011992`. Recording both would
     * count the money twice.
     *
     * @return the id of the transaction this message duplicates, after making sure that transaction
     * ends up as the debit; or null when this is a genuinely new payment.
     */
    private suspend fun reconcileWithExisting(
        parsed: ParsedSms,
        sender: String,
        body: String,
        receivedAt: Instant,
        rawSmsId: Long,
    ): Long? {
        val existing = findByReference(parsed)
            ?: findTransferCounterpart(parsed, body, receivedAt)
            ?: findExactBodyResend(sender, body, rawSmsId)
        existing ?: return null

        // Two named accounts that differ mean two real legs — money left one and arrived in the
        // other — so this is a transfer to be paired, not a duplicate to be collapsed. Merging here
        // would delete the receiving account's history. One event reported twice by a single bank
        // never names two different accounts.
        if (movesBetweenTwoAccounts(existing, parsed)) return null

        // The money left the account, so the debit is the truthful record. Whichever message
        // arrived first, the surviving row is the debit one.
        if (existing.direction == Direction.CREDIT && parsed.direction == Direction.DEBIT) {
            transactionRepository.update(
                existing.copy(
                    direction = Direction.DEBIT,
                    merchant = parsed.merchant.ifBlank { existing.merchant },
                    accountLabel = parsed.accountLabel.ifBlank { existing.accountLabel },
                    referenceId = parsed.referenceId ?: existing.referenceId,
                ),
            )
        }
        return existing.id
    }

    /**
     * True when the two messages name different accounts — the signature of a transfer's two legs
     * rather than of one bank describing a single event twice.
     *
     * A blank label on either side means the message didn't say, which is not evidence of a second
     * account, so those still merge.
     */
    private fun movesBetweenTwoAccounts(existing: TransactionEntity, parsed: ParsedSms): Boolean {
        val left = existing.accountLabel.trim()
        val right = parsed.accountLabel.trim()
        return left.isNotEmpty() && right.isNotEmpty() && !left.equals(right, ignoreCase = true)
    }

    /**
     * A transfer's two legs legitimately share one reference, so once both exist, a resend of
     * either leg has two rows to choose from. Prefer the one that *is* this leg — same direction,
     * same account — over an arbitrary one; picking the other leg by accident reads as "here's the
     * transfer's other side" and creates a phantom duplicate instead of recognising the resend.
     */
    private suspend fun findByReference(parsed: ParsedSms): TransactionEntity? {
        val candidates = parsed.referenceId?.let { transactionRepository.findAllByReference(it) }.orEmpty()
        if (candidates.size <= 1) return candidates.firstOrNull()
        return candidates.firstOrNull { it.direction == parsed.direction && !movesBetweenTwoAccounts(it, parsed) }
            ?: candidates.first()
    }

    /**
     * The fallback for confirmations that omit the reference: same amount, same day, opposite
     * direction.
     *
     * Deliberately narrow. A same-day refund from a shop looks identical on those three facts
     * alone, so the message must also read like a bank transfer confirmation — otherwise a genuine
     * refund would be swallowed into the original purchase.
     */
    private suspend fun findTransferCounterpart(
        parsed: ParsedSms,
        body: String,
        receivedAt: Instant,
    ): TransactionEntity? {
        if (!TRANSFER_WORDING.containsMatchIn(body)) return null
        val day = SmsDateParser.plausibleOccurredAt(parsed.occurredAt, receivedAt)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        val zone = TimeZone.currentSystemDefault()
        return transactionRepository.findOppositeCounterpart(
            amountMinor = parsed.amountMinor,
            direction = parsed.direction,
            dayStart = day.atStartOfDayIn(zone),
            dayEnd = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
        )
    }

    /**
     * A byte-identical resend of a message already ingested — a bank redelivering a message that
     * originally failed, or (in testing) a rerun seed script — has nothing distinguishing it from
     * the original when the parser couldn't extract a reference number to match on. Matching on the
     * literal message text, not just amount/merchant/direction, keeps this narrow: two genuinely
     * separate same-day purchases at the same merchant for the same amount carry different message
     * text (different running balances, order IDs, timestamps in the body) and won't collide here.
     */
    private suspend fun findExactBodyResend(sender: String, body: String, rawSmsId: Long): TransactionEntity? {
        val prior = rawSmsDao.findLinkedBySenderAndBody(sender, body, excludingId = rawSmsId) ?: return null
        return prior.linkedTransactionId?.let { transactionRepository.getById(it) }
    }

    /**
     * Derives a pattern unless one that already covers this exact message shape exists — in which
     * case the confirmation is just more evidence for it.
     *
     * The old behaviour short-circuited on *any* stored pattern for the sender, so the first
     * pattern derived was permanent even if it never matched anything again.
     */
    private suspend fun learnPattern(
        rawSms: RawSmsEntity,
        amountMinor: Long,
        merchant: String,
        direction: Direction,
    ) {
        val sender = PatternLearner.normaliseSender(rawSms.sender)
        val existing = learnedPatternDao.getForSender(sender)
        val alreadyCovered = existing.firstOrNull { PatternLearner.applyToBody(it, rawSms.body) != null }

        if (alreadyCovered != null) {
            learnedPatternDao.update(alreadyCovered.copy(confirmedCount = alreadyCovered.confirmedCount + 1))
            return
        }
        PatternLearner.derive(rawSms.sender, rawSms.body, amountMinor, merchant, direction)?.let {
            learnedPatternDao.insert(it)
        }
    }

    private suspend fun createTransaction(
        amountMinor: Long,
        direction: Direction,
        merchant: String,
        rawSmsId: Long?,
        occurredAt: Instant,
        categoryId: Long? = null,
        accountLabel: String = "",
        referenceId: String? = null,
    ): Long = transactionRepository.create(
        TransactionEntity(
            householdId = LocalIds.DEFAULT_HOUSEHOLD_ID,
            userId = LocalIds.DEFAULT_USER_ID,
            amountMinor = amountMinor,
            direction = direction,
            occurredAt = occurredAt,
            merchant = merchant,
            accountLabel = accountLabel,
            categoryId = categoryId,
            source = TransactionSource.SMS,
            rawSmsId = rawSmsId,
            referenceId = referenceId,
        ),
    )

    private companion object {
        const val DUPLICATE_ROW_ID = -1L

        /**
         * How many queued rows one re-parse page reads (§8.1). Not a cap on the walk — it bounds
         * how much of the queue is held in memory at once, nothing more.
         */
        const val REPARSE_PAGE_SIZE = 500

        /**
         * How many skipped messages are kept. They're a diagnostic trail, not history: enough to
         * see what the heuristic has been turning away lately, few enough that an inbox full of
         * amount-bearing promotional SMS can't grow the database without bound.
         */
        const val DISCARDED_KEEP = 200

        /**
         * Wording that marks a message as a bank-transfer confirmation rather than an ordinary
         * credit. Without this narrowing, the amount/date fallback would merge a same-day refund
         * into the purchase it refunded.
         */
        val TRANSFER_WORDING = Regex("""(?i)\b(neft|imps|rtgs|upi|money\s+transfer|transferred|transfer)\b""")
    }
}
