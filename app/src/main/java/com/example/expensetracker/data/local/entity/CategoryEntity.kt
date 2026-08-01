package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val householdId: Long = LocalIds.DEFAULT_HOUSEHOLD_ID,
    val name: String,
    val icon: String,
    val colour: Long,
    val isIncome: Boolean = false,
)

/** Seeded on first run. No other starter categories, per decisions log §8. */
const val UNASSIGNED_CATEGORY_NAME = "Unassigned"