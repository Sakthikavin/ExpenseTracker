@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.data.remoterules.Redactor
import com.example.expensetracker.ui.common.CategorizePrompt
import com.example.expensetracker.ui.common.CategorizePromptBar
import com.example.expensetracker.ui.common.CategoryBadge
import com.example.expensetracker.ui.common.CategoryLabel
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.common.formatDate
import com.example.expensetracker.ui.common.parseInrInputToMinorUnits
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun ReviewQueueScreen() {
    val container = LocalAppContainer.current
    val viewModel = appViewModel {
        ReviewQueueViewModel(
            container.smsRepository,
            container.categoryRepository,
            container.merchantCategoryRuleRepository,
            container.submissionRepository,
        )
    }
    val needsReview by viewModel.needsReview.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val categorizePrompt by viewModel.categorizePrompt.collectAsState()
    val submissionMessage by viewModel.submissionMessage.collectAsState()
    var selected by remember { mutableStateOf<RawSmsEntity?>(null) }
    var submitting by remember { mutableStateOf<RawSmsEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The actual commit-after-timeout lives in the ViewModel, on a per-item timer independent of
    // this snackbar — so a second dismissal never costs the first one its undo window. This
    // snackbar is just the visible control for whichever item was dismissed most recently;
    // replacing it outright (instead of queuing) keeps feedback prompt for back-to-back swipes.
    val dismissWithUndo: (RawSmsEntity) -> Unit = { rawSms ->
        snackbarHostState.currentSnackbarData?.dismiss()
        viewModel.dismiss(rawSms)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Dismissed",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDismiss(rawSms)
            }
        }
    }

    LaunchedEffect(submissionMessage) {
        submissionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSubmissionMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (needsReview.isEmpty()) {
                EmptyReviewState(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(needsReview, key = { it.id }) { rawSms ->
                        ReviewCard(
                            modifier = Modifier.animateItem(),
                            rawSms = rawSms,
                            onConfirm = { selected = rawSms },
                            onDismiss = { dismissWithUndo(rawSms) },
                            onSubmit = { submitting = rawSms },
                        )
                    }
                }
            }

            categorizePrompt?.let { prompt ->
                val categoryId = when (prompt) {
                    is CategorizePrompt.RetroactiveApply -> prompt.categoryId
                    is CategorizePrompt.RuleUpdate -> prompt.newCategoryId
                }
                CategorizePromptBar(
                    prompt = prompt,
                    categoryName = categories.firstOrNull { it.id == categoryId }?.name ?: "Unassigned",
                    onConfirm = {
                        when (prompt) {
                            is CategorizePrompt.RetroactiveApply -> viewModel.applyRetroactively(prompt)
                            is CategorizePrompt.RuleUpdate -> viewModel.confirmRuleUpdate(prompt)
                        }
                    },
                    onDismiss = { viewModel.dismissCategorizePrompt() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }

    submitting?.let { rawSms ->
        SubmitForRuleSheet(
            rawSms = rawSms,
            onDismiss = { submitting = null },
            onSendForReview = { transactionType, note ->
                viewModel.sendForReview(rawSms, transactionType, note)
                submitting = null
            },
            onReportNoise = { reason ->
                viewModel.discardAsNoise(rawSms, reason)
                submitting = null
            },
        )
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
private fun EmptyReviewState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            "You're all caught up",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Nothing needs review right now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val CARD_SHAPE = RoundedCornerShape(20.dp)

@Composable
private fun ReviewCard(
    rawSms: RawSmsEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = { SwipeActionBackground(dismissState.dismissDirection) },
        onDismiss = { direction ->
            when (direction) {
                // Confirming shouldn't remove the row — it just opens the review dialog — so the
                // card springs back to Settled instead of staying swiped away.
                SwipeToDismissBoxValue.StartToEnd -> {
                    onConfirm()
                    scope.launch { dismissState.reset() }
                }
                SwipeToDismissBoxValue.EndToStart -> onDismiss()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CARD_SHAPE,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SenderAvatar(sender = rawSms.sender)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            rawSms.sender,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            formatDate(rawSms.receivedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedIconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(onClick = onConfirm) {
                        Icon(Icons.Filled.Check, contentDescription = "Confirm", modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    rawSms.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (rawSms.submittedAt == null) {
                    TextButton(onClick = onSubmit, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send for a rule")
                    }
                } else {
                    // Still unparsed here until a rule comes back, so the row stays — but asking
                    // again would only add a duplicate to the console's inbox.
                    Text(
                        "Sent for a rule on ${formatDate(rawSms.submittedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

/** Sits behind [ReviewCard] mid-swipe: red+trash trailing the dismiss side, primary+check leading the confirm side. */
@Composable
private fun SwipeActionBackground(direction: SwipeToDismissBoxValue) {
    val color = when (direction) {
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
        SwipeToDismissBoxValue.Settled -> Color.Transparent
    }
    val alignment = if (direction == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CARD_SHAPE)
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        when (direction) {
            SwipeToDismissBoxValue.EndToStart ->
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
            SwipeToDismissBoxValue.StartToEnd ->
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
}

private val AVATAR_COLOURS = listOf(
    Color(0xFF5C6BC0),
    Color(0xFF26A69A),
    Color(0xFFEF6C6C),
    Color(0xFFAB47BC),
    Color(0xFF7CB342),
    Color(0xFFFFA726),
    Color(0xFF42A5F5),
)

@Composable
private fun SenderAvatar(sender: String, modifier: Modifier = Modifier) {
    val initials = sender.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "?" }
    val colour = AVATAR_COLOURS[abs(sender.hashCode()) % AVATAR_COLOURS.size]
    Box(
        modifier = modifier
            .size(44.dp)
            .background(colour, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

private val TRANSACTION_TYPES = listOf(
    "UPI" to "upi",
    "NEFT/IMPS" to "neft_imps",
    "Card" to "card",
    "ATM" to "atm",
    "Auto-debit" to "auto_debit",
    "Other" to "other",
)

private val DISCARD_REASONS = listOf("OTP", "Promo", "Balance-only notice", "Not a transaction", "Other")

/**
 * The upload half of the remote-rules loop (REQUIREMENTS §7): either "this is a transaction my
 * phone couldn't read, please write a rule" or "this sender is noise, please mute it". Shows the
 * redacted template so it's obvious what actually leaves the device.
 */
@Composable
private fun SubmitForRuleSheet(
    rawSms: RawSmsEntity,
    onDismiss: () -> Unit,
    onSendForReview: (transactionType: String, note: String) -> Unit,
    onReportNoise: (reason: String) -> Unit,
) {
    var isNoise by remember { mutableStateOf(false) }
    var transactionType by remember { mutableStateOf(TRANSACTION_TYPES.first().second) }
    var reason by remember { mutableStateOf(DISCARD_REASONS.first()) }
    var note by remember { mutableStateOf("") }
    val template = remember(rawSms.body) { Redactor.redact(rawSms.body) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Without these the send button sits under the gesture nav bar (and behind the
                // keyboard once the note field has focus), where taps never reach it.
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Send for a rule", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only this redacted shape is uploaded — no amounts, account numbers or balances.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                template,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !isNoise,
                    onClick = { isNoise = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Needs a rule") }
                SegmentedButton(
                    selected = isNoise,
                    onClick = { isNoise = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Not a transaction") }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isNoise) {
                    DISCARD_REASONS.forEach { option ->
                        FilterChip(
                            selected = reason == option,
                            onClick = { reason = option },
                            label = { Text(option) },
                        )
                    }
                } else {
                    TRANSACTION_TYPES.forEach { (label, wire) ->
                        FilterChip(
                            selected = transactionType == wire,
                            onClick = { transactionType = wire },
                            label = { Text(label) },
                        )
                    }
                }
            }

            if (!isNoise) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("e.g. UPI payment to a shop") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { if (isNoise) onReportNoise(reason) else onSendForReview(transactionType, note) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isNoise) "Report as noise" else "Send for review") }
        }
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