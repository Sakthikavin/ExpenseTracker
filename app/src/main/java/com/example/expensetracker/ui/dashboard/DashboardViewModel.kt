package com.example.expensetracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.TransactionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

data class CategorySpendRow(val category: CategoryEntity?, val totalMinor: Long, val percentage: Float)

data class DashboardUiState(
    val rangeStart: LocalDate = defaultThisMonthRange().first,
    val rangeEnd: LocalDate = defaultThisMonthRange().second,
    val incomeMinor: Long = 0,
    val expenseMinor: Long = 0,
    /**
     * Money moved between the user's own accounts. Excluded from [incomeMinor] and [expenseMinor],
     * but shown rather than hidden — money that vanishes from a total with no explanation is worse
     * than money counted wrongly.
     */
    val transferMinor: Long = 0,
    val spendByCategory: List<CategorySpendRow> = emptyList(),
)

private fun defaultThisMonthRange(): Pair<LocalDate, LocalDate> {
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(zone).date
    val start = LocalDate(today.year, today.month, 1)
    val end = start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
    return start to end
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val zone = TimeZone.currentSystemDefault()

    private val _selectedRange = MutableStateFlow(defaultThisMonthRange())
    val selectedRange: StateFlow<Pair<LocalDate, LocalDate>> = _selectedRange

    fun selectThisMonth() {
        _selectedRange.value = defaultThisMonthRange()
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        _selectedRange.value = if (start <= end) start to end else end to start
    }

    private val income = _selectedRange.flatMapLatest { (start, end) ->
        transactionRepository.observeTotalByDirection(
            start.atStartOfDayIn(zone),
            end.atTime(23, 59, 59).toInstant(zone),
            Direction.CREDIT,
        )
    }

    private val expense = _selectedRange.flatMapLatest { (start, end) ->
        transactionRepository.observeTotalByDirection(
            start.atStartOfDayIn(zone),
            end.atTime(23, 59, 59).toInstant(zone),
            Direction.DEBIT,
        )
    }

    private val transfers = _selectedRange.flatMapLatest { (start, end) ->
        transactionRepository.observeTransferTotal(
            start.atStartOfDayIn(zone),
            end.atTime(23, 59, 59).toInstant(zone),
        )
    }

    private val spendByCategory = _selectedRange.flatMapLatest { (start, end) ->
        transactionRepository.observeSpendByCategory(
            start.atStartOfDayIn(zone),
            end.atTime(23, 59, 59).toInstant(zone),
        )
    }

    private val categories = categoryRepository.observeAll()

    val uiState: StateFlow<DashboardUiState> = combine(
        _selectedRange,
        income,
        expense,
        spendByCategory,
        categories,
    ) { range, incomeTotal, expenseTotal, spend, categoryList ->
        val categoriesById = categoryList.associateBy { it.id }
        val totalSpend = spend.sumOf { it.totalMinor }.coerceAtLeast(1)
        DashboardUiState(
            rangeStart = range.first,
            rangeEnd = range.second,
            incomeMinor = incomeTotal,
            expenseMinor = expenseTotal,
            spendByCategory = spend
                .map {
                    CategorySpendRow(
                        category = it.categoryId?.let(categoriesById::get),
                        totalMinor = it.totalMinor,
                        percentage = it.totalMinor.toFloat() / totalSpend.toFloat() * 100f,
                    )
                }
                .sortedByDescending { it.totalMinor },
        )
    }
        // Folded in separately: `combine` only takes five flows before it degrades into an
        // untyped array, and this reads better than casting.
        .combine(transfers) { state, transferTotal -> state.copy(transferMinor = transferTotal) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())
}
