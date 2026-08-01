package com.example.expensetracker.ui.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.expensetracker.ui.common.CategoryBadge
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatMinorUnitsAsInr
import com.example.expensetracker.ui.common.parseInrInputToMinorUnits

@Composable
fun BudgetsScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel {
        BudgetsViewModel(container.budgetRepository, container.categoryRepository, container.transactionRepository)
    }
    val rows by viewModel.rows.collectAsState()
    var editing by remember { mutableStateOf<BudgetRow?>(null) }

    if (rows.isEmpty()) {
        Text("Add a category first to set a budget.", modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(rows, key = { it.category.id }) { row ->
                BudgetRowItem(row = row, onClick = { editing = row })
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
        )
    }
}

@Composable
private fun BudgetRowItem(row: BudgetRow, onClick: () -> Unit) {
    val limitMinor = row.budget?.monthlyLimitMinor
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryBadge(row.category, size = 22.dp)
                Text(row.category.name, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = if (limitMinor != null) {
                    "${formatMinorUnitsAsInr(row.spentMinor)} / ${formatMinorUnitsAsInr(limitMinor)}"
                } else {
                    "${formatMinorUnitsAsInr(row.spentMinor)} / no budget set"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (limitMinor != null && limitMinor > 0) {
            val fraction = (row.spentMinor.toFloat() / limitMinor.toFloat()).coerceIn(0f, 1f)
            val overBudget = row.spentMinor >= limitMinor
            val nearLimit = row.spentMinor >= (limitMinor * 0.8).toLong()
            val barColor = when {
                overBudget -> MaterialTheme.colorScheme.error
                nearLimit -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
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
                        .fillMaxWidth(fraction)
                        .height(8.dp)
                        .background(barColor, RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

@Composable
private fun EditBudgetDialog(row: BudgetRow, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var text by remember {
        mutableStateOf(row.budget?.monthlyLimitMinor?.let { (it / 100.0).toString() } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monthly budget for ${row.category.name}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Limit (₹)") },
                placeholder = { Text("e.g. 5000") },
            )
        },
        confirmButton = {
            Button(onClick = { parseInrInputToMinorUnits(text)?.let(onSave) }) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}