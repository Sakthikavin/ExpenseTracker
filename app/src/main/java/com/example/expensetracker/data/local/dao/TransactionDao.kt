package com.example.expensetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

data class CategorySpend(val categoryId: Long?, val totalMinor: Long)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query(
        "SELECT * FROM transactions WHERE occurredAt BETWEEN :start AND :end ORDER BY occurredAt DESC",
    )
    fun observeInRange(start: Instant, end: Instant): Flow<List<TransactionEntity>>

    /**
     * Filters left null are ignored. [tag] and [merchantQuery] match as substrings.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE (:start IS NULL OR occurredAt >= :start)
          AND (:end IS NULL OR occurredAt <= :end)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:accountLabel IS NULL OR accountLabel = :accountLabel)
          AND (:tag IS NULL OR ',' || tags || ',' LIKE '%,' || :tag || ',%')
          AND (:merchantQuery IS NULL OR merchant LIKE '%' || :merchantQuery || '%')
          AND (:minAmountMinor IS NULL OR amountMinor >= :minAmountMinor)
          AND (:maxAmountMinor IS NULL OR amountMinor <= :maxAmountMinor)
        ORDER BY occurredAt DESC
        """,
    )
    fun search(
        start: Instant?,
        end: Instant?,
        categoryId: Long?,
        accountLabel: String?,
        tag: String?,
        merchantQuery: String?,
        minAmountMinor: Long?,
        maxAmountMinor: Long?,
    ): Flow<List<TransactionEntity>>

    /**
     * Transfers are excluded here and in [observeTotalByDirection]: moving money between your own
     * accounts is not spending, so counting it would inflate every total that matters.
     */
    @Query(
        """
        SELECT categoryId, SUM(amountMinor) AS totalMinor FROM transactions
        WHERE direction = :direction AND occurredAt BETWEEN :start AND :end
          AND transferGroupId IS NULL
        GROUP BY categoryId
        """,
    )
    fun observeSpendByCategory(
        start: Instant,
        end: Instant,
        direction: Direction = Direction.DEBIT,
    ): Flow<List<CategorySpend>>

    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
        WHERE direction = :direction AND occurredAt BETWEEN :start AND :end
          AND transferGroupId IS NULL
        """,
    )
    fun observeTotalByDirection(start: Instant, end: Instant, direction: Direction): Flow<Long>

    /** What moved between the user's own accounts — shown separately, never hidden. */
    @Query(
        """
        SELECT COALESCE(SUM(amountMinor), 0) FROM transactions
        WHERE direction = 'DEBIT' AND occurredAt BETWEEN :start AND :end
          AND transferGroupId IS NOT NULL
        """,
    )
    fun observeTransferTotal(start: Instant, end: Instant): Flow<Long>

    /** Exact duplicate detection: two SMS about one payment share the bank's reference. */
    @Query("SELECT * FROM transactions WHERE referenceId = :referenceId AND source = 'SMS' LIMIT 1")
    suspend fun findByReference(referenceId: String): TransactionEntity?

    /**
     * Fallback duplicate detection for transfer confirmations that omit the reference: the same
     * amount, on the same day, in the opposite direction, also from an SMS.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amountMinor = :amountMinor
          AND direction != :direction
          AND source = 'SMS'
          AND occurredAt BETWEEN :dayStart AND :dayEnd
        LIMIT 1
        """,
    )
    suspend fun findOppositeCounterpart(
        amountMinor: Long,
        direction: Direction,
        dayStart: Instant,
        dayEnd: Instant,
    ): TransactionEntity?

    /** Unpaired transactions near [start]..[end] — the pool [TransferMatcher] searches. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE transferGroupId IS NULL AND occurredAt BETWEEN :start AND :end AND id != :excludeId
        ORDER BY occurredAt DESC
        """,
    )
    suspend fun findUnpairedBetween(start: Instant, end: Instant, excludeId: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE transferGroupId = :groupId ORDER BY direction")
    suspend fun findByTransferGroup(groupId: String): List<TransactionEntity>

    @Query("UPDATE transactions SET transferGroupId = :groupId WHERE id IN (:ids)")
    suspend fun setTransferGroup(ids: List<Long>, groupId: String?)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)
}