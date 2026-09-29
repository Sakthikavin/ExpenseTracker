package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.dao.CategorySpend
import com.example.expensetracker.data.local.dao.MerchantCount
import com.example.expensetracker.data.local.dao.TransactionDao
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

class TransactionRepository(private val transactionDao: TransactionDao) {
    fun observeAll(): Flow<List<TransactionEntity>> = transactionDao.observeAll()

    fun observeInRange(start: Instant, end: Instant): Flow<List<TransactionEntity>> =
        transactionDao.observeInRange(start, end)

    fun search(
        start: Instant? = null,
        end: Instant? = null,
        categoryId: Long? = null,
        accountLabel: String? = null,
        tag: String? = null,
        merchantQuery: String? = null,
        minAmountMinor: Long? = null,
        maxAmountMinor: Long? = null,
    ): Flow<List<TransactionEntity>> = transactionDao.search(
        start, end, categoryId, accountLabel, tag, merchantQuery, minAmountMinor, maxAmountMinor,
    )

    fun observeSpendByCategory(start: Instant, end: Instant): Flow<List<CategorySpend>> =
        transactionDao.observeSpendByCategory(start, end)

    fun observeTotalByDirection(start: Instant, end: Instant, direction: Direction): Flow<Long> =
        transactionDao.observeTotalByDirection(start, end, direction)

    suspend fun create(transaction: TransactionEntity): Long = transactionDao.insert(transaction)

    /** @see com.example.expensetracker.data.local.dao.TransactionDao.findAllByReference */
    suspend fun findAllByReference(referenceId: String): List<TransactionEntity> =
        transactionDao.findAllByReference(referenceId)

    /** @see com.example.expensetracker.data.local.dao.TransactionDao.findOppositeCounterpart */
    suspend fun findOppositeCounterpart(
        amountMinor: Long,
        direction: Direction,
        dayStart: Instant,
        dayEnd: Instant,
    ): TransactionEntity? =
        transactionDao.findOppositeCounterpart(amountMinor, direction, dayStart, dayEnd)

    /** Money moved between the user's own accounts in this window — shown apart from spending. */
    fun observeTransferTotal(start: Instant, end: Instant): Flow<Long> =
        transactionDao.observeTransferTotal(start, end)

    suspend fun getById(id: Long): TransactionEntity? = transactionDao.getById(id)

    suspend fun update(transaction: TransactionEntity) = transactionDao.update(transaction)

    /** @see com.example.expensetracker.data.local.dao.TransactionDao.findDatedAfterTheirSms */
    suspend fun findDatedAfterTheirSms(toleranceMillis: Long) = transactionDao.findDatedAfterTheirSms(toleranceMillis)

    suspend fun delete(id: Long) = transactionDao.delete(id)

    /** @see com.example.expensetracker.data.local.dao.TransactionDao.observeMerchantCounts */
    fun observeMerchantCounts(): Flow<List<MerchantCount>> = transactionDao.observeMerchantCounts()

    /** @see com.example.expensetracker.data.local.dao.TransactionDao.findUnassignedByMerchantKey */
    suspend fun findUnassignedByMerchantKey(merchantKey: String): List<TransactionEntity> =
        transactionDao.findUnassignedByMerchantKey(merchantKey)

    /** @see com.example.expensetracker.data.local.dao.TransactionDao.applyCategoryToUnassignedByMerchantKey */
    suspend fun applyCategoryToUnassignedByMerchantKey(merchantKey: String, categoryId: Long) =
        transactionDao.applyCategoryToUnassignedByMerchantKey(merchantKey, categoryId)

    /** @see com.example.expensetracker.data.local.dao.TransactionDao.findEarliestByMerchantKey */
    suspend fun findEarliestByMerchantKey(merchantKey: String): TransactionEntity? =
        transactionDao.findEarliestByMerchantKey(merchantKey)
}