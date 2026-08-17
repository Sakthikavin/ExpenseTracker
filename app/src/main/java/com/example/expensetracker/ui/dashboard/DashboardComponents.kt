@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.expensetracker.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.expensetracker.ui.common.CategoryBadge
import com.example.expensetracker.ui.common.formatMinorUnitsAsInr

/** Card look shared by every dashboard block: off-white surface, thin border, rounded corners. */
@Composable
private fun DashboardCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DashboardPalette.Surface1, RoundedCornerShape(16.dp))
            .border(1.dp, DashboardPalette.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

private fun formatDeltaPercent(delta: Float): String {
    val rounded = kotlin.math.round(delta)
    return if (rounded == 0f) "0%" else "${if (rounded > 0) "+" else ""}${rounded.toInt()}%"
}

// ---------- 1: date pager ----------

@Composable
fun DatePagerRow(label: String, onPrev: () -> Unit, onNext: () -> Unit, onLabelClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashboardPalette.Surface1, RoundedCornerShape(12.dp))
            .border(1.dp, DashboardPalette.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous period", tint = DashboardPalette.TextSecondary)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = DashboardPalette.TextPrimary,
            modifier = Modifier.clickable(onClick = onLabelClick).padding(horizontal = 8.dp, vertical = 6.dp),
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Next period", tint = DashboardPalette.TextSecondary)
        }
    }
}

private data class PresetOption(val preset: DatePreset, val label: String)

private val PRESET_OPTIONS = listOf(
    PresetOption(DatePreset.TODAY, "Today"),
    PresetOption(DatePreset.LAST_7_DAYS, "Last 7 days"),
    PresetOption(DatePreset.LAST_30_DAYS, "Last 30 days"),
    PresetOption(DatePreset.THIS_MONTH, "This month"),
    PresetOption(DatePreset.LAST_MONTH, "Last month"),
    PresetOption(DatePreset.THIS_YEAR, "This year"),
)

@Composable
fun DatePresetSheet(
    selected: DatePreset,
    onSelect: (DatePreset) -> Unit,
    onCustomRangeRequested: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "Presets",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            PRESET_OPTIONS.forEach { option ->
                val isSelected = option.preset == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option.preset) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option.label,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (isSelected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCustomRangeRequested)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Custom range",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- 2: savings hero + stat tiles ----------

@Composable
fun SavingsHero(
    savedMinor: Long,
    deltaPercent: Float?,
    savedPercentOfIncome: Float,
    incomeMinor: Long,
    expenseMinor: Long,
    periodLabel: String,
) {
    DashboardCard {
        Text(
            "SAVED THIS $periodLabel".uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = DashboardPalette.TextSecondary,
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                formatMinorUnitsAsInr(savedMinor),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = DashboardPalette.TextPrimary,
            )
            if (deltaPercent != null) {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${formatDeltaPercent(deltaPercent)} vs last period",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (deltaPercent >= 0) DashboardPalette.SuccessText else DashboardPalette.StatusCritical,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(6.dp)
                .background(DashboardPalette.Gridline, RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(savedPercentOfIncome.coerceIn(0f, 100f) / 100f)
                    .height(6.dp)
                    .background(
                        if (savedPercentOfIncome >= 0) DashboardPalette.StatusGood else DashboardPalette.StatusCritical,
                        RoundedCornerShape(4.dp),
                    ),
            )
        }
        Text(
            text = "${formatDeltaPercent(savedPercentOfIncome).trimStart('+')} of income saved · " +
                "${formatMinorUnitsAsInr(incomeMinor)} in, ${formatMinorUnitsAsInr(expenseMinor)} out",
            style = MaterialTheme.typography.bodySmall,
            color = DashboardPalette.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun StatTile(label: String, amountMinor: Long, dotColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(DashboardPalette.Surface1, RoundedCornerShape(14.dp))
            .border(1.dp, DashboardPalette.Border, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = DashboardPalette.TextSecondary)
        }
        Text(
            formatMinorUnitsAsInr(amountMinor),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = DashboardPalette.TextPrimary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------- 4: transfer chip ----------

@Composable
fun TransferChip(amountMinor: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashboardPalette.Gridline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.SwapHoriz,
            contentDescription = null,
            tint = DashboardPalette.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "${formatMinorUnitsAsInr(amountMinor)} moved between your own accounts — not counted as income or expense",
            style = MaterialTheme.typography.bodySmall,
            color = DashboardPalette.TextSecondary,
        )
    }
}

// ---------- 3: review banner ----------

@Composable
fun ReviewBanner(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashboardPalette.Surface1, RoundedCornerShape(12.dp))
            .border(1.dp, DashboardPalette.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(30.dp).background(DashboardPalette.ReviewIconBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = DashboardPalette.WarningPillText,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$count transaction${if (count == 1) "" else "s"} need review",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = DashboardPalette.TextPrimary,
            )
            Text(
                "Unrecognised SMS — confirm to teach the parser",
                style = MaterialTheme.typography.bodySmall,
                color = DashboardPalette.TextSecondary,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = DashboardPalette.TextMuted)
    }
}

// ---------- 5: budgets at a glance ----------

@Composable
fun BudgetGlanceSection(rows: List<BudgetGlanceRow>, onSeeAll: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Budgets at a glance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = DashboardPalette.TextPrimary)
            Text(
                "See all →",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onSeeAll),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row -> BudgetGlanceItem(row) }
        }
    }
}

