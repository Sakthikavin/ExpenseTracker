@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.ui.common.CategoryLabel
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel

@Composable
fun MerchantRulesScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel {
        MerchantRulesViewModel(container.merchantCategoryRuleRepository, container.categoryRepository)
    }
    val rows by viewModel.rows.collectAsState()
    val query by viewModel.query.collectAsState()
    val categories by viewModel.categories.collectAsState()
    var reassignTarget by remember { mutableStateOf<MerchantRuleRow?>(null) }
    var renameTarget by remember { mutableStateOf<MerchantRuleRow?>(null) }
    var pendingDelete by remember { mutableStateOf<MerchantRuleRow?>(null) }

    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search merchants") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            if (rows.isEmpty()) {
                Text(
                    if (query.isBlank()) {
                        "No merchant rules yet. Categorizing a merchant will learn one automatically."
                    } else {
                        "No merchants match \"$query\"."
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(rows, key = { it.rule.id }) { row ->
                        MerchantRuleRowView(
                            row = row,
                            onClick = { reassignTarget = row },
                            onRename = { renameTarget = row },
                            onRemove = { pendingDelete = row },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    reassignTarget?.let { row ->
        CategoryPickerDialog(
            title = row.displayLabel,
            categories = categories,
            selected = row.category,
            onDismiss = { reassignTarget = null },
            onPick = { category ->
                viewModel.reassignCategory(row.rule.id, category.id)
                reassignTarget = null
            },
        )
    }

    renameTarget?.let { row ->
        RenameSheet(
            row = row,
            categories = categories,
            loadSmsContext = { viewModel.smsContextFor(row.rule.merchantKey) },
            onDismiss = { renameTarget = null },
            onSave = { displayName, categoryId ->
                viewModel.saveRename(row.rule.id, displayName, categoryId)
                renameTarget = null
            },
        )
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove this rule?") },
            text = {
                Text(
                    "\"${row.displayLabel}\" will stop auto-categorizing on arrival. Past transactions " +
                        "keep their category — this can't be undone.",
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.deleteRule(row.rule.id); pendingDelete = null }) { Text("Remove") }
            },
            dismissButton = { Button(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MerchantRuleRowView(
    row: MerchantRuleRow,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { MerchantInitialsIcon(row.displayLabel) },
        headlineContent = {
            Column {
                Text(row.displayLabel, fontWeight = FontWeight.Bold)
                if (row.rule.displayName != null) {
                    Text(
                        row.rule.merchantKey,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(row.category?.colour ?: 0xFF9E9E9EL), CircleShape),
                )
                Text(
                    row.category?.name ?: "Unassigned",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "· ${row.transactionCount} txn${if (row.transactionCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
                }
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Remove rule") },
                            onClick = { overflowExpanded = false; onRemove() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun MerchantInitialsIcon(label: String) {
    val initials = label.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "?" }
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Quick category-only reassignment from tapping a row — matching key untouched. */
@Composable
private fun CategoryPickerDialog(
    title: String,
    categories: List<CategoryEntity>,
    selected: CategoryEntity?,
    onDismiss: () -> Unit,
    onPick: (CategoryEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reassign \"$title\"") },
        text = {
            LazyColumn {
                items(categories, key = { it.id }) { category ->
                    ListItem(
                        headlineContent = { CategoryLabel(category = category) },
                        modifier = Modifier.clickable { onPick(category) },
                        trailingContent = { if (category.id == selected?.id) Text("✓") },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Addendum 5: rename and re-category share one sheet since both edit the same rule row. The raw
 * match key stays visible and read-only — renaming never touches matching logic.
 */
@Composable
private fun RenameSheet(
    row: MerchantRuleRow,
    categories: List<CategoryEntity>,
    loadSmsContext: suspend () -> String?,
    onDismiss: () -> Unit,
    onSave: (displayName: String?, categoryId: Long) -> Unit,
) {
    var displayName by remember { mutableStateOf(row.rule.displayName.orEmpty()) }
    var selectedCategory by remember { mutableStateOf(row.category) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var smsContext by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(row.rule.merchantKey) { smsContext = loadSmsContext() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Rename merchant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Matches: ${row.rule.merchantKey}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Text("Display name", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(row.rule.merchantKey) },
                singleLine = true,
            )
            Text(
                "Shown instead of the raw match text. Leave blank to keep showing \"${row.rule.merchantKey}\".",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Text("Category", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            ExposedDropdownMenuBox(
                expanded = categoryMenuExpanded,
                onExpandedChange = { categoryMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedCategory?.name ?: "Unassigned",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                    categories.forEach { category ->
                        DropdownMenuItem(
                            text = { CategoryLabel(category = category) },
                            onClick = { selectedCategory = category; categoryMenuExpanded = false },
                        )
                    }
                }
            }

            if (smsContext != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        "FROM THE SMS THAT CREATED THIS RULE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "\"$smsContext\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { selectedCategory?.let { onSave(displayName, it.id) } },
                    modifier = Modifier.weight(1f),
                    enabled = selectedCategory != null,
                ) { Text("Save") }
            }
        }
    }
}
