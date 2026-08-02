package com.example.expensetracker.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.OwnAccountEntity
import com.example.expensetracker.data.repository.TransferRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One account label, and whether the user has claimed it as their own. */
data class AccountRow(
    val label: String,
    val owned: OwnAccountEntity?,
) {
    val isOwned: Boolean get() = owned != null
    val displayName: String get() = owned?.displayName ?: label
}

class AccountsViewModel(private val transferRepository: TransferRepository) : ViewModel() {

    /**
     * Every label seen in a message, merged with the ones already claimed — so the screen is a list
     * of toggles rather than a form asking for account numbers.
     */
    val accounts: StateFlow<List<AccountRow>> = combine(
        transferRepository.observeSeenAccountLabels(),
        transferRepository.observeOwnAccounts(),
    ) { seen, owned ->
        val byLabel = owned.associateBy { it.label }
        (seen + owned.map { it.label }).distinct().sorted().map { AccountRow(it, byLabel[it]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setOwned(label: String, owned: Boolean) {
        viewModelScope.launch {
            if (owned) transferRepository.claimAccount(label) else transferRepository.releaseAccount(label)
        }
    }

    fun rename(account: OwnAccountEntity, nickname: String) {
        viewModelScope.launch { transferRepository.renameAccount(account, nickname) }
    }
}
