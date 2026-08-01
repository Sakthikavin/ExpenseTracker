@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.ui.common.CategoryBadge
import com.example.expensetracker.ui.common.CategoryLabel
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.parseInrInputToMinorUnits

@Composable
fun ReviewQueueScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel { ReviewQueueViewModel(container.smsRepository, container.categoryRepository) }
    val needsReview by viewModel.needsReview.collectAsState()
    val categories by viewModel.categories.collectAsState()
    var selected by remember { mutableStateOf<RawSmsEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (needsReview.isEmpty()) {
            Text("Nothing needs review right now.", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn {
                items(needsReview, key = { it.id }) { rawSms ->
                    Card(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(rawSms.sender, style = MaterialTheme.typography.labelMedium)
                            Text(rawSms.body, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { selected = rawSms }, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Confirm")
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { rawSms ->
        ConfirmReviewDialog(
            rawSms = rawSms,
            categories = categories,
            onDismiss = { selected = null },
            onConfirm = { amountMinor, direction, merchant, categoryId, accountLabel ->
                viewModel.confirm(rawSms, amountMinor, direction, merchant, categoryId, accountLabel)
                selected = null
            },
        )
    }
}

@Composable
private fun ConfirmReviewDialog(
    rawSms: RawSmsEntity,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onConfirm: (amountMinor: Long, direction: Direction, merchant: String, categoryId: Long?, accountLabel: String) -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(Direction.DEBIT) }
    var merchant by remember { mutableStateOf("") }
    var accountLabel by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rawSms.body, style = MaterialTheme.typography.bodySmall)
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
                    label = { Text("Merchant") },
                    placeholder = { Text("e.g. Swiggy, Amazon") },
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
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amountMinor = parseInrInputToMinorUnits(amountText) ?: return@Button
                    onConfirm(amountMinor, direction, merchant, selectedCategory?.id, accountLabel)
                },
            ) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}