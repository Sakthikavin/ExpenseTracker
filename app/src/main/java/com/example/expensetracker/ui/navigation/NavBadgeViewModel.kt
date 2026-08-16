package com.example.expensetracker.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.repository.BillRepository
import com.example.expensetracker.data.repository.SmsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Backs the bottom-nav badges — counts only, no other screen state. */
class NavBadgeViewModel(
    smsRepository: SmsRepository,
    billRepository: BillRepository,
) : ViewModel() {

    val reviewCount: StateFlow<Int> = smsRepository.observeNeedsReview()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val billsCount: StateFlow<Int> = billRepository.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
