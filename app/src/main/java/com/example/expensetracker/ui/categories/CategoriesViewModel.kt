package com.example.expensetracker.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.MerchantCategoryRuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoriesViewModel(
    private val categoryRepository: CategoryRepository,
    private val merchantCategoryRuleRepository: MerchantCategoryRuleRepository? = null,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Backs the "Merchant rules · N merchants auto-categorize on arrival" entry-point row (Addendum 4). */
    val ruleCount: StateFlow<Int> = (merchantCategoryRuleRepository?.observeAll() ?: flowOf(emptyList()))
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _ruleCountForPendingDelete = MutableStateFlow(0)

    /** How many merchant rules point at the category currently pending deletion — for the "N merchant rules … will also be removed" warning. */
    val ruleCountForPendingDelete: StateFlow<Int> = _ruleCountForPendingDelete.asStateFlow()

    fun loadRuleCountFor(categoryId: Long) {
        viewModelScope.launch {
            _ruleCountForPendingDelete.value = merchantCategoryRuleRepository?.countForCategory(categoryId) ?: 0
        }
    }

    fun addCategory(name: String, icon: String, colour: Long, isIncome: Boolean) {
        viewModelScope.launch {
            categoryRepository.create(CategoryEntity(name = name, icon = icon, colour = colour, isIncome = isIncome))
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { categoryRepository.delete(id) }
    }
}