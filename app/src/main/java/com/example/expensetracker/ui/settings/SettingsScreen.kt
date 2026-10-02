@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.expensetracker.export.CsvExporter
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatDateTime
import com.example.expensetracker.ui.common.parseInrInputToMinorUnits
import com.example.expensetracker.ui.theme.StatusGood
import com.example.expensetracker.ui.theme.StatusWarning
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

@Composable
fun SettingsScreen(onNavigateToReview: () -> Unit, onNavigateToSkipped: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val settingsViewModel = appViewModel {
        SettingsViewModel(container.smsRepository, container.ruleSyncCoordinator)
    }
    val importProgress by settingsViewModel.importProgress.collectAsState()
    val lastImportAt by settingsViewModel.lastImportAt.collectAsState()
    val ignoreBelowMinor by settingsViewModel.ignoreBelowMinor.collectAsState()
    val ruleUpdateState by settingsViewModel.ruleUpdateState.collectAsState()
    val skippedCount by settingsViewModel.skippedCount.collectAsState()

    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        settingsViewModel.loadLastImportAt(prefs)
        settingsViewModel.loadIgnoreBelowMinor(prefs)
        settingsViewModel.loadRuleUpdateState()
    }
    var ignoreBelowText by remember { mutableStateOf("") }
    LaunchedEffect(ignoreBelowMinor) { ignoreBelowText = "%.2f".format(ignoreBelowMinor / 100.0) }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            settingsViewModel.startImport(context.contentResolver, prefs)
        } else {
            permissionDenied = true
            val activity = context as? Activity
            permissionPermanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_SMS)
        }
    }

    fun beginImport() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            permissionDenied = false
            settingsViewModel.startImport(context.contentResolver, prefs)
        } else {
            permissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Data",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !importProgress.isRunning) { showConfirmDialog = true },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Import SMS history", fontWeight = FontWeight.SemiBold)
            Text(
                if (importProgress.isRunning) {
                    "Scanning…"
                } else {
                    "Scan your device for past bank messages and add anything not already tracked."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (lastImportAt == null) "Last run: never" else "Last run: just now",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (permissionDenied) {
            Text(
                "Permission needed to import SMS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(
                onClick = {
                    if (permissionPermanentlyDenied) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    } else {
                        permissionLauncher.launch(Manifest.permission.READ_SMS)
                    }
                },
            ) { Text(if (permissionPermanentlyDenied) "Open app settings" else "Grant permission") }
        }

        HorizontalDivider()

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

        HorizontalDivider()

        Text(
            "Noise filters",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Verification pings (\"Received! INR 1.00…\") never reach the review queue when they're " +
                "under this amount.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = ignoreBelowText,
            onValueChange = { text ->
                ignoreBelowText = text
                parseInrInputToMinorUnits(text)?.let { settingsViewModel.setIgnoreBelowMinor(prefs, it) }
            },
            label = { Text("Ignore transactions under (₹)") },
            singleLine = true,
        )

        Text(
            "One-off cleanup: the review queue has old confirmation-only messages (NPS, mutual fund " +
                "and tax-payment confirmations) that duplicate a debit already captured elsewhere. " +
                "New messages like these are filtered automatically; this clears out the backlog.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                scope.launch {
                    val count = container.smsRepository.ignoreConfirmationOnlyBacklog()
                    statusMessage = "Moved $count confirmation-only messages out of the review queue."
                }
            },
        ) { Text("Clean up confirmation-only messages") }

        // Only when there's something to see: an empty diagnostic list is a dead end, and the
        // count is the part that tells you whether the parser has been missing anything.
        if (skippedCount > 0) {
            Text(
                "$skippedCount ${if (skippedCount == 1) "message" else "messages"} mentioned an " +
                    "amount but didn't look like a bank alert, so nothing was recorded for them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onNavigateToSkipped) { Text("Messages I skipped →") }
        }

        HorizontalDivider()

        Text(
            "Rule updates",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (ruleUpdateState.version != null) {
                "Current version: v${ruleUpdateState.version}"
            } else {
                "No rules downloaded yet"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Last checked: " + (
                ruleUpdateState.lastCheckedAtMillis
                    ?.let { formatDateTime(Instant.fromEpochMilliseconds(it)) }
                    ?: "never"
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { settingsViewModel.checkForRuleUpdates() },
            enabled = !ruleUpdateState.isChecking,
        ) { Text(if (ruleUpdateState.isChecking) "Checking…" else "Check now") }
        ruleUpdateState.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        statusMessage?.let { Text(it) }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Import SMS history") },
            text = {
                Text(
                    "Scans your device's SMS inbox for past bank messages and adds any " +
                        "transactions not already tracked. Duplicates are detected automatically, " +
                        "so it's safe to run more than once. Scanning a large inbox may take a minute.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showConfirmDialog = false; beginImport() }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (importProgress.isRunning || importProgress.isComplete) {
        // rememberModalBottomSheetState only reads confirmValueChange's closure once, at first
        // creation — rememberUpdatedState is what lets that closure still see later isComplete
        // values instead of being pinned to whatever it was when the sheet first appeared.
        val isComplete by rememberUpdatedState(importProgress.isComplete)
        val sheetState = rememberModalBottomSheetState(
            confirmValueChange = { isComplete },
        )
        ImportSheet(
            progress = importProgress,
            sheetState = sheetState,
            onReview = {
                settingsViewModel.dismissSummary()
                onNavigateToReview()
            },
            onDone = { settingsViewModel.dismissSummary() },
        )
    }
}

@Composable
private fun ImportSheet(
    progress: ImportProgress,
    sheetState: SheetState,
    onReview: () -> Unit,
    onDone: () -> Unit,
) {
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDone,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress.isComplete) {
                Text("Import complete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${progress.total} messages scanned. Already-tracked messages were skipped " +
                        "automatically — nothing is duplicated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress.ranWithoutRules) {
                    Text(
                        "Couldn't load the parsing rules, so nothing could be matched — the " +
                            "messages are in your review queue and will be re-checked " +
                            "automatically the next time the rules load.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusWarning,
                    )
                }
                SummaryRow("Transactions added", progress.imported.toString())
                SummaryRow("Needs your review", progress.needsReview.toString(), StatusWarning)
                SummaryRow("Not financial (ignored)", progress.ignored.toString())
                if (progress.skipped > 0) {
                    SummaryRow("Mentioned money, not recognised", progress.skipped.toString())
                }
                if (progress.needsReview > 0) {
                    TextButton(onClick = onReview) {
                        Text("Review the ${progress.needsReview} flagged messages →")
                    }
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            } else {
                Text("Scanning your messages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Reading SMS from your device and matching them against the published rules — " +
                        "this only runs once. You can leave this open or check back later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val fraction = if (progress.total > 0) progress.scanned.toFloat() / progress.total else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${progress.scanned} of ${progress.total} messages",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveCount("Imported", progress.imported, StatusGood, Modifier.weight(1f))
                    LiveCount("Needs review", progress.needsReview, StatusWarning, Modifier.weight(1f))
                    LiveCount("Not financial", progress.ignored, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LiveCount(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
