package com.example.expensetracker.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

enum class Direction { DEBIT, CREDIT }

enum class TransactionSource { SMS, MANUAL }

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("categoryId"),
        Index("occurredAt"),
        Index("rawSmsId"),
        Index("referenceId"),
        Index("transferGroupId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val householdId: Long = LocalIds.DEFAULT_HOUSEHOLD_ID,
    val userId: Long = LocalIds.DEFAULT_USER_ID,
    /** Minor currency units (paise) to avoid floating-point rounding on money. */
    val amountMinor: Long,
    val direction: Direction,
    val occurredAt: Instant,
    val merchant: String,
    val accountLabel: String,
    val categoryId: Long?,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val receiptPath: String? = null,
    val source: TransactionSource,
    val isPrivate: Boolean = false,
    val rawSmsId: Long? = null,
    /**
     * The bank's own reference for this movement of money, when the SMS carried one.
     *
     * Banks send two messages for a single transfer — a debit alert and a confirmation — and this
     * is the exact link between them, used to avoid recording the payment twice.
     */
    val referenceId: String? = null,
    /**
     * Links the two legs of a transfer between the user's own accounts. Both rows are kept — each
     * account's history stays truthful — but neither counts as spending or income, because no money
     * entered or left the user's control.
     *
     * A group can hold a single leg when only one of the two messages arrived.
     */
    val transferGroupId: String? = null,
)