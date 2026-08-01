package com.example.expensetracker.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.expensetracker.ui.bills.BillsScreen
import com.example.expensetracker.ui.budgets.BudgetsScreen
import com.example.expensetracker.ui.categories.CategoriesScreen
import com.example.expensetracker.ui.dashboard.DashboardScreen
import com.example.expensetracker.ui.review.ReviewQueueScreen
import com.example.expensetracker.ui.settings.SettingsScreen
import com.example.expensetracker.ui.transactions.TransactionsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseTrackerNavGraph() {
    val navController = rememberNavController()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expense Tracker") },
                actions = {
                    IconButton(onClick = { navController.navigate(Destination.Categories.route) { launchSingleTop = true } }) {
                        Icon(Icons.Filled.Category, contentDescription = "Categories")
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
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
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
            composable(Destination.Dashboard.route) { DashboardScreen() }
            composable(Destination.Transactions.route) { TransactionsScreen() }
            composable(Destination.Review.route) { ReviewQueueScreen() }
            composable(Destination.Budgets.route) { BudgetsScreen() }
            composable(Destination.Bills.route) { BillsScreen() }
            composable(Destination.Categories.route) { CategoriesScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}