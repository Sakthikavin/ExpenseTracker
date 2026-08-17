package com.example.expensetracker.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.LocalIds
import com.example.expensetracker.data.local.entity.OwnAccountEntity
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.data.local.entity.TransactionSource
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.TransactionRepository
import com.example.expensetracker.data.repository.TransferRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * A row in the transactions list. A transfer between the user's own accounts is one event with two
 * legs, so it collapses into a single [Transfer] row rather than appearing twice.
 */
sealed interface TransactionListItem {
    val sortKey: Instant

    /** Stable across recomposition; transfers and singles can never collide. */
    val rowKey: String

    data class Single(val transaction: TransactionEntity) : TransactionListItem {
        override val sortKey: Instant get() = transaction.occurredAt
        override val rowKey: String get() = "txn-${transaction.id}"
    }

    data class Transfer(
        val groupId: String,
        val out: TransactionEntity?,
        val into: TransactionEntity?,
    ) : TransactionListItem {
        private val any: TransactionEntity get() = out ?: requireNotNull(into)
        override val sortKey: Instant get() = any.occurredAt
        override val rowKey: String get() = "transfer-$groupId"
        val amountMinor: Long get() = any.amountMinor
        /** A one-legged transfer is one whose other message never arrived. */
        val isComplete: Boolean get() = out != null && into != null
    }
}

/** One day's worth of rows, oldest-groups-last since [TransactionsViewModel.listItems] is already sorted that way. */
data class TransactionsDayGroup(val date: LocalDate, val items: List<TransactionListItem>)

class TransactionsViewModel(
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
    private val transferRepository: TransferRepository? = null,
) : ViewModel() {

    val transactions: StateFlow<List<TransactionEntity>> = transactionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The list as displayed: transfers folded into one row, everything else untouched. */
    val listItems: StateFlow<List<TransactionListItem>> = transactionRepository.observeAll()
        .map { all ->
            val (transferLegs, singles) = all.partition { it.transferGroupId != null }
            val transfers = transferLegs.groupBy { it.transferGroupId!! }.map { (groupId, legs) ->
                TransactionListItem.Transfer(
                    groupId = groupId,
                    out = legs.firstOrNull { it.direction == Direction.DEBIT },
                    into = legs.firstOrNull { it.direction == Direction.CREDIT },
                )
            }
            (singles.map { TransactionListItem.Single(it) } + transfers).sortedByDescending { it.sortKey }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [listItems], grouped one date header per day instead of repeating the date on every row. */
    val groupedItems: StateFlow<List<TransactionsDayGroup>> = listItems
        .map { items ->
            val zone = TimeZone.currentSystemDefault()
            items.groupBy { it.sortKey.toLocalDateTime(zone).date }
                .map { (date, group) -> TransactionsDayGroup(date, group) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ownAccounts: StateFlow<List<OwnAccountEntity>> =
        (transferRepository?.observeOwnAccounts() ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Plausible other legs for [transaction], best match first. */
    suspend fun transferCandidates(transaction: TransactionEntity): List<TransactionEntity> =
        transferRepository?.manualCandidates(transaction, transactions.value).orEmpty()

    fun linkTransfer(first: TransactionEntity, second: TransactionEntity) {
        viewModelScope.launch { transferRepository?.link(first, second) }
    }

    fun markSingleLegTransfer(transaction: TransactionEntity) {
        viewModelScope.launch { transferRepository?.markSingleLeg(transaction) }
    }

    fun unlinkTransfer(groupId: String) {
        viewModelScope.launch { transferRepository?.unlink(groupId) }
    }

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addManualTransaction(
        amountMinor: Long,
        direction: Direction,
        merchant: String,
        accountLabel: String,
        categoryId: Long?,
        note: String,
        tags: List<String>,
        occurredAt: Instant = Clock.System.now(),
    ) {
        viewModelScope.launch {
            transactionRepository.create(
                TransactionEntity(
                    householdId = LocalIds.DEFAULT_HOUSEHOLD_ID,
                    userId = LocalIds.DEFAULT_USER_ID,
                    amountMinor = amountMinor,
                    direction = direction,
                    occurredAt = occurredAt,
                    merchant = merchant,
                    accountLabel = accountLabel,
                    categoryId = categoryId,
                    note = note,
                    tags = tags,
                    source = TransactionSource.MANUAL,
                ),
            )
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { transactionRepository.delete(id) }
    }

    fun updateCategory(transaction: TransactionEntity, categoryId: Long?) {
        viewModelScope.launch {
            transactionRepository.update(transaction.copy(categoryId = categoryId))
        }
    }
}