private data class StatusStyle(val pillBg: Color, val pillText: Color, val meterColor: Color, val label: String)

private fun styleFor(status: BudgetStatus): StatusStyle = when (status) {
    BudgetStatus.GOOD -> StatusStyle(DashboardPalette.GoodPillBg, DashboardPalette.GoodPillText, DashboardPalette.StatusGood, "On track")
    BudgetStatus.WARNING -> StatusStyle(DashboardPalette.WarningPillBg, DashboardPalette.WarningPillText, DashboardPalette.StatusWarning, "Near limit")
    BudgetStatus.CRITICAL -> StatusStyle(DashboardPalette.CriticalPillBg, DashboardPalette.CriticalPillText, DashboardPalette.StatusCritical, "Over budget")
}

/** Public so `ui/budgets/BudgetsScreen.kt` can reuse it for "Needs attention" rows there too. */
@Composable
fun BudgetGlanceItem(row: BudgetGlanceRow, modifier: Modifier = Modifier) {
    val style = styleFor(row.status)
    val remainingMinor = row.limitMinor - row.spentMinor
    val caption = if (remainingMinor >= 0) {
        "${formatMinorUnitsAsInr(row.spentMinor)} of ${formatMinorUnitsAsInr(row.limitMinor)} · ${formatMinorUnitsAsInr(remainingMinor)} left"
    } else {
        "${formatMinorUnitsAsInr(row.spentMinor)} of ${formatMinorUnitsAsInr(row.limitMinor)} · over by ${formatMinorUnitsAsInr(-remainingMinor)}"
    }

    DashboardCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryBadge(row.category, size = 20.dp)
                Text(row.category.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = DashboardPalette.TextPrimary)
            }
            Row(
                modifier = Modifier
                    .background(style.pillBg, RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(modifier = Modifier.size(6.dp).background(style.meterColor, CircleShape))
                Text(style.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = style.pillText)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(6.dp)
                .background(DashboardPalette.Gridline, RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((row.spentMinor.toFloat() / row.limitMinor.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(style.meterColor, RoundedCornerShape(4.dp)),
            )
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = DashboardPalette.TextMuted, modifier = Modifier.padding(top = 5.dp))
    }
}

// ---------- category table row (with vs-last-period delta) ----------

@Composable
fun CategoryDeltaRow(row: CategorySpendRow, deltaPercent: Float?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(row.category, size = 18.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            row.category?.name ?: "Unassigned",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = DashboardPalette.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "%.1f%%".format(row.percentage),
            style = MaterialTheme.typography.bodySmall,
            color = DashboardPalette.TextSecondary,
            modifier = Modifier.width(46.dp),
        )
        Text(
            text = formatMinorUnitsAsInr(row.totalMinor),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = DashboardPalette.TextPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = if (deltaPercent == null) "—" else "${if (deltaPercent >= 0) "▲" else "▼"} ${kotlin.math.abs(deltaPercent).let { kotlin.math.round(it).toInt() }}%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                deltaPercent == null -> MaterialTheme.colorScheme.onSurfaceVariant
                deltaPercent > 0 -> DashboardPalette.StatusCritical
                else -> DashboardPalette.SuccessText
            },
            modifier = Modifier.width(52.dp),
        )
    }
}
