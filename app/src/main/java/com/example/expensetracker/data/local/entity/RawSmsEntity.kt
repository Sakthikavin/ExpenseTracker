package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

enum class ParseStatus { PARSED, NEEDS_REVIEW, IGNORED }

/**
 * The unique index is the deduplication guard: Android can redeliver an `SMS_RECEIVED` broadcast,
 * and without it the same alert would be ingested twice and the money counted twice.
 */
@Entity(
    tableName = "raw_sms",
    indices = [Index(value = ["sender", "body", "receivedAt"], unique = true)],
)
data class RawSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAt: Instant,
    val parseStatus: ParseStatus,
    val linkedTransactionId: Long? = null,
)