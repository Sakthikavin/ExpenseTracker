package com.example.expensetracker.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.BudgetEntity
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.repository.BudgetRepository
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.TransactionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

data class BudgetRow(
    val category: CategoryEntity,
    val budget: BudgetEntity?,
    val spentMinor: Long,
)

class BudgetsViewModel(
    private val budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository,
    transactionRepository: TransactionRepository,
) : ViewModel() {

    private val zone = TimeZone.currentSystemDefault()
    private val today = Clock.System.now().toLocalDateTime(zone).date
    private val monthStart = LocalDate(today.year, today.month, 1)
    private val monthEnd = monthStart.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
    private val rangeStart = monthStart.atStartOfDayIn(zone)
    private val rangeEnd = monthEnd.atTime(23, 59, 59).toInstant(zone)

    val rows: StateFlow<List<BudgetRow>> = combine(
        categoryRepository.observeAll(),
        budgetRepository.observeAll(),
        transactionRepository.observeSpendByCategory(rangeStart, rangeEnd),
    ) { categories, budgets, spend ->
        val budgetsByCategory = budgets.associateBy { it.categoryId }
        val spendByCategory = spend.associate { it.categoryId to it.totalMinor }
        categories
            .filterNot { it.isIncome }
            .map { category ->
                BudgetRow(
                    category = category,
                    budget = budgetsByCategory[category.id],
                    spentMinor = spendByCategory[category.id] ?: 0,
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setBudget(categoryId: Long, monthlyLimitMinor: Long) {
        viewModelScope.launch {
            val existing = budgetRepository.getForCategory(categoryId)
            budgetRepository.upsert(
                (existing ?: BudgetEntity(categoryId = categoryId, monthlyLimitMinor = monthlyLimitMinor))
                    .copy(monthlyLimitMinor = monthlyLimitMinor),
            )
        }
    }

    fun removeBudget(categoryId: Long) {
        viewModelScope.launch {
            budgetRepository.getForCategory(categoryId)?.let { budgetRepository.delete(it.id) }
        }
    }
}