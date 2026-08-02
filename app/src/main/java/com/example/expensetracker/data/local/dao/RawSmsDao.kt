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

    /**
     * Returns -1 when the unique (sender, body, receivedAt) index rejects the row as a duplicate,
     * which is how [com.example.expensetracker.data.repository.SmsRepository] detects a redelivered
     * broadcast and avoids counting the same money twice.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rawSms: RawSmsEntity): Long

    @Update
    suspend fun update(rawSms: RawSmsEntity)
}