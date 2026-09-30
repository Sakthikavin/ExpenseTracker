package com.example.expensetracker.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.data.repository.SmsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs "Messages I skipped": the messages that mentioned money but matched no tier and none of the
 * structure a bank alert has. They're kept — capped to the newest few hundred — so the parser's
 * misses are findable. Before this existed, a wording no tier recognised left no trace at all.
 */
class SkippedMessagesViewModel(private val smsRepository: SmsRepository) : ViewModel() {

    val messages: StateFlow<List<RawSmsEntity>> = smsRepository.observeDiscarded()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Hands a message the heuristic misjudged to the review queue, where it can be confirmed into a
     * transaction or sent off for a rule like anything else there.
     */
    fun moveToReview(rawSms: RawSmsEntity) {
        viewModelScope.launch { smsRepository.moveToReview(rawSms) }
    }
}
