package com.example.expensetracker.data.repository

import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.data.local.entity.TransactionSource
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferMatcherTest {

    private val day = Instant.parse("2026-08-02T10:00:00Z")

    private fun txn(
        id: Long,
        amountMinor: Long,
        direction: Direction,
        accountLabel: String = "",
        referenceId: String? = null,
        at: Instant = day,
        transferGroupId: String? = null,
    ) = TransactionEntity(
        id = id,
        amountMinor = amountMinor,
        direction = direction,
        occurredAt = at,
        merchant = "",
        accountLabel = accountLabel,
        categoryId = null,
        source = TransactionSource.SMS,
        referenceId = referenceId,
        transferGroupId = transferGroupId,
    )

    // --- the confident cases, linked without asking ---

    @Test
    fun `a shared bank reference is proof of one movement`() {
        val debit = txn(1, 1_000_000, Direction.DEBIT, "XX3941", referenceId = "UTR512345678901")
        val credit = txn(2, 1_000_000, Direction.CREDIT, "XX4795", referenceId = "UTR512345678901")

        val match = TransferMatcher.match(credit, listOf(debit), ownAccountLabels = emptySet())

        assertEquals(TransferMatcher.Confidence.AUTOMATIC, match?.confidence)
        assertEquals(debit, match?.counterpart)
    }

    @Test
    fun `both accounts being mine is enough without a reference`() {
        val debit = txn(1, 1_000_000, Direction.DEBIT, "XX3941")
        val credit = txn(2, 1_000_000, Direction.CREDIT, "XX4795")

        val match = TransferMatcher.match(credit, listOf(debit), setOf("XX3941", "XX4795"))

        assertEquals(TransferMatcher.Confidence.AUTOMATIC, match?.confidence)
    }

    @Test
    fun `a debit naming my account as the destination links automatically`() {
        val credit = txn(2, 1_000_000, Direction.CREDIT, "XX4795")
        val debit = txn(1, 1_000_000, Direction.DEBIT, "XX3941")

        val match = TransferMatcher.match(
            transaction = debit,
            candidates = listOf(credit),
            ownAccountLabels = setOf("XX4795"),
            counterpartyAccount = "XX4795",
        )

        assertEquals(TransferMatcher.Confidence.AUTOMATIC, match?.confidence)
    }

    // --- the unsure case, which must ask rather than guess ---

    @Test
    fun `an unrecognised pair is only suggested`() {
        val debit = txn(1, 1_000_000, Direction.DEBIT, "XX3941")
        val credit = txn(2, 1_000_000, Direction.CREDIT, "XX9999")

        val match = TransferMatcher.match(credit, listOf(debit), ownAccountLabels = emptySet())

        assertEquals(TransferMatcher.Confidence.SUGGESTED, match?.confidence)
    }

    // --- what must never be paired ---

    @Test
    fun `same direction is never a transfer`() {
        val a = txn(1, 1_000_000, Direction.DEBIT, "XX3941")
        val b = txn(2, 1_000_000, Direction.DEBIT, "XX4795")
        assertNull(TransferMatcher.match(b, listOf(a), setOf("XX3941", "XX4795")))
    }

    @Test
    fun `a different amount is a different payment`() {
        val debit = txn(1, 1_000_000, Direction.DEBIT, "XX3941")
        val credit = txn(2, 2_500_000, Direction.CREDIT, "XX4795")
        assertNull(TransferMatcher.match(credit, listOf(debit), setOf("XX3941", "XX4795")))
    }

    @Test
    fun `a transfer fee still counts as the same movement`() {
        // ₹10,005 leaves, ₹10,000 arrives — IMPS charges apply.
        val debit = txn(1, 1_000_500, Direction.DEBIT, "XX3941")
        val credit = txn(2, 1_000_000, Direction.CREDIT, "XX4795")
        assertEquals(
            TransferMatcher.Confidence.AUTOMATIC,
            TransferMatcher.match(credit, listOf(debit), setOf("XX3941", "XX4795"))?.confidence,
        )
    }

    @Test
    fun `legs too far apart are not paired`() {
        val debit = txn(1, 1_000_000, Direction.DEBIT, "XX3941", at = day)
        val credit = txn(
            2, 1_000_000, Direction.CREDIT, "XX4795",
            at = Instant.parse("2026-08-09T10:00:00Z"),
        )
        assertNull(TransferMatcher.match(credit, listOf(debit), setOf("XX3941", "XX4795")))
    }

    @Test
    fun `an already-paired transaction is not stolen into another transfer`() {
        val taken = txn(1, 1_000_000, Direction.DEBIT, "XX3941", transferGroupId = "existing")
        val credit = txn(2, 1_000_000, Direction.CREDIT, "XX4795")
        assertNull(TransferMatcher.match(credit, listOf(taken), setOf("XX3941", "XX4795")))
    }

    // --- the manual picker ---

    @Test
    fun `manual candidates are opposite direction, nearby, closest amount first`() {
        val subject = txn(1, 1_000_000, Direction.DEBIT, "XX3941")
        val exact = txn(2, 1_000_000, Direction.CREDIT, "XX4795")
        val close = txn(3, 1_010_000, Direction.CREDIT, "XX4795")
        val sameDirection = txn(4, 1_000_000, Direction.DEBIT, "XX4795")
        val tooOld = txn(5, 1_000_000, Direction.CREDIT, at = Instant.parse("2026-06-01T10:00:00Z"))

        val candidates = TransferMatcher.manualCandidates(
            subject,
            listOf(subject, exact, close, sameDirection, tooOld),
        )

        assertEquals(listOf(exact, close), candidates)
    }

    @Test
    fun `manual candidates tolerate a wider amount gap than automatic pairing`() {
        val subject = txn(1, 1_000_000, Direction.DEBIT)
        val loose = txn(2, 900_000, Direction.CREDIT)

        assertNull(TransferMatcher.match(subject, listOf(loose), emptySet()))
        assertTrue(loose in TransferMatcher.manualCandidates(subject, listOf(subject, loose)))
    }

    @Test
    fun `group ids are unique`() {
        assertTrue(TransferMatcher.newGroupId() != TransferMatcher.newGroupId())
    }
}
