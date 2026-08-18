package com.example.expensetracker.ui.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.expensetracker.ui.common.CategoryBadge
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatMinorUnitsAsInr
import com.example.expensetracker.ui.common.parseInrInputToMinorUnits
import com.example.expensetracker.ui.dashboard.BudgetGlanceItem
import com.example.expensetracker.ui.dashboard.BudgetGlanceRow
import com.example.expensetracker.ui.dashboard.DashboardPalette
import com.example.expensetracker.ui.dashboard.budgetStatus

@Composable
fun BudgetsScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel {
        BudgetsViewModel(container.budgetRepository, container.categoryRepository, container.transactionRepository)
    }
    val rows by viewModel.rows.collectAsState()
    var editing by remember { mutableStateOf<BudgetRow?>(null) }

    val needsAttention = rows
        .filter { it.budget != null }
        .sortedByDescending { it.spentMinor.toFloat() / it.budget!!.monthlyLimitMinor.coerceAtLeast(1).toFloat() }
    val noBudgetSet = rows
        .filter { it.budget == null }
        .sortedByDescending { it.spentMinor }

    Scaffold(containerColor = DashboardPalette.PagePlane) { padding ->
        if (rows.isEmpty()) {
            Text(
                "Add a category first to set a budget.",
                color = DashboardPalette.TextMuted,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (needsAttention.isNotEmpty()) {
                    item { SectionHeader("Needs attention") }
                    items(needsAttention, key = { "attn-${it.category.id}" }) { row ->
                        BudgetGlanceItem(
                            row = BudgetGlanceRow(
                                category = row.category,
                                spentMinor = row.spentMinor,
                                limitMinor = row.budget!!.monthlyLimitMinor,
                                status = budgetStatus(row.spentMinor, row.budget.monthlyLimitMinor),
                            ),
                            modifier = Modifier.clickable { editing = row },
                        )
                    }
                }
                if (noBudgetSet.isNotEmpty()) {
                    item { SectionHeader("No budget set", modifier = Modifier.padding(top = if (needsAttention.isNotEmpty()) 12.dp else 0.dp)) }
                    items(noBudgetSet, key = { "none-${it.category.id}" }) { row ->
                        NoBudgetRow(row = row, onClick = { editing = row })
                    }
                }
            }
        }
    }

    editing?.let { row ->
        EditBudgetDialog(
            row = row,
            onDismiss = { editing = null },
            onSave = { minor ->
                viewModel.setBudget(row.category.id, minor)
                editing = null
            },
            onRemove = {
                viewModel.removeBudget(row.category.id)
                editing = null
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = DashboardPalette.TextPrimary,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

/** De-emphasized: no meter, muted text — the point is to surface the gap, not to alarm. */
@Composable
private fun NoBudgetRow(row: BudgetRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashboardPalette.Surface1, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryBadge(row.category, size = 20.dp)
            Text(row.category.name, style = MaterialTheme.typography.bodyMedium, color = DashboardPalette.TextSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(formatMinorUnitsAsInr(row.spentMinor), style = MaterialTheme.typography.bodyMedium, color = DashboardPalette.TextMuted)
            Text(
                "Set →",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EditBudgetDialog(row: BudgetRow, onDismiss: () -> Unit, onSave: (Long) -> Unit, onRemove: () -> Unit) {
    var text by remember {
        mutableStateOf(row.budget?.monthlyLimitMinor?.let { (it / 100.0).toString() } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly budget for ${row.category.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Limit (₹)") },
                    placeholder = { Text("e.g. 5000") },
                )
                if (row.budget != null) {
                    TextButton(onClick = onRemove) {
                        Text("Remove budget", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { parseInrInputToMinorUnits(text)?.let(onSave) }) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}
