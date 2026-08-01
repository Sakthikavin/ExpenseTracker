package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

enum class ParseStatus { PARSED, NEEDS_REVIEW, IGNORED }

@Entity(tableName = "raw_sms")
data class RawSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val receivedAt: Instant,
    val parseStatus: ParseStatus,
    val linkedTransactionId: Long? = null,
)