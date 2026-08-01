package com.example.expensetracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.expensetracker.export.CsvExporter
import com.example.expensetracker.ui.common.LocalAppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Export all transactions to a local CSV file.")
        Button(
            onClick = {
                scope.launch {
                    val transactions = container.transactionRepository.observeAll().first()
                    val categories = container.categoryRepository.observeAll().first()
                    val file = CsvExporter.export(context, transactions, categories)
                    statusMessage = "Exported to ${file.absolutePath}"
                }
            },
        ) { Text("Export CSV") }

        statusMessage?.let { Text(it) }
    }
}