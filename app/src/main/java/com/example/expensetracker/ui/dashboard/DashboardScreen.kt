@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.expensetracker.ui.common.AddTransactionDialog
import com.example.expensetracker.ui.common.CategoryBadge
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatDateRange
import com.example.expensetracker.ui.common.formatMinorUnitsAsInr
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

private enum class SpendByCategoryView { CHART, TABLE }

@Composable
fun DashboardScreen(
    onNavigateToReview: () -> Unit,
    onNavigateToBudgets: () -> Unit,
    onNavigateToTransactions: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = appViewModel {
        DashboardViewModel(
            container.transactionRepository,
            container.categoryRepository,
            container.budgetRepository,
            container.smsRepository,
        )
    }
    val state by viewModel.uiState.collectAsState()
    val selectedRange by viewModel.selectedRange.collectAsState()
    var showPresetSheet by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var spendView by remember { mutableStateOf(SpendByCategoryView.CHART) }
    val categories by viewModel.categoriesState.collectAsState()

    Scaffold(
        containerColor = DashboardPalette.PagePlane,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DatePagerRow(
                    label = periodLabel(state.datePreset, state.rangeStart, state.rangeEnd),
                    onPrev = { viewModel.stepPeriod(forward = false) },
                    onNext = { viewModel.stepPeriod(forward = true) },
                    onLabelClick = { showPresetSheet = true },
                )
            }

            item {
                SavingsHero(
                    savedMinor = state.savedMinor,
                    deltaPercent = state.savingsDeltaPercent,
                    savedPercentOfIncome = state.savedPercentOfIncome,
                    incomeMinor = state.incomeMinor,
                    expenseMinor = state.expenseMinor,
                    periodLabel = periodNoun(state.datePreset),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        label = "Income",
                        amountMinor = state.incomeMinor,
                        dotColor = DashboardPalette.StatusGood,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Expense",
                        amountMinor = state.expenseMinor,
                        dotColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Transfers are excluded from the totals above, so state what happened to that money
            // rather than letting it silently disappear from the picture.
            if (state.transferMinor > 0) {
                item { TransferChip(amountMinor = state.transferMinor) }
            }

            if (state.reviewCount > 0) {
                item { ReviewBanner(count = state.reviewCount, onClick = onNavigateToReview) }
            }

            if (state.budgetGlance.isNotEmpty()) {
                item {
                    BudgetGlanceSection(rows = state.budgetGlance, onSeeAll = onNavigateToBudgets)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Spend by category",
                        style = MaterialTheme.typography.titleMedium,
                        color = DashboardPalette.TextPrimary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    SingleChoiceSegmentedButtonRow {
                        SpendByCategoryView.entries.forEachIndexed { index, view ->
                            SegmentedButton(
                                selected = spendView == view,
                                onClick = { spendView = view },
                                shape = SegmentedButtonDefaults.itemShape(index, SpendByCategoryView.entries.size),
                            ) { Text(if (view == SpendByCategoryView.CHART) "Chart" else "Table") }
                        }
                    }
                }
            }

            if (state.spendByCategory.isEmpty()) {
                item { Text("No expenses recorded for this range yet.", color = DashboardPalette.TextMuted) }
            } else if (spendView == SpendByCategoryView.CHART) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CategoryPieChart(
                            rows = state.spendByCategory,
                            totalMinor = state.spendByCategory.sumOf { it.totalMinor },
                            modifier = Modifier.size(180.dp),
                        )
                    }
                }
                items(state.spendByCategory) { row ->
                    CategoryLegendRow(
                        row = row,
                        onCategorize = onNavigateToTransactions,
                    )
                }
            } else {
                items(state.spendByCategory) { row ->
                    CategoryDeltaRow(row = row, deltaPercent = state.categoryDeltaPercent[row.category?.id])
                }
            }
        }
    }

    if (showPresetSheet) {
        DatePresetSheet(
            selected = state.datePreset,
            onSelect = { preset ->
                viewModel.selectPreset(preset)
                showPresetSheet = false
            },
            onCustomRangeRequested = {
                showPresetSheet = false
                showRangePicker = true
            },
            onDismiss = { showPresetSheet = false },
        )
    }

    if (showRangePicker) {
        DateRangePickerDialog(
            initialStart = selectedRange.first,
            initialEnd = selectedRange.second,
            onDismiss = { showRangePicker = false },
            onConfirm = { start, end ->
                viewModel.selectCustomRange(start, end)
                showRangePicker = false
            },
        )
    }

    if (showAddDialog) {
        AddTransactionDialog(
            categories = categories,
            onDismiss = { showAddDialog = false },
            onSave = { amountMinor, direction, merchant, accountLabel, categoryId, note ->
                viewModel.addManualTransaction(
                    amountMinor = amountMinor,
                    direction = direction,
                    merchant = merchant,
                    accountLabel = accountLabel,
                    categoryId = categoryId,
                    note = note,
                )
                showAddDialog = false
            },
        )
    }
}

