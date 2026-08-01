@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
fun DashboardScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel { DashboardViewModel(container.transactionRepository, container.categoryRepository) }
    val state by viewModel.uiState.collectAsState()
    val selectedRange by viewModel.selectedRange.collectAsState()
    var isCustomSelected by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    var spendView by remember { mutableStateOf(SpendByCategoryView.CHART) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !isCustomSelected,
                    onClick = {
                        isCustomSelected = false
                        viewModel.selectThisMonth()
                    },
                    label = { Text("This month") },
                )
                FilterChip(
                    selected = isCustomSelected,
                    onClick = {
                        isCustomSelected = true
                        showRangePicker = true
                    },
                    label = { Text("Custom range") },
                )
            }
        }

        item {
            Text(
                text = formatDateRange(state.rangeStart, state.rangeEnd),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TotalCard(
                    label = "Income",
                    amountMinor = state.incomeMinor,
                    modifier = Modifier.weight(1f),
                )
                TotalCard(
                    label = "Expense",
                    amountMinor = state.expenseMinor,
                    modifier = Modifier.weight(1f),
                )
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
            item { Text("No expenses recorded for this range yet.") }
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
                CategoryLegendRow(row)
            }
        } else {
            val maxSpend = state.spendByCategory.maxOf { it.totalMinor }.coerceAtLeast(1)
            items(state.spendByCategory) { row ->
                CategorySpendBar(
                    row = row,
                    fraction = row.totalMinor.toFloat() / maxSpend.toFloat(),
                )
            }
        }
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

@Composable
private fun TotalCard(label: String, amountMinor: Long, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = formatMinorUnitsAsInr(amountMinor), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

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
        Text(
            text = formatMinorUnitsAsInr(totalMinor),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun CategoryLegendRow(row: CategorySpendRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryBadge(row.category, size = 22.dp)
            Text(text = row.category?.name ?: "Unassigned", style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatPercentage(row.percentage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = formatMinorUnitsAsInr(row.totalMinor), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CategorySpendBar(row: CategorySpendRow, fraction: Float) {
    val color = Color(row.category?.colour ?: 0xFF9E9E9EL)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryBadge(row.category, size = 22.dp)
                Text(text = row.category?.name ?: "Unassigned", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = formatPercentage(row.percentage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = formatMinorUnitsAsInr(row.totalMinor), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .padding(top = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp)
                    .background(color, RoundedCornerShape(4.dp)),
            )
        }
    }
}
