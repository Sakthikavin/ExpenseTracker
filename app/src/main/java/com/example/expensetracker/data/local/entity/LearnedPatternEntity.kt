package com.example.expensetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Derived from a manually confirmed "needs review" SMS so the next message from the same
 * sender parses on its own. [fieldMap] maps a semantic field name (amount, merchant) to the
 * capturing-group index in [regex] that holds it.
 */
@Entity(tableName = "learned_patterns")
data class LearnedPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised sender key — see `PatternLearner.normaliseSender`. */
    val senderPattern: String,
    val regex: String,
    val fieldMap: Map<String, Int>,
    val confirmedCount: Int = 1,
    /**
     * The direction the user confirmed. Messages don't reliably state it in a capturable position,
     * so it is recorded once per pattern rather than extracted per message.
     *
     * The SQL default is declared so this column's definition matches what `MIGRATION_1_2` creates
     * for databases upgrading from v1 — Room validates the two against each other at startup.
     */
    @ColumnInfo(defaultValue = "DEBIT")
    val direction: Direction = Direction.DEBIT,
)