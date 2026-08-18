package com.example.expensetracker.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.OwnAccountEntity
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel

@Composable
fun AccountsScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel { AccountsViewModel(container.transferRepository) }
    val accounts by viewModel.accounts.collectAsState()
    var renaming by remember { mutableStateOf<OwnAccountEntity?>(null) }
    var deleting by remember { mutableStateOf<AccountRow?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Mark the accounts that are yours. When money moves between two of them, " +
                "it's recorded as a transfer instead of counting as spending or income.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )

        if (accounts.isEmpty()) {
            Text(
                "No accounts seen yet. They appear here automatically as bank messages arrive.",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }

        LazyColumn {
            items(accounts, key = { it.label }) { row ->
                ListItem(
                    headlineContent = { Text(row.displayName) },
                    supportingContent = if (row.owned?.nickname?.isNotBlank() == true) {
                        { Text(row.label) }
                    } else {
                        null
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = row.isOwned,
                                onCheckedChange = { viewModel.setOwned(row.label, it) },
                            )
                            IconButton(onClick = { deleting = row }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Remove ${row.displayName}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                row.owned?.let { owned ->
                    TextButton(
                        onClick = { renaming = owned },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text(if (owned.nickname.isBlank()) "Add a name" else "Rename") }
                }
                HorizontalDivider()
            }
        }
    }

    renaming?.let { account ->
        RenameDialog(
            account = account,
            onDismiss = { renaming = null },
            onSave = { nickname ->
                viewModel.rename(account, nickname)
                renaming = null
            },
        )
    }

    deleting?.let { row ->
        DeleteAccountDialog(
            row = row,
            onDismiss = { deleting = null },
            onConfirm = {
                viewModel.delete(row.label)
                deleting = null
            },
        )
    }
}

@Composable
private fun RenameDialog(
    account: OwnAccountEntity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var nickname by remember { mutableStateOf(account.nickname) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name ${account.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What do you call this account?")
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("e.g. HDFC Savings") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(nickname.trim()) }) { Text("Save") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteAccountDialog(
    row: AccountRow,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${row.displayName}?") },
        text = {
            Text(
                if (row.hasTransactionHistory) {
                    "This label has transactions tied to it. Removing it won't delete those " +
                        "transactions, but it will lose its name and stop being offered as yours " +
                        "until you claim it again."
                } else {
                    "This will remove it from the list. You can't undo this."
                },
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}
