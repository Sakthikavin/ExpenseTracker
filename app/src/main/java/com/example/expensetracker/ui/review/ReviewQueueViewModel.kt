package com.example.expensetracker.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.data.remoterules.SubmissionAction
import com.example.expensetracker.data.remoterules.SubmissionRepository
import com.example.expensetracker.data.remoterules.SubmissionResult
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.MerchantCategoryRuleRepository
import com.example.expensetracker.data.repository.SmsRepository
import com.example.expensetracker.ui.common.CategorizePrompt
import com.example.expensetracker.ui.common.toPrompt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ReviewQueueViewModel(
    private val smsRepository: SmsRepository,
    categoryRepository: CategoryRepository,
    private val merchantCategoryRuleRepository: MerchantCategoryRuleRepository? = null,
    private val submissionRepository: SubmissionRepository? = null,
) : ViewModel() {

    private val _submissionMessage = MutableStateFlow<String?>(null)

    /** Outcome of the most recent upload, for the screen to surface in a snackbar. */
    val submissionMessage: StateFlow<String?> = _submissionMessage.asStateFlow()

    /**
     * "Send for review": uploads the redacted template so a rule can be written for it. The row
     * stays in the queue — it's still unparsed on this phone until that rule lands.
     */
    fun sendForReview(rawSms: RawSmsEntity, transactionType: String, note: String) {
        submit(rawSms, SubmissionAction.REVIEW, transactionType, note, successMessage = "Sent for review")
    }

    /**
     * "Not a transaction": uploads the template so this sender/shape can be added to
     * `discardSenders`, and hides the row locally right away rather than waiting for that.
     */
    fun discardAsNoise(rawSms: RawSmsEntity, reason: String) {
        submit(rawSms, SubmissionAction.DISCARD, transactionType = null, note = reason, successMessage = "Reported as noise")
        viewModelScope.launch { smsRepository.dismissReview(rawSms) }
    }

    private fun submit(
        rawSms: RawSmsEntity,
        action: SubmissionAction,
        transactionType: String?,
        note: String?,
        successMessage: String,
    ) {
        val repository = submissionRepository ?: return
        viewModelScope.launch {
            _submissionMessage.value = when (val result = repository.submit(rawSms.sender, rawSms.body, action, transactionType, note)) {
                SubmissionResult.Sent -> successMessage
                is SubmissionResult.Blocked ->
                    "Not sent — the message still shows ${result.hints.joinToString(" and ")} after redaction"
                SubmissionResult.Failed -> "Couldn't reach the server — try again later"
            }
        }
    }

    fun clearSubmissionMessage() {
        _submissionMessage.value = null
    }

    private val _categorizePrompt = MutableStateFlow<CategorizePrompt?>(null)

    /** Addendum 4's retroactive-apply / rule-drift follow-up question, when one is pending. */
    val categorizePrompt: StateFlow<CategorizePrompt?> = _categorizePrompt.asStateFlow()

    // Swiping/tapping dismiss hides the item right away, before its own undo window decides
    // whether the dismissal is real — writing it to the database immediately would make undo
    // a second, distinguishable write instead of "as if it never happened". Each item gets its
    // own timer, independent of whatever else gets dismissed afterward — otherwise dismissing a
    // second item while the first is still undoable would finalize the first with no warning.
    private val pendingDismissIds = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingDismissJobs = mutableMapOf<Long, Job>()

    val needsReview: StateFlow<List<RawSmsEntity>> =
        combine(smsRepository.observeNeedsReview(), pendingDismissIds) { items, pending ->
            items.filterNot { it.id in pending }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            val outcome = smsRepository.confirmReview(rawSms, amountMinor, direction, merchant, categoryId, accountLabel)
            _categorizePrompt.value = outcome.toPrompt(merchant)
        }
    }

    /** "Apply to N" on [CategorizePrompt.RetroactiveApply]. */
    fun applyRetroactively(prompt: CategorizePrompt.RetroactiveApply) {
        viewModelScope.launch {
            merchantCategoryRuleRepository?.applyRetroactively(prompt.merchantKey, prompt.categoryId)
            _categorizePrompt.value = null
        }
    }

    /** "Update rule" on [CategorizePrompt.RuleUpdate]. */
    fun confirmRuleUpdate(prompt: CategorizePrompt.RuleUpdate) {
        viewModelScope.launch {
            merchantCategoryRuleRepository?.confirmRuleUpdate(prompt.merchantKey, prompt.newCategoryId)
            _categorizePrompt.value = null
        }
    }

    /** "Not now" / "Keep rule as-is" — the category change already applied, only the rule question is declined. */
    fun dismissCategorizePrompt() {
        _categorizePrompt.value = null
    }

    /** Hides the item and starts its own undo window; [undoDismiss] cancels it before it lapses. */
    fun dismiss(rawSms: RawSmsEntity) {
        pendingDismissIds.update { it + rawSms.id }
        pendingDismissJobs[rawSms.id]?.cancel()
        pendingDismissJobs[rawSms.id] = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            smsRepository.dismissReview(rawSms)
            pendingDismissIds.update { it - rawSms.id }
            pendingDismissJobs.remove(rawSms.id)
        }
    }

    /** The undo action was tapped before this item's own window lapsed. */
    fun undoDismiss(rawSms: RawSmsEntity) {
        pendingDismissJobs.remove(rawSms.id)?.cancel()
        pendingDismissIds.update { it - rawSms.id }
    }

    private companion object {
        const val UNDO_WINDOW_MS = 4_000L
    }
}