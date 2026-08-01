package com.example.expensetracker

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.example.expensetracker.ui.common.LocalAppContainer
import com.example.expensetracker.ui.navigation.ExpenseTrackerNavGraph
import com.example.expensetracker.ui.theme.ExpenseTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as ExpenseTrackerApp).container

        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { /* SMS auto-detection and bill notifications stay off until granted. */ }

            LaunchedEffect(Unit) {
                val permissions = buildList {
                    add(Manifest.permission.RECEIVE_SMS)
                    add(Manifest.permission.READ_SMS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                permissionLauncher.launch(permissions.toTypedArray())
            }

            ExpenseTrackerTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ExpenseTrackerNavGraph()
                }
            }
        }
    }
}