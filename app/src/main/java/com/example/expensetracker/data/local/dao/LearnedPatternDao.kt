package com.example.expensetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.expensetracker.data.local.entity.LearnedPatternEntity

@Dao
interface LearnedPatternDao {
    @Query("SELECT * FROM learned_patterns")
    suspend fun getAll(): List<LearnedPatternEntity>

    @Query("SELECT * FROM learned_patterns WHERE senderPattern = :sender LIMIT 1")
    suspend fun getForSender(sender: String): LearnedPatternEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pattern: LearnedPatternEntity): Long

    @Update
    suspend fun update(pattern: LearnedPatternEntity)
}