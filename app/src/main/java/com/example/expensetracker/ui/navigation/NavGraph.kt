package com.example.expensetracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.expensetracker.ui.accounts.AccountsScreen
import com.example.expensetracker.ui.bills.BillsScreen
import com.example.expensetracker.ui.budgets.BudgetsScreen
import com.example.expensetracker.ui.categories.CategoriesScreen
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.common.appViewModel
import com.example.expensetracker.ui.dashboard.DashboardScreen
import com.example.expensetracker.ui.review.ReviewQueueScreen
import com.example.expensetracker.ui.settings.SettingsScreen
import com.example.expensetracker.ui.transactions.TransactionsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseTrackerNavGraph() {
    val navController = rememberNavController()
    val container = LocalAppContainer.current
    val badgeViewModel = appViewModel { NavBadgeViewModel(container.smsRepository, container.billRepository) }
    val reviewCount by badgeViewModel.reviewCount.collectAsState()
    val billsCount by badgeViewModel.billsCount.collectAsState()

    fun navigateFromDashboard(route: String) {
        val startId = navController.graph.findStartDestination().id
        navController.popBackStack(startId, inclusive = false)
        navController.navigate(route) { launchSingleTop = true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expense Tracker") },
                actions = {
                    IconButton(onClick = { navController.navigate(Destination.Categories.route) { launchSingleTop = true } }) {
                        Icon(Icons.Filled.Category, contentDescription = "Categories")
                    }
                    IconButton(onClick = { navController.navigate(Destination.Accounts.route) { launchSingleTop = true } }) {
                        Icon(Destination.Accounts.icon, contentDescription = Destination.Accounts.label)
                    }
                    IconButton(onClick = { navController.navigate(Destination.Settings.route) { launchSingleTop = true } }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            NavigationBar {
                Destination.bottomBarItems.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            val startId = navController.graph.findStartDestination().id
                            navController.popBackStack(startId, inclusive = false)
                            if (destination.route != Destination.Dashboard.route) {
                                navController.navigate(destination.route) { launchSingleTop = true }
                            }
                        },
                        icon = {
                            val badgeCount = when (destination) {
                                Destination.Review -> reviewCount
                                Destination.Bills -> billsCount
                                else -> 0
                            }
                            if (badgeCount > 0) {
                                BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                                    Icon(destination.icon, contentDescription = destination.label)
                                }
                            } else {
                                Icon(destination.icon, contentDescription = destination.label)
                            }
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.Dashboard.route) {
                DashboardScreen(
                    onNavigateToReview = { navigateFromDashboard(Destination.Review.route) },
                    onNavigateToBudgets = { navigateFromDashboard(Destination.Budgets.route) },
                    onNavigateToTransactions = { navigateFromDashboard(Destination.Transactions.route) },
                )
            }
            composable(Destination.Transactions.route) { TransactionsScreen() }
            composable(Destination.Review.route) { ReviewQueueScreen() }
            composable(Destination.Budgets.route) { BudgetsScreen() }
            composable(Destination.Bills.route) { BillsScreen() }
            composable(Destination.Categories.route) { CategoriesScreen() }
            composable(Destination.Accounts.route) { AccountsScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}