package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * [DISCARDED] is the only status a message lands in *without* looking financial: a message the
 * tier-2 heuristic turned away, kept anyway so the next wording it fails to recognise is visible
 * instead of vanishing. Stored as the enum name in a TEXT column (`Converters`), so adding a value
 * here is not a schema change and needs no migration.
 */
enum class ParseStatus { PARSED, NEEDS_REVIEW, IGNORED, DISCARDED }

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
    /**
     * When this message's redacted template was uploaded for a rule to be written for it. Null
     * until then; set so the review queue stops offering to send a message that's already waiting
     * on a rule.
     */
    val submittedAt: Instant? = null,
)