package com.example.expensetracker.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.repository.CategorizeOutcome

/**
 * UI-facing form of [CategorizeOutcome] — carries the human-readable merchant text (the
 * [CategorizeOutcome] itself only has the normalized `merchantKey`) needed to render Addendum 4's
 * two follow-up questions. `Applied` has nothing to ask, so it has no [CategorizePrompt] — see [toPrompt].
 */
sealed interface CategorizePrompt {
    val merchantDisplay: String

    /** Decision #1: a new rule was just learned and other past Unassigned transactions match it. */
    data class RetroactiveApply(
        val merchantKey: String,
        override val merchantDisplay: String,
        val categoryId: Long,
        val count: Int,
    ) : CategorizePrompt

    /** Decision #4: this one edit disagrees with the merchant's existing rule. */
    data class RuleUpdate(
        val merchantKey: String,
        override val merchantDisplay: String,
        val oldCategoryId: Long,
        val newCategoryId: Long,
    ) : CategorizePrompt
}

fun CategorizeOutcome.toPrompt(merchantDisplay: String): CategorizePrompt? = when (this) {
    CategorizeOutcome.Applied -> null
    is CategorizeOutcome.OfferRetroactiveApply -> CategorizePrompt.RetroactiveApply(merchantKey, merchantDisplay, categoryId, count)
    is CategorizeOutcome.OfferRuleUpdate -> CategorizePrompt.RuleUpdate(merchantKey, merchantDisplay, oldCategoryId, newCategoryId)
}

private val BarBg = Color(0xFF1A1A19)
private val BarBody = Color(0xFFD8D7D0)
private val BarAction = Color(0xFF7FB2F0)

/**
 * The dark two-action bar from the Addendum 4 mockup ("Apply to past transactions?" /
 * "Update the rule too?") — deliberately not a platform [androidx.compose.material3.Snackbar],
 * which only supports one action.
 */
@Composable
fun CategorizePromptBar(
    prompt: CategorizePrompt,
    categoryName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, body, confirmLabel) = when (prompt) {
        is CategorizePrompt.RetroactiveApply -> Triple(
            "Apply to past transactions?",
            "${prompt.count} other Unassigned transaction${if (prompt.count == 1) "" else "s"} from " +
                "${prompt.merchantDisplay} could also be $categoryName.",
            "APPLY TO ${prompt.count}",
        )
        is CategorizePrompt.RuleUpdate -> Triple(
            "Update the rule too?",
            "${prompt.merchantDisplay} is currently mapped to a different category. Change future " +
                "${prompt.merchantDisplay} transactions to $categoryName as well?",
            "UPDATE RULE",
        )
    }
    val dismissLabel = if (prompt is CategorizePrompt.RuleUpdate) "KEEP RULE AS-IS" else "NOT NOW"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BarBg, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = BarBody,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onDismiss) { Text(dismissLabel, color = BarAction, fontWeight = FontWeight.Bold) }
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = BarAction, fontWeight = FontWeight.Bold) }
        }
    }
}
