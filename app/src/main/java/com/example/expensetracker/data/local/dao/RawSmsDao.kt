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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rawSms: RawSmsEntity): Long

    @Update
    suspend fun update(rawSms: RawSmsEntity)
}