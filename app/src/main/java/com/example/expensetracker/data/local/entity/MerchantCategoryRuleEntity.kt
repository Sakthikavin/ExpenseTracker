package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * "Learn as you categorize" — categorizing a merchant once auto-categorizes it next time.
 *
 * [categoryId]'s foreign key is CASCADE, unlike [TransactionEntity.categoryId]'s SET_NULL: a rule
 * pointing at a deleted category is a dangling pointer with no reason to survive, unlike a
 * transaction, which is a real historical record that correctly falls back to Unassigned.
 */
@Entity(
    tableName = "merchant_category_rules",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("merchantKey", unique = true),
        Index("categoryId"),
    ],
)
data class MerchantCategoryRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val householdId: Long = LocalIds.DEFAULT_HOUSEHOLD_ID,
    /** The exact-match lookup key — see [merchantKeyOf]. Never touched by renaming. */
    val merchantKey: String,
    val categoryId: Long,
    /**
     * Cosmetic only, per Addendum 5 — what's shown in place of [merchantKey] on the Merchant Rules
     * screen. Never read by matching, upsert, or rule-drift logic; those key off [merchantKey] alone.
     */
    val displayName: String? = null,
    val updatedAt: Instant,
)

/**
 * The exact-match lookup key for a merchant string — deliberately exact, not fuzzy/substring, so
 * "SWIGGY" and "SWIGGY*ORDER" stay distinct rather than risk a wrong cross-match. Internal
 * whitespace collapses to single spaces so cosmetic differences in a parsed merchant string don't
 * fragment the same real-world merchant into two rules.
 *
 * Deliberately not [com.example.expensetracker.data.sms.PatternLearner.normaliseSender] — that
 * function is for bank *sender IDs* (splits on hyphens/dots, keeps the longest letter segment) and
 * would mangle a merchant string like "SIP MUTUAL FUND" down to "MUTUAL".
 */
fun merchantKeyOf(merchant: String): String =
    merchant.trim().uppercase().replace(Regex("\\s+"), " ")
