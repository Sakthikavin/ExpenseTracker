package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.dao.CategoryDao
import com.example.expensetracker.data.local.dao.OwnAccountDao
import com.example.expensetracker.data.local.dao.TransactionDao
import com.example.expensetracker.data.local.entity.OwnAccountEntity
import com.example.expensetracker.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/** The name of the category a paired transfer is filed under, so it reads as one at a glance. */
const val TRANSFERS_CATEGORY_NAME = "Transfers"

/**
 * Owns the "these two rows are one transfer" relationship: which accounts are the user's, finding
 * the other leg, and linking or unlinking a pair.
 */
class TransferRepository(
    private val transactionDao: TransactionDao,
    private val ownAccountDao: OwnAccountDao,
    private val categoryDao: CategoryDao,
) {
    fun observeOwnAccounts(): Flow<List<OwnAccountEntity>> = ownAccountDao.observeAll()

    /** Account labels seen in transactions, so the UI can offer them instead of asking for typing. */
    fun observeSeenAccountLabels(): Flow<List<String>> = ownAccountDao.observeSeenLabels()

    suspend fun claimAccount(label: String, nickname: String = "") {
        ownAccountDao.insert(OwnAccountEntity(label = label, nickname = nickname))
    }

    suspend fun releaseAccount(label: String) = ownAccountDao.deleteByLabel(label)

    suspend fun renameAccount(account: OwnAccountEntity, nickname: String) =
        ownAccountDao.update(account.copy(nickname = nickname))

    suspend fun ownAccountLabels(): Set<String> =
        ownAccountDao.getAll().map { it.label.trim().uppercase() }.toSet()

    /**
     * Looks for the other leg of [transaction] and links them when confident.
     *
     * @return the confidence of the link that was made, or null when nothing matched. A
     * [TransferMatcher.Confidence.SUGGESTED] result is *not* linked — it is returned so the caller
     * can offer it for confirmation instead of guessing on the user's behalf.
     */
    suspend fun tryPair(
        transaction: TransactionEntity,
        counterpartyAccount: String? = null,
    ): TransferMatcher.Match? {
        if (transaction.transferGroupId != null) return null

        val window = 72L * 60 * 60 * 1000
        val candidates = transactionDao.findUnpairedBetween(
            start = Instant.fromEpochMilliseconds(transaction.occurredAt.toEpochMilliseconds() - window),
            end = Instant.fromEpochMilliseconds(transaction.occurredAt.toEpochMilliseconds() + window),
            excludeId = transaction.id,
        )

        val match = TransferMatcher.match(
            transaction = transaction,
            candidates = candidates,
            ownAccountLabels = ownAccountLabels(),
            counterpartyAccount = counterpartyAccount,
        ) ?: return null

        if (match.confidence == TransferMatcher.Confidence.AUTOMATIC) {
            link(transaction, match.counterpart)
        }
        return match
    }

    /** Marks two transactions as the legs of one transfer and files both under "Transfers". */
    suspend fun link(first: TransactionEntity, second: TransactionEntity) {
        val groupId = TransferMatcher.newGroupId()
        transactionDao.setTransferGroup(listOf(first.id, second.id), groupId)
        transfersCategoryId()?.let { categoryId ->
            transactionDao.update(first.copy(transferGroupId = groupId, categoryId = categoryId))
            transactionDao.update(second.copy(transferGroupId = groupId, categoryId = categoryId))
        }
    }

    /** A transfer whose other leg never produced a message — still not spending. */
    suspend fun markSingleLeg(transaction: TransactionEntity) {
        val groupId = TransferMatcher.newGroupId()
        transactionDao.update(
            transaction.copy(transferGroupId = groupId, categoryId = transfersCategoryId() ?: transaction.categoryId),
        )
    }

    /** Undo. Both legs return to being ordinary transactions, and start counting again. */
    suspend fun unlink(groupId: String) {
        val legs = transactionDao.findByTransferGroup(groupId)
        transactionDao.setTransferGroup(legs.map { it.id }, null)
    }

    suspend fun legsOf(groupId: String): List<TransactionEntity> =
        transactionDao.findByTransferGroup(groupId)

    /** Counterparts a human could plausibly pick when tagging a transfer by hand. */
    suspend fun manualCandidates(transaction: TransactionEntity, all: List<TransactionEntity>) =
        TransferMatcher.manualCandidates(transaction, all)

    private suspend fun transfersCategoryId(): Long? =
        categoryDao.getByName(TRANSFERS_CATEGORY_NAME)?.id
}
