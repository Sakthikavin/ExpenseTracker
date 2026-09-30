package com.example.expensetracker.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.expensetracker.data.local.entity.Direction
import kotlinx.datetime.LocalDate

sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    /** The concrete, argument-free route a plain tab tap navigates to. Defaults to [route]. */
    open val baseRoute: String get() = route

    data object Dashboard : Destination("dashboard", "Dashboard", Icons.Filled.Dashboard)

    data object Transactions : Destination(
        route = "transactions?$ARG_DIRECTION={$ARG_DIRECTION}&$ARG_CATEGORY_ID={$ARG_CATEGORY_ID}" +
            "&$ARG_START_DATE={$ARG_START_DATE}&$ARG_END_DATE={$ARG_END_DATE}",
        label = "Transactions",
        icon = Icons.AutoMirrored.Filled.List,
    ) {
        override val baseRoute = "transactions"

        /**
         * Builds a concrete, navigable route filtered to [direction] and/or [categoryId], scoped to
         * [startDate]..[endDate] when given. Pass [UNASSIGNED_CATEGORY_ID] for "Unassigned" — a
         * transaction's own `categoryId == null` already means unassigned, so filtering needs a
         * value that isn't null to mean the same thing. Omit everything for the unfiltered list.
         */
        fun filteredRoute(
            direction: Direction? = null,
            categoryId: Long? = null,
            startDate: LocalDate? = null,
            endDate: LocalDate? = null,
        ): String {
            val params = buildList {
                direction?.let { add("$ARG_DIRECTION=${it.name}") }
                categoryId?.let { add("$ARG_CATEGORY_ID=$it") }
                startDate?.let { add("$ARG_START_DATE=$it") }
                endDate?.let { add("$ARG_END_DATE=$it") }
            }
            return if (params.isEmpty()) baseRoute else "$baseRoute?${params.joinToString("&")}"
        }
    }

    data object Review : Destination("review", "Review", Icons.Filled.RateReview)

    /** Reached from Settings, not the bottom bar — a diagnostic list, not a daily destination. */
    data object SkippedMessages : Destination("skipped_messages", "Messages I skipped", Icons.Filled.Inbox)
    data object Budgets : Destination("budgets", "Budgets", Icons.Filled.PieChart)
    data object Bills : Destination("bills", "Bills", Icons.Filled.Receipt)
    data object Categories : Destination("categories", "Categories", Icons.Filled.Category)
    data object MerchantRules : Destination("merchant_rules", "Merchant rules", Icons.AutoMirrored.Filled.List)
    data object Accounts : Destination("accounts", "My accounts", Icons.Filled.AccountBalance)
    data object Settings : Destination("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val bottomBarItems = listOf(Dashboard, Transactions, Review, Budgets, Bills)

        const val ARG_DIRECTION = "direction"
        const val ARG_CATEGORY_ID = "categoryId"
        const val ARG_START_DATE = "startDate"
        const val ARG_END_DATE = "endDate"

        /** Sentinel categoryId meaning "filter to Unassigned specifically" — see [Transactions.filteredRoute]. */
        const val UNASSIGNED_CATEGORY_ID = -1L
    }
}
