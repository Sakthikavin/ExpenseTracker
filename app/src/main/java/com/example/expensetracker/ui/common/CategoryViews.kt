package com.example.expensetracker.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.expensetracker.data.local.entity.CategoryEntity

private const val UNASSIGNED_COLOUR = 0xFF9E9E9EL

/** Colored circle + icon for a category, consistent everywhere a category is shown. Pass `null` for "Unassigned". */
@Composable
fun CategoryBadge(category: CategoryEntity?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color(category?.colour ?: UNASSIGNED_COLOUR), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CategoryIcons.iconFor(category?.icon ?: "category"),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

/** Badge + name, for list rows and dropdown items. */
@Composable
fun CategoryLabel(
    category: CategoryEntity?,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 22.dp,
    unassignedLabel: String = "Unassigned",
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryBadge(category, size = badgeSize)
        Text(category?.name ?: unassignedLabel)
    }
}
