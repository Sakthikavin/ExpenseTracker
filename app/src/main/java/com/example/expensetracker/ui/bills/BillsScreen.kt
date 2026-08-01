@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.BillEntity
import com.example.expensetracker.data.local.entity.BillRecurrence
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatMinorUnitsAsInr
import com.example.expensetracker.ui.common.parseInrInputToMinorUnits

@Composable
fun BillsScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel { BillsViewModel(container.billRepository) }
    val bills by viewModel.bills.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BillEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add bill")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (bills.isEmpty()) {
                Text("No recurring bills yet.", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn {
                    items(bills, key = { it.id }) { bill ->
                        ListItem(
                            headlineContent = { Text(bill.name) },
                            supportingContent = { Text("Due day ${bill.dueDay} · ${bill.recurrence.name.lowercase()}") },
                            trailingContent = {
                                Row {
                                    Text(formatMinorUnitsAsInr(bill.amountMinor))
                                    IconButton(onClick = { pendingDelete = bill }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddBillDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, amountMinor, dueDay, recurrence ->
                viewModel.addBill(name, amountMinor, dueDay, recurrence)
                showAddDialog = false
            },
        )
    }

    pendingDelete?.let { bill ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${bill.name}\"?") },
            text = { Text("This recurring bill reminder will be permanently removed. This can't be undone.") },
            confirmButton = {
                Button(onClick = { viewModel.delete(bill.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddBillDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, amountMinor: Long, dueDay: Int, recurrence: BillRecurrence) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var dueDayText by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf(BillRecurrence.MONTHLY) }
    var recurrenceMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Netflix, Rent, Electricity") },
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (₹)") },
                    placeholder = { Text("e.g. 499") },
                )
                OutlinedTextField(
                    value = dueDayText,
                    onValueChange = { dueDayText = it },
                    label = { Text("Due day of month (1-31)") },
                    placeholder = { Text("e.g. 5") },
                )
                ExposedDropdownMenuBox(
                    expanded = recurrenceMenuExpanded,
                    onExpandedChange = { recurrenceMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = recurrence.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Recurrence") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = recurrenceMenuExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    DropdownMenu(
                        expanded = recurrenceMenuExpanded,
                        onDismissRequest = { recurrenceMenuExpanded = false },
                    ) {
                        BillRecurrence.entries.forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value.name) },
                                onClick = {
                                    recurrence = value
                                    recurrenceMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amountMinor = parseInrInputToMinorUnits(amountText) ?: return@Button
                    val dueDay = dueDayText.toIntOrNull()?.coerceIn(1, 31) ?: return@Button
                    onSave(name, amountMinor, dueDay, recurrence)
                },
            ) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}