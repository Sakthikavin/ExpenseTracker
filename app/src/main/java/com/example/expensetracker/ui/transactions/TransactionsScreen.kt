@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.ui.common.CategoryBadge
import com.example.expensetracker.ui.common.CategoryLabel
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatDate
import com.example.expensetracker.ui.common.formatMinorUnitsAsInr
import com.example.expensetracker.ui.common.parseInrInputToMinorUnits

@Composable
fun TransactionsScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel { TransactionsViewModel(container.transactionRepository, container.categoryRepository) }
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (transactions.isEmpty()) {
                Text("No transactions yet.", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn {
                    items(transactions, key = { it.id }) { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            category = categories.firstOrNull { it.id == transaction.categoryId },
                            categories = categories,
                            onDelete = { pendingDelete = transaction },
                            onCategorySelected = { categoryId -> viewModel.updateCategory(transaction, categoryId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { transaction ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete transaction?") },
            text = {
                Text(
                    "Delete the ${formatMinorUnitsAsInr(transaction.amountMinor)} transaction for " +
                        "${transaction.merchant.ifBlank { "(no merchant)" }}? This can't be undone.",
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.delete(transaction.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { pendingDelete = null }) { Text("Cancel") } },
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
                    tags = emptyList(),
                )
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun TransactionRow(
    transaction: TransactionEntity,
    category: CategoryEntity?,
    categories: List<CategoryEntity>,
    onDelete: () -> Unit,
    onCategorySelected: (Long?) -> Unit,
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    ListItem(
        overlineContent = { Text(formatDate(transaction.occurredAt)) },
        headlineContent = { Text(transaction.merchant.ifBlank { "(no merchant)" }) },
        supportingContent = {
            Box {
                CategoryLabel(
                    category = category,
                    badgeSize = 18.dp,
                    modifier = Modifier.clickable { categoryMenuExpanded = true },
                )
                DropdownMenu(
                    expanded = categoryMenuExpanded,
                    onDismissRequest = { categoryMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { CategoryLabel(category = null) },
                        onClick = {
                            onCategorySelected(null)
                            categoryMenuExpanded = false
                        },
                    )
                    categories.forEach { option ->
                        DropdownMenuItem(
                            text = { CategoryLabel(category = option) },
                            onClick = {
                                onCategorySelected(option.id)
                                categoryMenuExpanded = false
                            },
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row {
                val sign = if (transaction.direction == Direction.DEBIT) "-" else "+"
                Text(
                    text = "$sign${formatMinorUnitsAsInr(transaction.amountMinor)}",
                    color = if (transaction.direction == Direction.DEBIT) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        },
    )
}

@Composable
private fun AddTransactionDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (amountMinor: Long, direction: Direction, merchant: String, accountLabel: String, categoryId: Long?, note: String) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(Direction.DEBIT) }
    var merchant by remember { mutableStateOf("") }
    var accountLabel by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow {
                    Direction.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = direction == value,
                            onClick = { direction = value },
                            shape = SegmentedButtonDefaults.itemShape(index, Direction.entries.size),
                        ) { Text(value.name) }
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (₹)") },
                    placeholder = { Text("e.g. 250.00") },
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant / payee") },
                    placeholder = { Text("e.g. Swiggy, Amazon, Landlord") },
                )
                OutlinedTextField(
                    value = accountLabel,
                    onValueChange = { accountLabel = it },
                    label = { Text("Account") },
                    placeholder = { Text("e.g. HDFC Bank, Cash") },
                )
                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { categoryMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedCategory?.name ?: "Unassigned",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        leadingIcon = { CategoryBadge(selectedCategory, size = 22.dp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { CategoryLabel(category = null) },
                            onClick = {
                                selectedCategory = null
                                categoryMenuExpanded = false
                            },
                        )
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { CategoryLabel(category = category) },
                                onClick = {
                                    selectedCategory = category
                                    categoryMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    placeholder = { Text("Optional") },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amountMinor = parseInrInputToMinorUnits(amountText) ?: return@Button
                    onSave(amountMinor, direction, merchant, accountLabel, selectedCategory?.id, note)
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        },
    )
}