package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Only monthly exists in v1; no rollover of unused amounts between months (decisions log §8). */
enum class BudgetPeriod { MONTHLY }

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId", unique = true)],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val householdId: Long = LocalIds.DEFAULT_HOUSEHOLD_ID,
    val categoryId: Long,
    /** Minor currency units (paise). */
    val monthlyLimitMinor: Long,
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
)