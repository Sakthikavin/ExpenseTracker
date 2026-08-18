package com.example.expensetracker.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.MerchantCategoryRuleEntity
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.MerchantCategoryRuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row on the Merchant Rules screen — the rule joined with its category and live transaction count. */
data class MerchantRuleRow(
    val rule: MerchantCategoryRuleEntity,
    val category: CategoryEntity?,
    val transactionCount: Int,
) {
    val displayLabel: String get() = rule.displayName ?: rule.merchantKey
}

class MerchantRulesViewModel(
    private val ruleRepository: MerchantCategoryRuleRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rows: StateFlow<List<MerchantRuleRow>> = combine(
        ruleRepository.observeAll(),
        categoryRepository.observeAll(),
        ruleRepository.observeMerchantCounts(),
        _query,
    ) { rules, categories, counts, query ->
        val countByKey = counts.associate { it.merchantKey to it.count }
        val needle = query.trim()
        rules
            .map { rule ->
                MerchantRuleRow(
                    rule = rule,
                    category = categories.firstOrNull { it.id == rule.categoryId },
                    transactionCount = countByKey[rule.merchantKey] ?: 0,
                )
            }
            .filter {
                needle.isBlank() ||
                    it.rule.merchantKey.contains(needle, ignoreCase = true) ||
                    it.rule.displayName?.contains(needle, ignoreCase = true) == true
            }
            .sortedBy { it.displayLabel }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Quick reassignment from tapping a row — matching key untouched. */
    fun reassignCategory(ruleId: Long, categoryId: Long) {
        viewModelScope.launch { ruleRepository.reassignCategory(ruleId, categoryId) }
    }

    /** The rename sheet's Save (Addendum 5) — rename and re-category share one sheet. */
    fun saveRename(ruleId: Long, displayName: String?, categoryId: Long) {
        viewModelScope.launch { ruleRepository.updateDisplayNameAndCategory(ruleId, displayName, categoryId) }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch { ruleRepository.deleteRule(id) }
    }

    suspend fun smsContextFor(merchantKey: String): String? = ruleRepository.smsContextFor(merchantKey)
}
