package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.dao.MerchantCategoryRuleDao
import com.example.expensetracker.data.local.dao.MerchantCount
import com.example.expensetracker.data.local.dao.RawSmsDao
import com.example.expensetracker.data.local.entity.MerchantCategoryRuleEntity
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.data.local.entity.merchantKeyOf
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock

/** What happened after a merchant was (re)categorized — see [MerchantCategoryRuleRepository.learnFromCategorization]. */
sealed interface CategorizeOutcome {
    /** Category was set; no rule question needs asking (no rule existed to drift, nothing to retro-apply). */
    data object Applied : CategorizeOutcome

    /**
     * A brand-new rule was just learned and other Unassigned transactions from the same merchant
     * exist. Addendum 4, decision #1: ask before touching them, never bulk-fire silently.
     */
    data class OfferRetroactiveApply(val merchantKey: String, val categoryId: Long, val count: Int) : CategorizeOutcome

    /**
     * This merchant already had a rule pointing at a different category. Addendum 4, decision #4:
     * ask before a one-off edit becomes the new default for every future transaction from this
     * merchant. The rule is deliberately left untouched until [MerchantCategoryRuleRepository.confirmRuleUpdate].
     */
    data class OfferRuleUpdate(val merchantKey: String, val oldCategoryId: Long, val newCategoryId: Long) : CategorizeOutcome
}

/**
 * "Learn as you categorize" — categorizing a merchant once auto-categorizes it next time — plus
 * the merchant-rename feature (a cosmetic [MerchantCategoryRuleEntity.displayName] that never
 * affects matching).
 *
 * [learnFromCategorization] is the one shared "set category and learn" function this depends on:
 * both [com.example.expensetracker.ui.transactions.TransactionsViewModel.updateCategory]
 * and [SmsRepository.confirmReview] call through it (directly or via [setCategoryAndLearn]) rather
 * than duplicating the retroactive-apply/rule-drift logic.
 */
class MerchantCategoryRuleRepository(
    private val ruleDao: MerchantCategoryRuleDao,
    private val rawSmsDao: RawSmsDao,
    private val transactionRepository: TransactionRepository,
) {
    fun observeAll(): Flow<List<MerchantCategoryRuleEntity>> = ruleDao.observeAll()

    fun observeMerchantCounts(): Flow<List<MerchantCount>> = transactionRepository.observeMerchantCounts()

    /** Silent lookup for auto-categorizing a new transaction — never learns, never prompts. */
    suspend fun categoryForMerchant(merchant: String): Long? {
        val key = merchantKeyOf(merchant)
        if (key.isBlank()) return null
        return ruleDao.getByKey(key)?.categoryId
    }

    /**
     * Sets [transaction]'s category and learns from it. Use when the transaction already exists and
     * its category is changing in place — e.g. the category dropdown on a Transactions row.
     */
    suspend fun setCategoryAndLearn(transaction: TransactionEntity, categoryId: Long?): CategorizeOutcome {
        transactionRepository.update(transaction.copy(categoryId = categoryId))
        if (categoryId == null) return CategorizeOutcome.Applied
        return learnFromCategorization(transaction.merchant, categoryId)
    }

    /**
     * The rule-learning core: upserts/checks the merchant's rule and decides whether a follow-up
     * question is needed. Call this directly (rather than [setCategoryAndLearn]) when the caller
     * already wrote the transaction's category itself — e.g. [SmsRepository.confirmReview], which
     * creates-or-updates the transaction as part of its own reconciliation logic.
     */
    suspend fun learnFromCategorization(merchant: String, categoryId: Long): CategorizeOutcome {
        val merchantKey = merchantKeyOf(merchant)
        if (merchantKey.isBlank()) return CategorizeOutcome.Applied

        val existingRule = ruleDao.getByKey(merchantKey)
        if (existingRule == null) {
            ruleDao.insert(
                MerchantCategoryRuleEntity(merchantKey = merchantKey, categoryId = categoryId, updatedAt = Clock.System.now()),
            )
            val unassignedCount = transactionRepository.findUnassignedByMerchantKey(merchantKey).size
            return if (unassignedCount > 0) {
                CategorizeOutcome.OfferRetroactiveApply(merchantKey, categoryId, unassignedCount)
            } else {
                CategorizeOutcome.Applied
            }
        }

        if (existingRule.categoryId != categoryId) {
            return CategorizeOutcome.OfferRuleUpdate(merchantKey, existingRule.categoryId, categoryId)
        }
        return CategorizeOutcome.Applied
    }

    /** "Apply to N" — the user accepted [CategorizeOutcome.OfferRetroactiveApply]. */
    suspend fun applyRetroactively(merchantKey: String, categoryId: Long) {
        transactionRepository.applyCategoryToUnassignedByMerchantKey(merchantKey, categoryId)
    }

    /** "Update rule" — the user accepted [CategorizeOutcome.OfferRuleUpdate]. Preserves [MerchantCategoryRuleEntity.displayName]. */
    suspend fun confirmRuleUpdate(merchantKey: String, categoryId: Long) {
        val existing = ruleDao.getByKey(merchantKey) ?: return
        ruleDao.update(existing.copy(categoryId = categoryId, updatedAt = Clock.System.now()))
    }

    /** Quick category reassignment from the Merchant Rules list (tap a row) — matching key untouched. */
    suspend fun reassignCategory(ruleId: Long, categoryId: Long) {
        val existing = ruleDao.getById(ruleId) ?: return
        ruleDao.update(existing.copy(categoryId = categoryId, updatedAt = Clock.System.now()))
    }

    /**
     * The rename sheet's Save (Addendum 5) — rename and re-category share one sheet since both edit
     * the same rule row. [displayName] blank/null clears back to showing the raw [MerchantCategoryRuleEntity.merchantKey].
     */
    suspend fun updateDisplayNameAndCategory(ruleId: Long, displayName: String?, categoryId: Long) {
        val existing = ruleDao.getById(ruleId) ?: return
        ruleDao.update(
            existing.copy(
                displayName = displayName?.trim()?.ifBlank { null },
                categoryId = categoryId,
                updatedAt = Clock.System.now(),
            ),
        )
    }

    /** Stops future auto-apply for this merchant; never touches past transactions. */
    suspend fun deleteRule(id: Long) = ruleDao.delete(id)

    suspend fun countForCategory(categoryId: Long): Int = ruleDao.countForCategory(categoryId)

    /**
     * The raw SMS text that taught [merchantKey]'s rule — Addendum 5, decision #3: shown read-only
     * on the rename sheet so a garbled merchant string (e.g. "Electronic c") is explained in place.
     */
    suspend fun smsContextFor(merchantKey: String): String? {
        val transaction = transactionRepository.findEarliestByMerchantKey(merchantKey) ?: return null
        val rawSmsId = transaction.rawSmsId ?: return null
        return rawSmsDao.getById(rawSmsId)?.body
    }
}
