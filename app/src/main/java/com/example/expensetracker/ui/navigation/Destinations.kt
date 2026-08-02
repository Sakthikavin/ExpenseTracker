package com.example.expensetracker.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Destination("dashboard", "Dashboard", Icons.Filled.Dashboard)
    data object Transactions : Destination("transactions", "Transactions", Icons.AutoMirrored.Filled.List)
    data object Review : Destination("review", "Review", Icons.Filled.RateReview)
    data object Budgets : Destination("budgets", "Budgets", Icons.Filled.PieChart)
    data object Bills : Destination("bills", "Bills", Icons.Filled.Receipt)
    data object Categories : Destination("categories", "Categories", Icons.Filled.Category)
    data object Accounts : Destination("accounts", "My accounts", Icons.Filled.AccountBalance)
    data object Settings : Destination("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val bottomBarItems = listOf(Dashboard, Transactions, Review, Budgets, Bills)
    }
}