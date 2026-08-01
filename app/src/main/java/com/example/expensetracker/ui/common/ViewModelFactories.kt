package com.example.expensetracker.ui.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/** A [ViewModelProvider.Factory] backed by a plain lambda — no reflection, no DI framework. */
class LambdaViewModelFactory<T : ViewModel>(private val creator: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
}

/** Constructs a ViewModel from [creator], wired to [AppContainer] via [LocalAppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(noinline creator: () -> VM): VM =
    viewModel(factory = LambdaViewModelFactory(creator))