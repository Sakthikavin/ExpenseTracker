package com.example.expensetracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.dao.CategorySpend
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.LocalIds
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.data.local.entity.TransactionSource
import com.example.expensetracker.data.repository.BudgetRepository
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.MerchantCategoryRuleRepository
import com.example.expensetracker.data.repository.SmsRepository
import com.example.expensetracker.data.repository.TransactionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

data class CategorySpendRow(val category: CategoryEntity?, val totalMinor: Long, val percentage: Float)

data class BudgetGlanceRow(
    val category: CategoryEntity,
    val spentMinor: Long,
    val limitMinor: Long,
    val status: BudgetStatus,
)

enum class DatePreset { TODAY, LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH, LAST_MONTH, THIS_YEAR, CUSTOM }

data class DashboardUiState(
    val rangeStart: LocalDate = defaultThisMonthRange().first,
    val rangeEnd: LocalDate = defaultThisMonthRange().second,
    val datePreset: DatePreset = DatePreset.THIS_MONTH,
    val incomeMinor: Long = 0,
    val expenseMinor: Long = 0,
    /**
     * Money moved between the user's own accounts. Excluded from [incomeMinor] and [expenseMinor],
     * but shown rather than hidden — money that vanishes from a total with no explanation is worse
     * than money counted wrongly.
     */
    val savedMinor: Long = 0,
    val savedPercentOfIncome: Float = 0f,
    /** Null when the previous period had no income to compare against. */
    val savingsDeltaPercent: Float? = null,
    val spendByCategory: List<CategorySpendRow> = emptyList(),
    /** Keyed by category id, or null for Unassigned. Null value = no previous-period spend to compare. */
    val categoryDeltaPercent: Map<Long?, Float?> = emptyMap(),
    val reviewCount: Int = 0,
    /** Up to 3 rows, most urgent first (over budget, then near limit, then on track). */
    val budgetGlance: List<BudgetGlanceRow> = emptyList(),
    /** True when the range on screen is the one pinned as the launch default. */
    val isDefaultRange: Boolean = false,
    /** True when *some* range is pinned, which is what distinguishes "set" from "replace". */
    val hasDefaultRange: Boolean = false,
    /** Set only while a restored custom default sits wholly in the past; see [StaleDefaultWarning]. */
    val staleDefault: StaleDefaultWarning? = null,
)

/**
 * A pinned custom default whose end date has already passed. Shown once per launch rather than
 * silently applied: a fixed range the user pinned weeks ago looks like an app that lost their
 * transactions, so the dashboard says why the period is empty and offers a way out.
 */
data class StaleDefaultWarning(val start: LocalDate, val end: LocalDate, val daysAgo: Int)

private fun defaultThisMonthRange(): Pair<LocalDate, LocalDate> {
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(zone).date
    val start = LocalDate(today.year, today.month, 1)
    val end = start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
    return start to end
}

private fun isWholeCalendarMonth(start: LocalDate, end: LocalDate): Boolean {
    if (start.dayOfMonth != 1) return false
    val expectedEnd = LocalDate(start.year, start.month, 1).plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
    return end == expectedEnd
}

/** For month-shaped ranges, the calendar-previous month; otherwise the same span shifted earlier. */
private fun previousPeriodRange(start: LocalDate, end: LocalDate): Pair<LocalDate, LocalDate> {
    if (isWholeCalendarMonth(start, end)) {
        val prevStart = start.minus(DatePeriod(months = 1))
        val prevEnd = LocalDate(prevStart.year, prevStart.month, 1).plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        return prevStart to prevEnd
    }
    val shift = DatePeriod(days = start.daysUntil(end) + 1)
    return start.minus(shift) to end.minus(shift)
}

/**
 * Scales a monthly budget limit to [start]–[end]. Whole-calendar-month ranges keep the limit
 * exactly as configured; anything else — a day, a week, a year — is scaled by day count against a
 * 30-day average month, so "on track" actually means something for a range shorter than a month.
 */
private fun proratedLimit(monthlyLimitMinor: Long, start: LocalDate, end: LocalDate): Long {
    if (isWholeCalendarMonth(start, end)) return monthlyLimitMinor
    val rangeDays = start.daysUntil(end) + 1
    return monthlyLimitMinor * rangeDays / 30
}

