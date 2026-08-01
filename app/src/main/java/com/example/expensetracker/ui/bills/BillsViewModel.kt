package com.example.expensetracker.ui.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.BillEntity
import com.example.expensetracker.data.local.entity.BillRecurrence
import com.example.expensetracker.data.repository.BillRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BillsViewModel(private val billRepository: BillRepository) : ViewModel() {

    val bills: StateFlow<List<BillEntity>> = billRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addBill(name: String, amountMinor: Long, dueDay: Int, recurrence: BillRecurrence) {
        viewModelScope.launch {
            billRepository.create(BillEntity(name = name, amountMinor = amountMinor, dueDay = dueDay, recurrence = recurrence))
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { billRepository.delete(id) }
    }
}