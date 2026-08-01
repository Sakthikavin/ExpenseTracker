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

    @Query(
        """
        SELECT categoryId, SUM(amountMinor) AS totalMinor FROM transactions
        WHERE direction = :direction AND occurredAt BETWEEN :start AND :end
        GROUP BY categoryId
        """,
    )
    fun observeSpendByCategory(
        start: Instant,
        end: Instant,
        direction: Direction = Direction.DEBIT,
    ): Flow<List<CategorySpend>>

    @Query(
        "SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE direction = :direction AND occurredAt BETWEEN :start AND :end",
    )
    fun observeTotalByDirection(start: Instant, end: Instant, direction: Direction): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)
}