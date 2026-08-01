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
import com.example.expensetracker.data.sms.PatternLearner
import com.example.expensetracker.data.sms.SmsParser
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

class SmsRepository(
    private val rawSmsDao: RawSmsDao,
    private val learnedPatternDao: LearnedPatternDao,
    private val transactionRepository: TransactionRepository,
    private val parser: SmsParser,
) {
    fun observeNeedsReview(): Flow<List<RawSmsEntity>> = rawSmsDao.observeByStatus(ParseStatus.NEEDS_REVIEW)

    /** Entry point from [com.example.expensetracker.data.sms.SmsReceiver]. */
    suspend fun ingest(sender: String, body: String, receivedAt: Instant) {
        when (val outcome = parser.parse(sender, body)) {
            is ParseOutcome.Parsed -> {
                val rawSmsId = rawSmsDao.insert(
                    RawSmsEntity(sender = sender, body = body, receivedAt = receivedAt, parseStatus = ParseStatus.PARSED),
                )
                createTransaction(outcome.parsed.amountMinor, outcome.parsed.direction, outcome.parsed.merchant, rawSmsId)
            }
            ParseOutcome.NeedsReview -> {
                rawSmsDao.insert(
                    RawSmsEntity(sender = sender, body = body, receivedAt = receivedAt, parseStatus = ParseStatus.NEEDS_REVIEW),
                )
            }
            ParseOutcome.Ignored -> Unit
        }
    }

    /** Manual confirmation from the "needs review" queue; also attempts to learn a pattern. */
    suspend fun confirmReview(
        rawSms: RawSmsEntity,
        amountMinor: Long,
        direction: Direction,
        merchant: String,
        categoryId: Long?,
        accountLabel: String,
    ) {
        val transactionId = createTransaction(amountMinor, direction, merchant, rawSms.id, categoryId, accountLabel)
        rawSmsDao.update(rawSms.copy(parseStatus = ParseStatus.PARSED, linkedTransactionId = transactionId))

        val existing = learnedPatternDao.getForSender(rawSms.sender)
        if (existing != null) {
            learnedPatternDao.update(existing.copy(confirmedCount = existing.confirmedCount + 1))
        } else {
            PatternLearner.derive(rawSms.sender, rawSms.body, amountMinor, merchant)?.let {
                learnedPatternDao.insert(it)
            }
        }
    }

    private suspend fun createTransaction(
        amountMinor: Long,
        direction: Direction,
        merchant: String,
        rawSmsId: Long?,
        categoryId: Long? = null,
        accountLabel: String = "",
    ): Long = transactionRepository.create(
        TransactionEntity(
            householdId = LocalIds.DEFAULT_HOUSEHOLD_ID,
            userId = LocalIds.DEFAULT_USER_ID,
            amountMinor = amountMinor,
            direction = direction,
            occurredAt = kotlinx.datetime.Clock.System.now(),
            merchant = merchant,
            accountLabel = accountLabel,
            categoryId = categoryId,
            source = TransactionSource.SMS,
            rawSmsId = rawSmsId,
        ),
    )
}