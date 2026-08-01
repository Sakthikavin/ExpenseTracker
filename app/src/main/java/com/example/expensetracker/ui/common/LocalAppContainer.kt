package com.example.expensetracker.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.expensetracker.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided — wrap the composition in CompositionLocalProvider from MainActivity")
}