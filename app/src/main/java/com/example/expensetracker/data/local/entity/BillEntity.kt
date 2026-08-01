package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

enum class BillRecurrence { WEEKLY, MONTHLY, YEARLY }

@Entity(tableName = "bills")
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val householdId: Long = LocalIds.DEFAULT_HOUSEHOLD_ID,
    val name: String,
    /** Minor currency units (paise). */
    val amountMinor: Long,
    /** Day of month (1-31) the bill is due; interpreted per [recurrence]. */
    val dueDay: Int,
    val recurrence: BillRecurrence = BillRecurrence.MONTHLY,
    val lastNotifiedAt: Instant? = null,
)