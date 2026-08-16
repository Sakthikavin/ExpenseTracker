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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.OwnAccountEntity
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.ui.common.AddTransactionDialog
import com.example.expensetracker.ui.common.CategoryLabel
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatDate
import com.example.expensetracker.ui.common.formatMinorUnitsAsInr

@Composable
fun TransactionsScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel {
        TransactionsViewModel(
            container.transactionRepository,
            container.categoryRepository,
            container.transferRepository,
        )
    }
    val listItems by viewModel.listItems.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val ownAccounts by viewModel.ownAccounts.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var pendingTransferFor by remember { mutableStateOf<TransactionEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (listItems.isEmpty()) {
                Text("No transactions yet.", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn {
                    items(listItems, key = { it.rowKey }) { item ->
                        when (item) {
                            is TransactionListItem.Single -> TransactionRow(
                                transaction = item.transaction,
                                category = categories.firstOrNull { it.id == item.transaction.categoryId },
                                categories = categories,
                                onDelete = { pendingDelete = item.transaction },
                                onCategorySelected = { viewModel.updateCategory(item.transaction, it) },
                                onMarkTransfer = { pendingTransferFor = item.transaction },
                            )

                            is TransactionListItem.Transfer -> TransferRow(
                                item = item,
                                ownAccounts = ownAccounts,
                                onUnlink = { viewModel.unlinkTransfer(item.groupId) },
                            )
                        }
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

    pendingTransferFor?.let { transaction ->
        var candidates by remember(transaction.id) { mutableStateOf(emptyList<TransactionEntity>()) }
        LaunchedEffect(transaction.id) { candidates = viewModel.transferCandidates(transaction) }

        MarkTransferDialog(
            transaction = transaction,
            candidates = candidates,
            onDismiss = { pendingTransferFor = null },
            onPick = { counterpart ->
                viewModel.linkTransfer(transaction, counterpart)
                pendingTransferFor = null
            },
            onNoCounterpart = {
                viewModel.markSingleLegTransfer(transaction)
                pendingTransferFor = null
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
    onMarkTransfer: () -> Unit,
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }

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
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Mark as transfer") },
                            onClick = {
                                overflowExpanded = false
                                onMarkTransfer()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                overflowExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * A transfer between the user's own accounts, shown as the single event it was rather than as a
 * debit and a credit that appear to cancel out.
 */
@Composable
private fun TransferRow(
    item: TransactionListItem.Transfer,
    ownAccounts: List<OwnAccountEntity>,
    onUnlink: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    fun nameFor(label: String): String =
        ownAccounts.firstOrNull { it.label.equals(label, ignoreCase = true) }?.displayName
            ?: label.ifBlank { "unknown" }

    val route = when {
        item.out != null && item.into != null ->
            "${nameFor(item.out.accountLabel)} → ${nameFor(item.into.accountLabel)}"
        item.out != null -> "from ${nameFor(item.out.accountLabel)}"
        else -> "to ${nameFor(item.into!!.accountLabel)}"
    }

    ListItem(
        overlineContent = { Text(formatDate(item.sortKey)) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text("  Transfer · $route")
            }
        },
        supportingContent = {
            Text(
                if (item.isComplete) "Not counted as spending" else "Not counted as spending · one leg only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row {
                Text(
                    text = formatMinorUnitsAsInr(item.amountMinor),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Not a transfer") },
                            onClick = {
                                overflowExpanded = false
                                onUnlink()
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * Picks the other leg of a transfer. Candidates are opposite-direction transactions of a similar
 * amount nearby in time, closest amount first — but the user always chooses.
 */
@Composable
private fun MarkTransferDialog(
    transaction: TransactionEntity,
    candidates: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onPick: (TransactionEntity) -> Unit,
    onNoCounterpart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark as transfer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Which transaction is the other side of this " +
                        "${formatMinorUnitsAsInr(transaction.amountMinor)} movement?",
                )
                if (candidates.isEmpty()) {
                    Text(
                        "Nothing nearby matches. You can still mark it as a transfer on its own — " +
                            "useful when only one bank sent a message.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    candidates.forEach { candidate ->
                        ListItem(
                            headlineContent = { Text(candidate.merchant.ifBlank { "(no merchant)" }) },
                            supportingContent = { Text(formatDate(candidate.occurredAt)) },
                            trailingContent = {
                                val sign = if (candidate.direction == Direction.DEBIT) "-" else "+"
                                Text("$sign${formatMinorUnitsAsInr(candidate.amountMinor)}")
                            },
                            modifier = Modifier.clickable { onPick(candidate) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNoCounterpart) { Text("No matching message") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}