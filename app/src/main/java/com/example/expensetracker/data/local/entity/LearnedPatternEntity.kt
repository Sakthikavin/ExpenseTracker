package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Derived from a manually confirmed "needs review" SMS so the next message from the same
 * sender parses on its own. [fieldMap] maps a semantic field name (amount, merchant,
 * direction) to the capturing-group index in [regex] that holds it.
 */
@Entity(tableName = "learned_patterns")
data class LearnedPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Sender ID (or prefix) this pattern applies to, e.g. "TMB-BNK" or a full address. */
    val senderPattern: String,
    val regex: String,
    val fieldMap: Map<String, Int>,
    val confirmedCount: Int = 1,
)