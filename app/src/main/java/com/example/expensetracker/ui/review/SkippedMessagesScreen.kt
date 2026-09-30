package com.example.expensetracker.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatDateTime

/**
 * "Messages I skipped" — the messages that mentioned money but that the parser didn't recognise as
 * a bank alert.
 *
 * This screen exists because of how its absence failed: a Canara alert writing `Dr.` instead of
 * "debited" matched no tier and was dropped without a row, so it appeared in no list anywhere and
 * the only way to discover it was to go looking by hand. The parser's admission test is a
 * heuristic, and a heuristic needs somewhere its misses can be seen.
 */
@Composable
fun SkippedMessagesScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel { SkippedMessagesViewModel(container.smsRepository) }
    val messages by viewModel.messages.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (messages.isEmpty()) {
            Text(
                "Nothing skipped.\n\nMessages that mention an amount but don't look like a bank " +
                    "alert would be listed here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "These mentioned an amount but didn't look like a bank alert, so they " +
                            "weren't recorded. Only the most recent ${messages.size} are kept. " +
                            "Spotted a real transaction? Move it to review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(messages, key = { it.id }) { rawSms ->
                    SkippedCard(
                        modifier = Modifier.animateItem(),
                        rawSms = rawSms,
                        onMoveToReview = { viewModel.moveToReview(rawSms) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkippedCard(rawSms: RawSmsEntity, onMoveToReview: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(rawSms.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    formatDateTime(rawSms.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            MessageBody(body = rawSms.body)

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onMoveToReview) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Move to review")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(rawSms.body)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy")
                }
            }
        }
    }
}
