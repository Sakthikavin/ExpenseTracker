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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Which bucket [SmsRepository.ingest] sorted a message into — what the SMS history importer counts. */
enum class IngestResult { IMPORTED, NEEDS_REVIEW, IGNORED }

class SmsRepository(
    private val rawSmsDao: RawSmsDao,
    private val learnedPatternDao: LearnedPatternDao,
    private val transactionRepository: TransactionRepository,
    private val parser: SmsParser,
    /** Optional so tests can exercise ingestion without the transfer machinery. */
    private val transferRepository: TransferRepository? = null,
    /** Optional so tests can exercise ingestion without merchant-rule machinery. */
    private val merchantCategoryRuleRepository: MerchantCategoryRuleRepository? = null,
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

                // Two SMS can describe one movement of money — a debit alert and the transfer
                // confirmation that follows it. Recording both would double-count the payment.
                reconcileWithExisting(outcome.parsed, sender, body, receivedAt, rawSmsId)?.let { existing ->
                    rawSmsDao.update(
                        RawSmsEntity(
                            id = rawSmsId,
                            sender = sender,
                            body = body,
                            receivedAt = receivedAt,
                            parseStatus = ParseStatus.PARSED,
                            linkedTransactionId = existing,
                        ),
                    )
                    return IngestResult.IMPORTED
                }

                // Silent per Addendum 4, decision #3: a transaction from an already-known merchant
                // is born categorized, no confirmation needed. Pure lookup — never learns, never
                // prompts; that only happens where a human actually chooses a category.
                val ruleCategoryId = merchantCategoryRuleRepository?.categoryForMerchant(outcome.parsed.merchant)
                val transactionId = createTransaction(
                    amountMinor = outcome.parsed.amountMinor,
                    direction = outcome.parsed.direction,
                    merchant = outcome.parsed.merchant,
                    rawSmsId = rawSmsId,
                    occurredAt = outcome.parsed.occurredAt ?: receivedAt,
                    categoryId = ruleCategoryId,
                    accountLabel = outcome.parsed.accountLabel,
                    referenceId = outcome.parsed.referenceId,
                )
                // Close the raw <-> transaction link; without this the auto-parsed majority of rows
                // point nowhere and only manually confirmed ones are traceable.
                rawSmsDao.update(
                    RawSmsEntity(
                        id = rawSmsId,
                        sender = sender,
                        body = body,
                        receivedAt = receivedAt,
                        parseStatus = ParseStatus.PARSED,
                        linkedTransactionId = transactionId,
                    ),
                )

                // Money moved between the user's own accounts produces two legs; pairing them here
                // keeps both rows while stopping either from counting as spending or income.
                transferRepository?.tryPair(
                    transaction = transactionRepository.getById(transactionId) ?: return IngestResult.IMPORTED,
                    counterpartyAccount = outcome.parsed.counterpartyAccount,
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
            // Doesn't look financial at all — not worth a row.
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
        val occurredAt = SmsDateParser.parse(rawSms.body) ?: rawSms.receivedAt
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
        val day = (parsed.occurredAt ?: receivedAt).toLocalDateTime(TimeZone.currentSystemDefault()).date
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
         * Wording that marks a message as a bank-transfer confirmation rather than an ordinary
         * credit. Without this narrowing, the amount/date fallback would merge a same-day refund
         * into the purchase it refunded.
         */
        val TRANSFER_WORDING = Regex("""(?i)\b(neft|imps|rtgs|upi|money\s+transfer|transferred|transfer)\b""")
    }
}
