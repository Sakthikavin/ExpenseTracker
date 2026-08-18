package com.example.expensetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.expensetracker.data.local.entity.ParseStatus
import com.example.expensetracker.data.local.entity.RawSmsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RawSmsDao {
    @Query("SELECT * FROM raw_sms WHERE parseStatus = :status ORDER BY receivedAt DESC")
    fun observeByStatus(status: ParseStatus = ParseStatus.NEEDS_REVIEW): Flow<List<RawSmsEntity>>

    @Query("SELECT * FROM raw_sms WHERE id = :id")
    suspend fun getById(id: Long): RawSmsEntity?

    /**
     * Returns -1 when the unique (sender, body, receivedAt) index rejects the row as a duplicate,
     * which is how [com.example.expensetracker.data.repository.SmsRepository] detects a redelivered
     * broadcast and avoids counting the same money twice.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rawSms: RawSmsEntity): Long

    @Update
    suspend fun update(rawSms: RawSmsEntity)

    /**
     * An earlier message from the same sender with byte-identical text, already linked to a
     * transaction — the signature of a genuine resend (bank redelivery, a rerun seed script) that
     * arrived with a different [receivedAt][com.example.expensetracker.data.local.entity.RawSmsEntity.receivedAt]
     * and so wasn't caught by the (sender, body, receivedAt) unique index.
     */
    @Query(
        "SELECT * FROM raw_sms WHERE sender = :sender AND body = :body " +
            "AND linkedTransactionId IS NOT NULL AND id != :excludingId LIMIT 1",
    )
    suspend fun findLinkedBySenderAndBody(sender: String, body: String, excludingId: Long): RawSmsEntity?
}