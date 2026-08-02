package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An account the user has confirmed is theirs, keyed on the masked label banks print in SMS
 * ("XX3941", "*3941", "X6686").
 *
 * This is what turns "₹10,000 left HDFC and ₹10,000 arrived at ICICI" into "one transfer between my
 * own accounts" rather than ₹10,000 of spending plus ₹10,000 of income. The labels are discovered
 * from messages already parsed, so registering one is a toggle rather than a form.
 */
@Entity(
    tableName = "own_accounts",
    indices = [Index(value = ["label"], unique = true)],
)
data class OwnAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The masked label exactly as it appears in the bank's SMS. */
    val label: String,
    /** What the user calls it — "HDFC Savings". Falls back to [label] when blank. */
    val nickname: String = "",
) {
    val displayName: String get() = nickname.ifBlank { label }
}
