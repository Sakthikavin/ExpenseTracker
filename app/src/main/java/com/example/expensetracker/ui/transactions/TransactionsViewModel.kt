package com.example.expensetracker.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.LocalIds
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.data.local.entity.TransactionSource
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.data.repository.MerchantCategoryRuleRepository
import com.example.expensetracker.data.repository.SmsRepository
import com.example.expensetracker.data.repository.TransactionRepository
import com.example.expensetracker.ui.common.CategorizePrompt
import com.example.expensetracker.ui.common.toPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * A row in the transactions list.
 *
 * Still a sealed interface with one case: it carried a folded-transfer row until parsing moved to
 * the published rule list, and the day-grouping and keying below are written against it.
 */
sealed interface TransactionListItem {
    val sortKey: Instant

    /** Stable across recomposition. */
    val rowKey: String

    data class Single(val transaction: TransactionEntity) : TransactionListItem {
        override val sortKey: Instant get() = transaction.occurredAt
        override val rowKey: String get() = "txn-${transaction.id}"
    }
}

/** One day's worth of rows, oldest-groups-last since [TransactionsViewModel.listItems] is already sorted that way. */
data class TransactionsDayGroup(val date: LocalDate, val items: List<TransactionListItem>)

/**
 * What the list is narrowed to, carried in from a click-through on the Dashboard. All filters are
 * optional and combine with AND. [categoryId] uses [UNASSIGNED_CATEGORY_ID] to mean "unassigned
 * specifically" — a transaction's own `categoryId == null` already means unassigned, so filtering
 * needs a value that isn't null to say the same thing.
 */
data class TransactionFilter(
    val direction: Direction? = null,
    val categoryId: Long? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
) {
    val isActive: Boolean get() = direction != null || categoryId != null || startDate != null || endDate != null

    companion object {
        const val UNASSIGNED_CATEGORY_ID = -1L
    }
}

data class FilterSummary(val count: Int, val totalMinor: Long)

private fun matchesFilter(transaction: TransactionEntity, filter: TransactionFilter, zone: TimeZone): Boolean {
    if (filter.direction != null && transaction.direction != filter.direction) return false
    when (filter.categoryId) {
        null -> Unit
        TransactionFilter.UNASSIGNED_CATEGORY_ID -> if (transaction.categoryId != null) return false
        else -> if (transaction.categoryId != filter.categoryId) return false
    }
    val date = transaction.occurredAt.toLocalDateTime(zone).date
    if (filter.startDate != null && date < filter.startDate) return false
    if (filter.endDate != null && date > filter.endDate) return false
    return true
}

class TransactionsViewModel(
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
    val filter: TransactionFilter = TransactionFilter(),
    private val merchantCategoryRuleRepository: MerchantCategoryRuleRepository? = null,
    private val smsRepository: SmsRepository? = null,
) : ViewModel() {

    private val _categorizePrompt = MutableStateFlow<CategorizePrompt?>(null)

    /** Addendum 4's retroactive-apply / rule-drift follow-up question, when one is pending. */
    val categorizePrompt: StateFlow<CategorizePrompt?> = _categorizePrompt.asStateFlow()

    val transactions: StateFlow<List<TransactionEntity>> = transactionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filteredTransactions = transactionRepository.observeAll()
        .map { all ->
            val zone = TimeZone.currentSystemDefault()
            all.filter { matchesFilter(it, filter, zone) }
        }

    val filterSummary: StateFlow<FilterSummary> = filteredTransactions
        .map { FilterSummary(count = it.size, totalMinor = it.sumOf { t -> t.amountMinor }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterSummary(0, 0))

    val listItems: StateFlow<List<TransactionListItem>> = filteredTransactions
        .map { all -> all.map { TransactionListItem.Single(it) }.sortedByDescending { it.sortKey } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [listItems], grouped one date header per day instead of repeating the date on every row. */
    val groupedItems: StateFlow<List<TransactionsDayGroup>> = listItems
        .map { items ->
            val zone = TimeZone.currentSystemDefault()
            items.groupBy { it.sortKey.toLocalDateTime(zone).date }
                .map { (date, group) -> TransactionsDayGroup(date, group) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The SMS a transaction was parsed from, for "view original message" — null for manual entries. */
    suspend fun rawSmsFor(transaction: TransactionEntity): RawSmsEntity? =
        transaction.rawSmsId?.let { smsRepository?.getRawSmsById(it) }

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
            // A category the user left as Unassigned still gets the merchant's learned rule
            // (Addendum 4: "apply the same lookup to manual entries at creation time") — an explicit
            // pick is never overridden.
            val resolvedCategoryId = categoryId ?: merchantCategoryRuleRepository?.categoryForMerchant(merchant)
            transactionRepository.create(
                TransactionEntity(
                    householdId = LocalIds.DEFAULT_HOUSEHOLD_ID,
                    userId = LocalIds.DEFAULT_USER_ID,
                    amountMinor = amountMinor,
                    direction = direction,
                    occurredAt = occurredAt,
                    merchant = merchant,
                    accountLabel = accountLabel,
                    categoryId = resolvedCategoryId,
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
            val outcome = merchantCategoryRuleRepository?.setCategoryAndLearn(transaction, categoryId)
            if (outcome == null) {
                transactionRepository.update(transaction.copy(categoryId = categoryId))
            } else {
                _categorizePrompt.value = outcome.toPrompt(transaction.merchant)
            }
        }
    }

    /** "Apply to N" on [CategorizePrompt.RetroactiveApply]. */
    fun applyRetroactively(prompt: CategorizePrompt.RetroactiveApply) {
        viewModelScope.launch {
            merchantCategoryRuleRepository?.applyRetroactively(prompt.merchantKey, prompt.categoryId)
            _categorizePrompt.value = null
        }
    }

    /** "Update rule" on [CategorizePrompt.RuleUpdate]. */
    fun confirmRuleUpdate(prompt: CategorizePrompt.RuleUpdate) {
        viewModelScope.launch {
            merchantCategoryRuleRepository?.confirmRuleUpdate(prompt.merchantKey, prompt.newCategoryId)
            _categorizePrompt.value = null
        }
    }

    /** "Not now" / "Keep rule as-is" — the category change already applied, only the rule question is declined. */
    fun dismissCategorizePrompt() {
        _categorizePrompt.value = null
    }
}