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

    /**
     * All patterns for a sender, most-confirmed first — a sender can legitimately need more than
     * one (a debit alert and a credit alert are worded differently), and the one that has been
     * confirmed most often is the likeliest to match.
     */
    @Query("SELECT * FROM learned_patterns WHERE senderPattern = :sender ORDER BY confirmedCount DESC")
    suspend fun getForSender(sender: String): List<LearnedPatternEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pattern: LearnedPatternEntity): Long

    @Update
    suspend fun update(pattern: LearnedPatternEntity)
}