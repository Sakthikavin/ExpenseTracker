@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.expensetracker.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.ui.common.CategoryBadge
import com.example.expensetracker.ui.common.CategoryIcons
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel

private val PRESET_COLOURS: List<Long> = listOf(
    0xFFE57373L, 0xFFFFB74DL, 0xFFFFF176L, 0xFF81C784L, 0xFF64B5F6L, 0xFFBA68C8L, 0xFF9E9E9EL,
)

@Composable
fun CategoriesScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel { CategoriesViewModel(container.categoryRepository) }
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CategoryEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add category")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn {
                items(categories, key = { it.id }) { category ->
                    ListItem(
                        leadingContent = { CategoryBadge(category, size = 36.dp) },
                        headlineContent = { Text(category.name) },
                        supportingContent = { Text(if (category.isIncome) "Income" else "Expense") },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = category }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${category.name}\"?") },
            text = { Text("Transactions in this category will become Unassigned. This can't be undone.") },
            confirmButton = {
                Button(onClick = { viewModel.delete(category.id); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { Button(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    if (showAddDialog) {
        AddCategoryDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, icon, colour, isIncome ->
                viewModel.addCategory(name, icon, colour, isIncome)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, icon: String, colour: Long, isIncome: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var isIncome by remember { mutableStateOf(false) }
    var colour by remember { mutableStateOf(PRESET_COLOURS.first()) }
    var icon by remember { mutableStateOf(CategoryIcons.catalog.keys.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Travel, Gifts") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Income category")
                    Switch(checked = isIncome, onCheckedChange = { isIncome = it }, modifier = Modifier.padding(start = 8.dp))
                }
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESET_COLOURS.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(swatch), CircleShape)
                                .clickable { colour = swatch },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (swatch == colour) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White)
                            }
                        }
                    }
                }
                Text("Icon", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CategoryIcons.catalog.forEach { (key, vector) ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (key == icon) Color(colour) else Color(0xFFE0E0E0),
                                    CircleShape,
                                )
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = vector,
                                contentDescription = key,
                                tint = if (key == icon) Color.White else Color.Black,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onSave(name, icon, colour, isIncome) }) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}