private fun periodNoun(preset: DatePreset): String = when (preset) {
    DatePreset.TODAY -> "day"
    DatePreset.LAST_7_DAYS, DatePreset.LAST_30_DAYS, DatePreset.CUSTOM -> "period"
    DatePreset.THIS_MONTH, DatePreset.LAST_MONTH -> "month"
    DatePreset.THIS_YEAR -> "year"
}

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun periodLabel(preset: DatePreset, start: LocalDate, end: LocalDate): String = when {
    preset == DatePreset.THIS_MONTH || preset == DatePreset.LAST_MONTH ->
        "${MONTH_NAMES[start.monthNumber - 1]} ${start.year}"
    preset == DatePreset.THIS_YEAR -> "${start.year}"
    preset == DatePreset.TODAY -> formatDateRange(start, end)
    else -> formatDateRange(start, end)
}

@Composable
private fun DateRangePickerDialog(
    initialStart: LocalDate,
    initialEnd: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val utc = TimeZone.UTC
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart.atStartOfDayEpochMillis(),
        initialSelectedEndDateMillis = initialEnd.atStartOfDayEpochMillis(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            DateRangePicker(state = pickerState, modifier = Modifier.height(480.dp))
        },
        confirmButton = {
            Button(
                onClick = {
                    val startMillis = pickerState.selectedStartDateMillis
                    val endMillis = pickerState.selectedEndDateMillis ?: startMillis
                    if (startMillis != null && endMillis != null) {
                        val start = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(utc).date
                        val end = Instant.fromEpochMilliseconds(endMillis).toLocalDateTime(utc).date
                        onConfirm(start, end)
                    }
                },
            ) { Text("Apply") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun LocalDate.atStartOfDayEpochMillis(): Long =
    this.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

private fun formatPercentage(percentage: Float): String = "%.1f%%".format(percentage)

@Composable
private fun CategoryPieChart(rows: List<CategorySpendRow>, totalMinor: Long, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(180.dp)) {
            val strokeWidth = size.minDimension * 0.22f
            var startAngle = -90f
            rows.forEach { row ->
                val sweep = if (totalMinor == 0L) 0f else (row.totalMinor.toFloat() / totalMinor.toFloat()) * 360f
                drawArc(
                    color = Color(row.category?.colour ?: 0xFF9E9E9EL),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatMinorUnitsAsInr(totalMinor),
                style = MaterialTheme.typography.titleMedium,
                color = DashboardPalette.TextPrimary,
            )
            Text(
                text = "Total spend",
                style = MaterialTheme.typography.labelSmall,
                color = DashboardPalette.TextMuted,
            )
        }
    }
}

@Composable
private fun CategoryLegendRow(row: CategorySpendRow, onCategorize: () -> Unit) {
    val isUnassigned = row.category == null
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryBadge(row.category, size = 22.dp)
            Text(text = row.category?.name ?: "Unassigned", style = MaterialTheme.typography.bodyMedium, color = DashboardPalette.TextPrimary)
            if (isUnassigned) {
                Text(
                    "Categorize →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onCategorize),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatPercentage(row.percentage),
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardPalette.TextMuted,
            )
            Text(text = formatMinorUnitsAsInr(row.totalMinor), style = MaterialTheme.typography.bodyMedium, color = DashboardPalette.TextPrimary)
        }
    }
}