private fun presetRange(preset: DatePreset, today: LocalDate): Pair<LocalDate, LocalDate> = when (preset) {
    DatePreset.TODAY -> today to today
    DatePreset.LAST_7_DAYS -> today.minus(DatePeriod(days = 6)) to today
    DatePreset.LAST_30_DAYS -> today.minus(DatePeriod(days = 29)) to today
    DatePreset.THIS_MONTH -> {
        val start = LocalDate(today.year, today.month, 1)
        start to start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
    }
    DatePreset.LAST_MONTH -> {
        val thisMonthStart = LocalDate(today.year, today.month, 1)
        val start = thisMonthStart.minus(DatePeriod(months = 1))
        start to thisMonthStart.minus(DatePeriod(days = 1))
    }
    DatePreset.THIS_YEAR -> LocalDate(today.year, 1, 1) to today
    // Never actually read: selectPreset() rejects CUSTOM, selectCustomRange() sets the range directly.
    DatePreset.CUSTOM -> today to today
}

/**
 * Resolves a pinned default into concrete dates: presets against [today], a custom range from its
 * stored literal dates. Falls back to the current month if a CUSTOM pin somehow lost its dates.
 */
private fun DefaultDateRange.toRange(today: LocalDate): Pair<LocalDate, LocalDate> =
    if (preset == DatePreset.CUSTOM) {
        val start = customStart
        val end = customEnd
        if (start != null && end != null) start to end else defaultThisMonthRange()
    } else {
        presetRange(preset, today)
    }

private data class DefaultRangeState(val hasDefault: Boolean, val isCurrentRangeDefault: Boolean)

