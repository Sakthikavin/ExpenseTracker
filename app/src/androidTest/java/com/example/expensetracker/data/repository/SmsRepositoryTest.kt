package com.example.expensetracker.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.expensetracker.data.local.AppDatabase
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.ParseStatus
import com.example.expensetracker.data.sms.SmsParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the ingest path against a real (in-memory) database, because the behaviour worth
 * protecting here — not double-counting money, linking rows together — lives in the interaction
 * between the repository and SQLite constraints, not in either alone.
 */
@RunWith(AndroidJUnit4::class)
class SmsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SmsRepository

    private val federalSms =
        "Debited Rs 1.00 from a/c X6686 on 01Aug26 19:00 via UPI to KEERTHANA KU. " +
            "Ref 621312687340.Bal Rs 42727.9. -Federal Bank"

    private lateinit var transferRepository: TransferRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        transferRepository = TransferRepository(
            transactionDao = db.transactionDao(),
            ownAccountDao = db.ownAccountDao(),
            categoryDao = db.categoryDao(),
        )
        repository = SmsRepository(
            rawSmsDao = db.rawSmsDao(),
            learnedPatternDao = db.learnedPatternDao(),
            transactionRepository = TransactionRepository(db.transactionDao()),
            parser = SmsParser(db.learnedPatternDao()),
            transferRepository = transferRepository,
        )
    }

    @After
    fun tearDown() = db.close()

    /** The money-losing bug: Android can redeliver a broadcast, and it must not count twice. */
    @Test
    fun ingestingTheSameMessageTwiceCreatesOneTransaction() = runBlocking {
        val receivedAt = Instant.fromEpochMilliseconds(1_785_657_168_000)

        repository.ingest("AD-FEDBNK", federalSms, receivedAt)
        repository.ingest("AD-FEDBNK", federalSms, receivedAt)

        val transactions = db.transactionDao().observeAll().first()
        assertEquals("a redelivered broadcast must not double-count", 1, transactions.size)
        assertEquals(100, transactions.single().amountMinor)
    }

    /**
     * Two real payments are two transactions. They are told apart by the bank's reference, not by
     * their wording: an identical body carrying an identical reference is the *same* payment
     * however far apart the two copies arrive.
     */
    @Test
    fun twoRealPaymentsWithDifferentReferencesAreBothKept() = runBlocking {
        repository.ingest("AD-FEDBNK", federalSms, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest(
            "AD-FEDBNK",
            federalSms.replace("621312687340", "621312687341"),
            Instant.fromEpochMilliseconds(1_785_657_999_000),
        )

        assertEquals(2, db.transactionDao().observeAll().first().size)
    }

    @Test
    fun aRepeatOfTheSameReferenceIsNeverCountedTwice() = runBlocking {
        repository.ingest("AD-FEDBNK", federalSms, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest("AD-FEDBNK", federalSms, Instant.fromEpochMilliseconds(1_785_657_999_000))

        assertEquals(1, db.transactionDao().observeAll().first().size)
    }

    @Test
    fun autoParsedRowsAreLinkedToTheirTransaction() = runBlocking {
        repository.ingest("AD-FEDBNK", federalSms, Instant.fromEpochMilliseconds(1_785_657_168_000))

        val raw = db.rawSmsDao().observeByStatus(ParseStatus.PARSED).first().single()
        val transaction = db.transactionDao().observeAll().first().single()

        assertNotNull("the auto-parsed row must point at its transaction", raw.linkedTransactionId)
        assertEquals(transaction.id, raw.linkedTransactionId)
        assertEquals(raw.id, transaction.rawSmsId)
    }

    @Test
    fun usesTheDateFromTheMessageRatherThanIngestTime() = runBlocking {
        val receivedAt = Instant.fromEpochMilliseconds(1_900_000_000_000) // long after the message
        repository.ingest("AD-FEDBNK", federalSms, receivedAt)

        val occurredAt = db.transactionDao().observeAll().first().single().occurredAt
        assertEquals(Instant.parse("2026-08-01T13:30:00Z"), occurredAt)
    }

    /**
     * Multi-line alerts, ingested through the real receiver path.
     *
     * `adb emu sms send` truncates at the first newline, so the emulator console cannot deliver
     * these — this is the only place the block format is exercised end to end on a device.
     */
    @Test
    fun ingestsMultiLineBlockFormatAlerts() = runBlocking {
        val axis = "Debit INR 292.00\nAxis Bank A/c XX4795\n01-08-26 15:48:58\n" +
            "APY/500405010905/920010018\nWhatsApp BAL to 917036165000"
        val hdfcUpi = "Sent Rs.58.00\nFrom HDFC Bank A/C *3941\nTo Google India Digital Serv\n" +
            "On 24/02/26\nRef 119088866187"

        repository.ingest("AD-AXISBK", axis, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest("VM-HDFCBK", hdfcUpi, Instant.fromEpochMilliseconds(1_785_657_268_000))

        val transactions = db.transactionDao().observeAll().first()
        assertEquals("both multi-line alerts must auto-parse", 2, transactions.size)

        val apy = transactions.first { it.amountMinor == 29200L }
        assertEquals(Direction.DEBIT, apy.direction)
        assertEquals("APY", apy.merchant)
        assertEquals("XX4795", apy.accountLabel)

        val upi = transactions.first { it.amountMinor == 5800L }
        assertEquals("Google India Digital Serv", upi.merchant)
        assertEquals("119088866187", upi.referenceId)
    }

    // --- one payment, two messages ---

    private val npsDebit = "UPDATE: INR 5,000.00 debited from HDFC Bank XX3941 on 05-MAR-26. Info: NEFT " +
        "Dr-UTIB0CCH274-SAKTHI KAVIN S S-SANDOZ - MUM-HDFCH00842011992-NET BANKING SI -NPS " +
        "Contribution M. Avl bal:INR 84,966.79"

    private val npsCredit = "HDFC Bank : NEFT money transfer Txn No HDFCH00842011992 for Rs INR 5,000.00 " +
        "has been credited to SAKTHI KAVIN S S on 05-03-2026 at 04:01:54"

    @Test
    fun theNpsPairBecomesOneDebit() = runBlocking {
        repository.ingest("AD-HDFCBK", npsDebit, Instant.fromEpochMilliseconds(1_772_000_000_000))
        repository.ingest("AD-HDFCBK", npsCredit, Instant.fromEpochMilliseconds(1_772_000_060_000))

        val transactions = db.transactionDao().observeAll().first()
        assertEquals("two messages, one payment", 1, transactions.size)
        assertEquals(500000, transactions.single().amountMinor)
        assertEquals(
            "the money left the account, so it is a debit",
            Direction.DEBIT,
            transactions.single().direction,
        )
    }

    @Test
    fun theNpsPairIsStillOneDebitWhenTheConfirmationArrivesFirst() = runBlocking {
        repository.ingest("AD-HDFCBK", npsCredit, Instant.fromEpochMilliseconds(1_772_000_000_000))
        repository.ingest("AD-HDFCBK", npsDebit, Instant.fromEpochMilliseconds(1_772_000_060_000))

        val transactions = db.transactionDao().observeAll().first()
        assertEquals(1, transactions.size)
        assertEquals(Direction.DEBIT, transactions.single().direction)
    }

    @Test
    fun bothMessagesStayLinkedToTheSurvivingTransaction() = runBlocking {
        repository.ingest("AD-HDFCBK", npsDebit, Instant.fromEpochMilliseconds(1_772_000_000_000))
        repository.ingest("AD-HDFCBK", npsCredit, Instant.fromEpochMilliseconds(1_772_000_060_000))

        val transactionId = db.transactionDao().observeAll().first().single().id
        val rawRows = db.rawSmsDao().observeByStatus(ParseStatus.PARSED).first()
        assertEquals("both messages are kept for audit", 2, rawRows.size)
        assertTrue(
            "both must point at the surviving transaction",
            rawRows.all { it.linkedTransactionId == transactionId },
        )
    }

    /**
     * The counterpart rule must not swallow a refund: same amount, same day, opposite direction is
     * exactly what a refund looks like, so only transfer wording may trigger the fallback.
     */
    @Test
    fun aSameDayRefundIsNotMergedIntoThePurchase() = runBlocking {
        repository.ingest(
            "VM-HDFCBK",
            "Rs 750.00 debited to MYNTRA on 05-03-26.",
            Instant.fromEpochMilliseconds(1_772_000_000_000),
        )
        repository.ingest(
            "VM-HDFCBK",
            "Rs 750.00 credited from MYNTRA on 05-03-26.",
            Instant.fromEpochMilliseconds(1_772_000_600_000),
        )

        val transactions = db.transactionDao().observeAll().first()
        assertEquals("a refund is its own event", 2, transactions.size)
    }

    // --- transfers between the user's own accounts ---

    /** HDFC → ICICI: two banks, two real legs, one movement of money. */
    private val hdfcOut = "Sent Rs.10000.00\nFrom HDFC Bank A/C *3941\nTo ICICI Bank A/C XX4795\n" +
        "On 02/08/26\nRef 512345678901"

    private val iciciIn = "INR 10,000.00 credited to ICICI Bank A/C XX4795 on 02-Aug-26. " +
        "Info: IMPS/512345678901/HDFC BANK. Avl Bal INR 25,000.00"

    @Test
    fun aCrossBankSelfTransferIsPairedAndExcludedFromTotals() = runBlocking {
        repository.ingest("VM-HDFCBK", hdfcOut, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest("AD-ICICIB", iciciIn, Instant.fromEpochMilliseconds(1_785_657_268_000))

        val transactions = db.transactionDao().observeAll().first()
        assertEquals("both legs are kept — each account's history is real", 2, transactions.size)
        assertTrue(
            "the shared UTR should have paired them automatically",
            transactions.all { it.transferGroupId != null },
        )
        assertEquals(
            "both legs belong to the same transfer",
            1,
            transactions.mapNotNull { it.transferGroupId }.distinct().size,
        )

        val start = Instant.fromEpochMilliseconds(0)
        val end = Instant.fromEpochMilliseconds(2_000_000_000_000)
        assertEquals(
            "moving my own money is not spending",
            0L,
            db.transactionDao().observeTotalByDirection(start, end, Direction.DEBIT).first(),
        )
        assertEquals(
            "and it is not income either",
            0L,
            db.transactionDao().observeTotalByDirection(start, end, Direction.CREDIT).first(),
        )
        assertEquals(
            "but it is still visible as a transfer",
            1_000_000L,
            db.transactionDao().observeTransferTotal(start, end).first(),
        )
    }

    @Test
    fun anOrdinaryPurchaseStillCountsAsSpending() = runBlocking {
        repository.ingest(
            "VM-HDFCBK",
            "Rs 450.00 debited to swiggy@icici on 02-08-26. Ref 4432112.",
            Instant.fromEpochMilliseconds(1_785_657_168_000),
        )

        val total = db.transactionDao().observeTotalByDirection(
            Instant.fromEpochMilliseconds(0),
            Instant.fromEpochMilliseconds(2_000_000_000_000),
            Direction.DEBIT,
        ).first()
        assertEquals(45000L, total)
    }

    @Test
    fun unlinkingATransferMakesBothLegsCountAgain() = runBlocking {
        repository.ingest("VM-HDFCBK", hdfcOut, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest("AD-ICICIB", iciciIn, Instant.fromEpochMilliseconds(1_785_657_268_000))

        val groupId = db.transactionDao().observeAll().first().first().transferGroupId!!
        transferRepository.unlink(groupId)

        val transactions = db.transactionDao().observeAll().first()
        assertTrue("undo must fully detach", transactions.all { it.transferGroupId == null })
        assertEquals(
            1_000_000L,
            db.transactionDao().observeTotalByDirection(
                Instant.fromEpochMilliseconds(0),
                Instant.fromEpochMilliseconds(2_000_000_000_000),
                Direction.DEBIT,
            ).first(),
        )
    }

    @Test
    fun claimingAccountsPairsATransferThatHasNoSharedReference() = runBlocking {
        transferRepository.claimAccount("XX1111", "HDFC Savings")
        transferRepository.claimAccount("XX2222", "ICICI Savings")

        repository.ingest(
            "VM-HDFCBK",
            "Rs 5000.00 debited from a/c XX1111 to SELF on 02-08-26.",
            Instant.fromEpochMilliseconds(1_785_657_168_000),
        )
        repository.ingest(
            "AD-ICICIB",
            "Rs 5000.00 credited to a/c XX2222 from SELF on 02-08-26.",
            Instant.fromEpochMilliseconds(1_785_657_268_000),
        )

        val transactions = db.transactionDao().observeAll().first()
        assertEquals(2, transactions.size)
        assertTrue(
            "both accounts being mine is enough to pair without a reference",
            transactions.all { it.transferGroupId != null },
        )
    }

    /** Confirming a queued message must teach a pattern that the *next* message can use. */
    @Test
    fun confirmingAReviewTeachesAPatternThatParsesTheNextMessage() = runBlocking {
        val first = "Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000"
        repository.ingest("AD-ICICIB", first, Instant.fromEpochMilliseconds(1_785_657_168_000))

        val queued = db.rawSmsDao().observeByStatus(ParseStatus.NEEDS_REVIEW).first().single()
        repository.confirmReview(
            rawSms = queued,
            amountMinor = 59900,
            direction = Direction.DEBIT,
            merchant = "NETFLIX.COM",
            categoryId = null,
            accountLabel = "XX12",
        )

        // A later message of the same shape, with a different date, amount, merchant and balance.
        val later = "Purchase of Rs 249.00 on your ICICI Card XX12 at SPOTIFY.COM. Avl Lmt Rs 44751"
        repository.ingest("VM-ICICIB", later, Instant.fromEpochMilliseconds(1_785_757_168_000))

        val transactions = db.transactionDao().observeAll().first()
        assertEquals(2, transactions.size)
        val learned = transactions.first { it.amountMinor == 24900L }
        assertEquals("SPOTIFY.COM", learned.merchant)
        assertEquals(
            "nothing should be left in the review queue",
            0,
            db.rawSmsDao().observeByStatus(ParseStatus.NEEDS_REVIEW).first().size,
        )
    }
}
