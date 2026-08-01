package com.example.expensetracker.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.SmsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReviewQueueViewModel(
    private val smsRepository: SmsRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val needsReview: StateFlow<List<RawSmsEntity>> = smsRepository.observeNeedsReview()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirm(
        rawSms: RawSmsEntity,
        amountMinor: Long,
        direction: Direction,
        merchant: String,
        categoryId: Long?,
        accountLabel: String,
    ) {
        viewModelScope.launch {
            smsRepository.confirmReview(rawSms, amountMinor, direction, merchant, categoryId, accountLabel)
        }
    }
}