private data class PreviousPeriodTotals(
    val incomeMinor: Long,
    val expenseMinor: Long,
    val spendByCategory: List<CategorySpend>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
    budgetRepository: BudgetRepository,
    smsRepository: SmsRepository,
    private val merchantCategoryRuleRepository: MerchantCategoryRuleRepository? = null,
    private val dateRangePreferences: DateRangePreferences? = null,
) : ViewModel() {

    private val zone = TimeZone.currentSystemDefault()
    private fun today(): LocalDate = Clock.System.now().toLocalDateTime(zone).date

    /** What the user pinned, or null if they never did. Kept in sync by [setCurrentRangeAsDefault]. */
    private val _defaultRange = MutableStateFlow(dateRangePreferences?.load())

    private val restoredDefault: DefaultDateRange? = _defaultRange.value

    private val _selectedRange = MutableStateFlow(
        restoredDefault?.toRange(today()) ?: defaultThisMonthRange(),
    )
    val selectedRange: StateFlow<Pair<LocalDate, LocalDate>> = _selectedRange

    private val _selectedPreset = MutableStateFlow(restoredDefault?.preset ?: DatePreset.THIS_MONTH)
    val selectedPreset: StateFlow<DatePreset> = _selectedPreset

    /**
     * Only a restored *custom* default can be stale — every other preset is recomputed against
     * today, so it is current by construction.
     */
    private val _staleDefault = MutableStateFlow(
        restoredDefault
            ?.takeIf { it.preset == DatePreset.CUSTOM }
            ?.let { pinned ->
                val end = pinned.customEnd ?: return@let null
                val daysAgo = end.daysUntil(today())
                if (daysAgo > 0) StaleDefaultWarning(pinned.customStart ?: end, end, daysAgo) else null
            },
    )

    fun selectPreset(preset: DatePreset) {
        require(preset != DatePreset.CUSTOM) { "Use selectCustomRange for custom ranges" }
        _selectedPreset.value = preset
        _selectedRange.value = presetRange(preset, today())
        _staleDefault.value = null
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        _selectedPreset.value = DatePreset.CUSTOM
        _selectedRange.value = if (start <= end) start to end else end to start
        _staleDefault.value = null
    }

    /**
     * Pins whatever is on screen as the launch default. Presets are pinned by name so they stay
     * relative to today; a custom range is pinned with its literal dates.
     */
    fun setCurrentRangeAsDefault() {
        val prefs = dateRangePreferences ?: return
        val preset = _selectedPreset.value
        val (start, end) = _selectedRange.value
        prefs.save(preset, start, end)
        _defaultRange.value = DefaultDateRange(
            preset = preset,
            customStart = start.takeIf { preset == DatePreset.CUSTOM },
            customEnd = end.takeIf { preset == DatePreset.CUSTOM },
        )
    }

    /** Unpins the default; the dashboard goes back to opening on the current calendar month. */
    fun clearDefaultRange() {
        dateRangePreferences?.clear()
        _defaultRange.value = null
        _staleDefault.value = null
    }

    /** Leaves the stale range on screen — the user chose to look at it anyway. */
    fun dismissStaleDefaultWarning() {
        _staleDefault.value = null
    }

    /** Chevron stepping: shifts by a calendar month for month-shaped ranges, else by the range's own length. */
    fun stepPeriod(forward: Boolean) {
        val (start, end) = _selectedRange.value
        val (newStart, newEnd) = if (isWholeCalendarMonth(start, end)) {
            val shiftedStart = if (forward) start.plus(DatePeriod(months = 1)) else start.minus(DatePeriod(months = 1))
            shiftedStart to shiftedStart.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        } else {
            val shift = DatePeriod(days = start.daysUntil(end) + 1)
            val shiftedStart = if (forward) start.plus(shift) else start.minus(shift)
            val shiftedEnd = if (forward) end.plus(shift) else end.minus(shift)
            shiftedStart to shiftedEnd
        }
        _selectedPreset.value = DatePreset.CUSTOM
        _selectedRange.value = newStart to newEnd
        _staleDefault.value = null
    }

    private fun LocalDate.startInstant(): Instant = atStartOfDayIn(zone)
    private fun LocalDate.endInstant(): Instant = atTime(23, 59, 59).toInstant(zone)

    private val income = _selectedRange.flatMapLatest { (start, end) ->
        transactionRepository.observeTotalByDirection(start.startInstant(), end.endInstant(), Direction.CREDIT)
    }

    private val expense = _selectedRange.flatMapLatest { (start, end) ->
        transactionRepository.observeTotalByDirection(start.startInstant(), end.endInstant(), Direction.DEBIT)
    }

    private val spendByCategory = _selectedRange.flatMapLatest { (start, end) ->
        transactionRepository.observeSpendByCategory(start.startInstant(), end.endInstant())
    }

    private val categories = categoryRepository.observeAll()

    val categoriesState: StateFlow<List<CategoryEntity>> =
        categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val previousPeriod = _selectedRange.flatMapLatest { (start, end) ->
        val (prevStart, prevEnd) = previousPeriodRange(start, end)
        combine(
            transactionRepository.observeTotalByDirection(prevStart.startInstant(), prevEnd.endInstant(), Direction.CREDIT),
            transactionRepository.observeTotalByDirection(prevStart.startInstant(), prevEnd.endInstant(), Direction.DEBIT),
            transactionRepository.observeSpendByCategory(prevStart.startInstant(), prevEnd.endInstant()),
        ) { prevIncome, prevExpense, prevSpend -> PreviousPeriodTotals(prevIncome, prevExpense, prevSpend) }
    }

    // Tracks whatever range is selected on the dashboard — not fixed to the calendar month —
    // so switching periods recalculates spend against each budget's limit the same way income
    // and expense do. The limit itself is prorated to that range too: comparing one day's spend
    // against a full monthly limit would read as "on track" almost no matter what.
    private val budgetGlance = _selectedRange.flatMapLatest { (start, end) ->
        combine(
            budgetRepository.observeAll(),
            categories,
            transactionRepository.observeSpendByCategory(start.startInstant(), end.endInstant()),
        ) { budgets, categoryList, spend ->
            val categoriesById = categoryList.associateBy { it.id }
            val spendByCategoryId = spend.associate { it.categoryId to it.totalMinor }
            budgets
                .mapNotNull { budget ->
                    val category = categoriesById[budget.categoryId] ?: return@mapNotNull null
                    val spentMinor = spendByCategoryId[budget.categoryId] ?: 0
                    val prorated = proratedLimit(budget.monthlyLimitMinor, start, end)
                    BudgetGlanceRow(
                        category = category,
                        spentMinor = spentMinor,
                        limitMinor = prorated,
                        status = budgetStatus(spentMinor, prorated),
                    )
                }
                .sortedWith(
                    compareByDescending<BudgetGlanceRow> { it.status.ordinal }
                        .thenByDescending { it.spentMinor.toFloat() / it.limitMinor.coerceAtLeast(1).toFloat() },
                )
                .take(3)
        }
    }

    private val reviewCount = smsRepository.observeNeedsReview().map { it.size }

    /**
     * Whether the range on screen is the pinned one. A pinned preset matches by name, so stepping
     * a month forward (which drops to CUSTOM) correctly reads as "not the default".
     */
    private val defaultRangeState = combine(_defaultRange, _selectedPreset, _selectedRange) { pinned, preset, range ->
        val isCurrent = when {
            pinned == null -> false
            pinned.preset != preset -> false
            preset != DatePreset.CUSTOM -> true
            else -> pinned.customStart == range.first && pinned.customEnd == range.second
        }
        DefaultRangeState(hasDefault = pinned != null, isCurrentRangeDefault = isCurrent)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        _selectedRange,
        income,
        expense,
        spendByCategory,
        categories,
    ) { range, incomeTotal, expenseTotal, spend, categoryList ->
        val categoriesById = categoryList.associateBy { it.id }
        val totalSpend = spend.sumOf { it.totalMinor }.coerceAtLeast(1)
        val savedMinor = incomeTotal - expenseTotal
        DashboardUiState(
            rangeStart = range.first,
            rangeEnd = range.second,
            incomeMinor = incomeTotal,
            expenseMinor = expenseTotal,
            savedMinor = savedMinor,
            savedPercentOfIncome = if (incomeTotal > 0) savedMinor.toFloat() / incomeTotal.toFloat() * 100f else 0f,
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
        .combine(previousPeriod) { state, prev ->
            val prevSaved = prev.incomeMinor - prev.expenseMinor
            val savingsDeltaPercent = if (prevSaved != 0L) {
                (state.savedMinor - prevSaved).toFloat() / abs(prevSaved).toFloat() * 100f
            } else {
                null
            }
            val prevSpendByCategoryId = prev.spendByCategory.associate { it.categoryId to it.totalMinor }
            val categoryDeltaPercent = state.spendByCategory.associate { row ->
                val prevAmount = prevSpendByCategoryId[row.category?.id]
                val delta = if (prevAmount != null && prevAmount > 0) {
                    (row.totalMinor - prevAmount).toFloat() / prevAmount.toFloat() * 100f
                } else {
                    null
                }
                row.category?.id to delta
            }
            state.copy(savingsDeltaPercent = savingsDeltaPercent, categoryDeltaPercent = categoryDeltaPercent)
        }
        .combine(budgetGlance) { state, glance -> state.copy(budgetGlance = glance) }
        .combine(reviewCount) { state, count -> state.copy(reviewCount = count) }
        .combine(_selectedPreset) { state, preset -> state.copy(datePreset = preset) }
        .combine(defaultRangeState) { state, default ->
            state.copy(
                isDefaultRange = default.isCurrentRangeDefault,
                hasDefaultRange = default.hasDefault,
            )
        }
        .combine(_staleDefault) { state, stale -> state.copy(staleDefault = stale) }
        // The seed carries the restored range, not the data-class defaults: otherwise the first
        // frame after launch shows "this month" in the pager before the flows emit, and a user
        // with a pinned range watches the header flip under them.
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DashboardUiState(
                rangeStart = _selectedRange.value.first,
                rangeEnd = _selectedRange.value.second,
                datePreset = _selectedPreset.value,
                staleDefault = _staleDefault.value,
            ),
        )

    fun addManualTransaction(
        amountMinor: Long,
        direction: Direction,
        merchant: String,
        accountLabel: String,
        categoryId: Long?,
        note: String,
        occurredAt: Instant = Clock.System.now(),
    ) {
        viewModelScope.launch {
            // Same lookup TransactionsViewModel.addManualTransaction applies — an explicit pick is
            // never overridden, but Unassigned still gets the merchant's learned rule (Addendum 4).
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
                    tags = emptyList(),
                    source = TransactionSource.MANUAL,
                ),
            )
        }
    }
}
