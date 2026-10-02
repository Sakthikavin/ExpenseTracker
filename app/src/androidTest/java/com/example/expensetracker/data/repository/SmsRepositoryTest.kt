package com.example.expensetracker.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.expensetracker.data.local.AppDatabase
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.ParseStatus
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.data.remoterules.RemoteRulesApi
import com.example.expensetracker.data.remoterules.RemoteRulesRepository
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

    private lateinit var parser: SmsParser

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        parser = SmsParser(db.learnedPatternDao(), remoteRulesRepository = publishedRules())
        repository = SmsRepository(
            rawSmsDao = db.rawSmsDao(),
            learnedPatternDao = db.learnedPatternDao(),
            transactionRepository = TransactionRepository(db.transactionDao()),
            parser = parser,
        )
    }

    /**
     * The published generic rules, from the copy of the console's `generic-rules.json` that
     * `src/test/resources` holds and the build ships into the instrumentation APK's assets.
     *
     * Needed because the app parses nothing by itself any more: without a rule set these tests
     * would exercise the ingest path with every message falling through to the review queue.
     */
    private fun publishedRules(): RemoteRulesRepository {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("generic-rules.json").bufferedReader().use { it.readText() }
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("sms_repository_test_rules", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("remote_rules_cached_set", json).commit()
        return RemoteRulesRepository(RemoteRulesApi(), prefs)
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

    /** Two payments whose messages differ are two transactions, however close together they land. */
    @Test
    fun twoPaymentsWithDifferentMessagesAreBothKept() = runBlocking {
        repository.ingest("AD-FEDBNK", federalSms, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest(
            "AD-FEDBNK",
            federalSms.replace("621312687340", "621312687341"),
            Instant.fromEpochMilliseconds(1_785_657_999_000),
        )

        assertEquals(2, db.transactionDao().observeAll().first().size)
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
        // The generic rules map no `account` group, so nothing fills the label (§2).
        assertEquals("", apy.accountLabel)

        val upi = transactions.first { it.amountMinor == 5800L }
        assertEquals("Google India Digital Serv", upi.merchant)
    }

    // --- one payment, two messages ---

    private val npsDebit = "UPDATE: INR 5,000.00 debited from HDFC Bank XX3941 on 05-MAR-26. Info: NEFT " +
        "Dr-UTIB0CCH274-SAKTHI KAVIN S S-SANDOZ - MUM-HDFCH00842011992-NET BANKING SI -NPS " +
        "Contribution M. Avl bal:INR 84,966.79"

    private val npsCredit = "HDFC Bank : NEFT money transfer Txn No HDFCH00842011992 for Rs INR 5,000.00 " +
        "has been credited to SAKTHI KAVIN S S on 05-03-2026 at 04:01:54"

    /**
     * Two messages about one payment are two transactions now, and that's the documented behaviour
     * (`PARSING_ARCHITECTURE.md` §7): the debit alert and the NEFT confirmation share a reference,
     * but nothing on the device matches on references any more. Collapsing them is the console's
     * job — a discarded sender, or an ignore rule for the confirmation's wording.
     */
    @Test
    fun theNpsPairIsTwoTransactionsAndTheConsoleResolvesIt() = runBlocking {
        repository.ingest("AD-HDFCBK", npsDebit, Instant.fromEpochMilliseconds(1_772_000_000_000))
        repository.ingest("AD-HDFCBK", npsCredit, Instant.fromEpochMilliseconds(1_772_000_060_000))

        val transactions = db.transactionDao().observeAll().first()
        assertEquals(2, transactions.size)
        assertEquals(
            "both legs are real messages, each kept with its own row",
            2,
            db.rawSmsDao().observeByStatus(ParseStatus.PARSED).first().size,
        )
    }

    /** A refund is its own event: same amount, same day, opposite direction, two transactions. */
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

    // --- what used to be transfer pairing ---

    /** HDFC → ICICI: two banks, two messages, one movement of the user's own money. */
    private val hdfcOut = "Sent Rs.10000.00\nFrom HDFC Bank A/C *3941\nTo ICICI Bank A/C XX4795\n" +
        "On 02/08/26\nRef 512345678901"

    private val iciciIn = "INR 10,000.00 credited to ICICI Bank A/C XX4795 on 02-Aug-26. " +
        "Info: IMPS/512345678901/HDFC BANK. Avl Bal INR 25,000.00"

    /**
     * A self-transfer is now an ordinary debit and an ordinary credit, and both count
     * (`PARSING_ARCHITECTURE.md` §6/§7). This is the user-visible cost of dropping the pairing:
     * a month with a transfer in it reports more spending *and* more income than it used to.
     */
    @Test
    fun aSelfTransferBecomesAnOrdinaryDebitAndCredit() = runBlocking {
        repository.ingest("VM-HDFCBK", hdfcOut, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest("AD-ICICIB", iciciIn, Instant.fromEpochMilliseconds(1_785_657_268_000))

        val transactions = db.transactionDao().observeAll().first()
        assertEquals("both legs are kept — each account's history is real", 2, transactions.size)
        assertTrue("nothing pairs them any more", transactions.all { it.transferGroupId == null })

        val start = Instant.fromEpochMilliseconds(0)
        val end = Instant.fromEpochMilliseconds(2_000_000_000_000)
        assertEquals(
            "the outgoing leg now counts as spending",
            1_000_000L,
            db.transactionDao().observeTotalByDirection(start, end, Direction.DEBIT).first(),
        )
        assertEquals(
            "and the incoming leg as income",
            1_000_000L,
            db.transactionDao().observeTotalByDirection(start, end, Direction.CREDIT).first(),
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

    /**
     * A re-confirmation — a double-tap on Save before the item left the queue, or the item somehow
     * being confirmed a second time — must update the transaction it's already linked to, not
     * insert a second row the raw SMS silently stops pointing at.
     */
    @Test
    fun reConfirmingAnAlreadyLinkedItemUpdatesInsteadOfDuplicating() = runBlocking {
        val body = "Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000"
        repository.ingest("AD-ICICIB", body, Instant.fromEpochMilliseconds(1_785_657_168_000))

        val queued = db.rawSmsDao().observeByStatus(ParseStatus.NEEDS_REVIEW).first().single()
        repository.confirmReview(
            rawSms = queued,
            amountMinor = 59900,
            direction = Direction.DEBIT,
            merchant = "NETFLIX.COM",
            categoryId = null,
            accountLabel = "XX12",
        )

        val confirmedOnce = db.rawSmsDao().observeByStatus(ParseStatus.PARSED).first().single()
        repository.confirmReview(
            rawSms = confirmedOnce,
            amountMinor = 64900,
            direction = Direction.DEBIT,
            merchant = "NETFLIX PREMIUM",
            categoryId = null,
            accountLabel = "XX12",
        )

        val transactions = db.transactionDao().observeAll().first()
        assertEquals("a re-confirmation must update, not duplicate", 1, transactions.size)
        assertEquals(64900L, transactions.single().amountMinor)
        assertEquals("NETFLIX PREMIUM", transactions.single().merchant)
    }

    /**
     * Both messages of a self-transfer arriving again — a bank resending, or a rerun seed script.
     * Each has a different `receivedAt`, so the unique index on `raw_sms` doesn't apply and the
     * body match is the only thing standing between this and four transactions.
     */
    @Test
    fun resendingBothLegsOfATransferCreatesNoNewRows() = runBlocking {
        repository.ingest("VM-HDFCBK", hdfcOut, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest("AD-ICICIB", iciciIn, Instant.fromEpochMilliseconds(1_785_657_268_000))

        // Both legs arrive again later — e.g. a re-run of a test seed script, or a bank resending —
        // each with a different receivedAt, so the exact-redelivery unique index doesn't apply.
        repository.ingest("VM-HDFCBK", hdfcOut, Instant.fromEpochMilliseconds(1_785_657_368_000))
        repository.ingest("AD-ICICIB", iciciIn, Instant.fromEpochMilliseconds(1_785_657_468_000))

        assertEquals("resending both must not create new rows", 2, db.transactionDao().observeAll().first().size)
    }

    /**
     * A plain resend — the same message arriving twice with a different `receivedAt` — must still
     * not be counted twice. Real senders redeliver failed messages; a rerun seed script does the
     * same in testing. This is the one duplicate check left (`findExactBodyResend`), and it works
     * by matching the literal message text against a prior ingest.
     */
    @Test
    fun resendingAnOrdinaryMessageWithNoExtractableReferenceDoesNotDuplicate() = runBlocking {
        val body = "Rs 450.00 debited to SWIGGY on 16-08-26. Ref 778801. -HDFC Bank"
        repository.ingest("HDFCBK", body, Instant.fromEpochMilliseconds(1_785_657_168_000))
        repository.ingest("HDFCBK", body, Instant.fromEpochMilliseconds(1_785_657_368_000))

        val transactions = db.transactionDao().observeAll().first()
        assertEquals("a plain resend must not create a second transaction", 1, transactions.size)
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

    // --- permanent noise filters ---

    /** The confirmation-only senders never reach the review queue, but stay in raw_sms for audit. */
    @Test
    fun aConfirmationOnlySenderIsIgnoredButKeptForAudit() = runBlocking {
        val result = repository.ingest(
            "AD-NPSCRA",
            "Rs 5000.00 debited towards NPS contribution. Ref 12345.",
            Instant.fromEpochMilliseconds(1_785_657_168_000),
        )

        assertEquals(IngestResult.IGNORED, result)
        assertEquals(0, db.transactionDao().observeAll().first().size)
        assertEquals(
            "nothing should reach the review queue",
            0,
            db.rawSmsDao().observeByStatus(ParseStatus.NEEDS_REVIEW).first().size,
        )
        assertEquals(
            "the message must still be visible in raw_sms, just under IGNORED",
            1,
            db.rawSmsDao().observeByStatus(ParseStatus.IGNORED).first().size,
        )
    }

    /** A rotating sender prefix ("AD-", "VM-") must not defeat the normalised-sender match. */
    @Test
    fun theConfirmationOnlyFilterSurvivesARotatingSenderPrefix() = runBlocking {
        val result = repository.ingest(
            "VM-AXISMF-S",
            "Rs 2000.00 debited for mutual fund purchase. Ref 998877.",
            Instant.fromEpochMilliseconds(1_785_657_168_000),
        )

        assertEquals(IngestResult.IGNORED, result)
    }

    /** A mandate reminder and a bill-due nudge share no sender, only the future-tense wording. */
    @Test
    fun futureTenseMandateAndBillWordingAreIgnoredRegardlessOfSender() = runBlocking {
        val mandateResult = repository.ingest(
            "AD-NACHBK",
            "Your ECS mandate of Rs 999.00 will be debited on 05-09-26. To stop execution, contact your bank.",
            Instant.fromEpochMilliseconds(1_785_657_168_000),
        )
        val billResult = repository.ingest(
            "VM-CCBILL",
            "Your credit card bill of Rs 4500.00 is due for payment by 10-09-26.",
            Instant.fromEpochMilliseconds(1_785_657_268_000),
        )

        assertEquals("mandate notice", IngestResult.IGNORED, mandateResult)
        assertEquals("bill reminder", IngestResult.IGNORED, billResult)
        assertEquals(
            "neither should reach the review queue",
            0,
            db.rawSmsDao().observeByStatus(ParseStatus.NEEDS_REVIEW).first().size,
        )
    }

    /** A message that also states a debit already happened must not be swallowed by the tense filter. */
    @Test
    fun futureTenseWordingWithAPastTenseConfirmationStillReachesReview() = runBlocking {
        val result = repository.ingest(
            "AD-EMIBNK",
            "Last EMI of Rs 500.00 was debited on 01-08-26. Next EMI of Rs 500.00 will be debited on 01-09-26.",
            Instant.fromEpochMilliseconds(1_785_657_168_000),
        )

        assertEquals(IngestResult.NEEDS_REVIEW, result)
        assertEquals(1, db.rawSmsDao().observeByStatus(ParseStatus.NEEDS_REVIEW).first().size)
    }

    /** A verification ping under the amount floor is noise; the same shape above it is real. */
    @Test
    fun amountsBelowTheIgnoreThresholdAreFilteredButNotOnesAboveIt() = runBlocking {
        val pingResult = repository.ingest(
            "AD-HDFCBK",
            "Received! INR 1.00 in HDFC Bank A/c xx3941",
            Instant.fromEpochMilliseconds(1_785_657_168_000),
        )
        val realResult = repository.ingest(
            "AD-HDFCBK",
            "Received! INR 5.00 in HDFC Bank A/c xx3941",
            Instant.fromEpochMilliseconds(1_785_657_268_000),
        )

        assertEquals("a ₹1 verification ping is below the default ₹2 floor", IngestResult.IGNORED, pingResult)
        assertEquals("₹5 clears the floor and still needs review", IngestResult.NEEDS_REVIEW, realResult)
    }

    // --- re-parsing the review queue after a rules pull (§8.1) ---

    /** Financial enough to sit in review, structurally unmatched by any template, so it stays put. */
    private fun filler(index: Int) =
        "Update: transfer of Rs 999 was processed successfully, ref unavailable. #$index"

    private suspend fun queue(body: String, receivedAt: Long, sender: String = "AD-FILLER") =
        db.rawSmsDao().insert(
            RawSmsEntity(
                sender = sender,
                body = body,
                receivedAt = Instant.fromEpochMilliseconds(receivedAt),
                parseStatus = ParseStatus.NEEDS_REVIEW,
            ),
        )

    /**
     * The stuck-backlog bug: a re-parse capped at the newest 200 rows never re-reads anything older,
     * so a rule published later can't ever clear it. Here the one parseable message is the *oldest*
     * of 250.
     */
    @Test
    fun reparseReachesTheOldestRowOfALongQueue() = runBlocking {
        val base = 1_785_000_000_000
        val oldestId = queue(federalSms, base, sender = "AD-FEDBNK")
        repeat(249) { queue(filler(it), base + (it + 1) * 60_000L) }

        val outcome = repository.reparseNeedsReview()

        assertEquals("the whole queue must be re-read, not the newest 200", 250, outcome.checked)
        assertEquals(1, outcome.cleared)
        val oldest = db.rawSmsDao().getById(oldestId)!!
        assertEquals(ParseStatus.PARSED, oldest.parseStatus)
        assertNotNull("the cleared row must point at the transaction it produced", oldest.linkedTransactionId)
    }

    /**
     * Rows leave NEEDS_REVIEW as the walk clears them, so paging by `OFFSET` would step over that
     * many rows it never looked at. Every fourth row here is parseable — spread across ten pages,
     * an offset-paged walk would miss most of them.
     */
    @Test
    fun reparsePagesWithoutSkippingRowsThatClearMidWalk() = runBlocking {
        val paged = SmsRepository(
            rawSmsDao = db.rawSmsDao(),
            learnedPatternDao = db.learnedPatternDao(),
            transactionRepository = TransactionRepository(db.transactionDao()),
            parser = parser,
            reparsePageSize = 25,
        )
        val base = 1_785_000_000_000
        repeat(100) { index ->
            // Distinct references: same-reference messages are one payment, and would be merged.
            val body = if (index % 4 == 0) federalSms.replace("621312687340", "62131268%04d".format(index)) else filler(index)
            queue(body, base + index * 60_000L, sender = if (index % 4 == 0) "AD-FEDBNK" else "AD-FILLER")
        }

        val outcome = paged.reparseNeedsReview()

        assertEquals(100, outcome.checked)
        assertEquals("every parseable row must be found, whichever page it sat on", 25, outcome.cleared)
        assertEquals(75, db.rawSmsDao().observeByStatus(ParseStatus.NEEDS_REVIEW).first().size)
    }

    // --- one-time backlog cleanup ---

    /** Rows queued before the permanent sender filter existed must be movable in one pass. */
    @Test
    fun cleanupMovesExistingBacklogRowsFromNoisySendersOutOfReview() = runBlocking {
        db.rawSmsDao().insert(
            RawSmsEntity(
                sender = "AD-NPSCRA",
                body = "Rs 5000.00 debited towards NPS contribution. Ref 55555.",
                receivedAt = Instant.fromEpochMilliseconds(1_785_657_168_000),
                parseStatus = ParseStatus.NEEDS_REVIEW,
            ),
        )
        db.rawSmsDao().insert(
            RawSmsEntity(
                sender = "AD-ICICIB",
                body = "Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000",
                receivedAt = Instant.fromEpochMilliseconds(1_785_657_268_000),
                parseStatus = ParseStatus.NEEDS_REVIEW,
            ),
        )

        val moved = repository.ignoreConfirmationOnlyBacklog()

        assertEquals(1, moved)
        assertEquals(1, db.rawSmsDao().observeByStatus(ParseStatus.NEEDS_REVIEW).first().size)
        assertEquals(1, db.rawSmsDao().observeByStatus(ParseStatus.IGNORED).first().size)

        // Safe to run twice: nothing left from those senders to move a second time.
        assertEquals(0, repository.ignoreConfirmationOnlyBacklog())
    }

    // --- skipped messages: what the heuristic turned away (Canara `Dr.`) ---

    /**
     * A message that mentions money but matches nothing keeps a row now. It used to keep none,
     * which is how a Canara alert reading `Dr.` instead of "debited" went missing entirely: absent
     * from transactions, from review, and from any list the user could look at.
     */
    @Test
    fun aMessageMentioningMoneyThatMatchesNothingIsKeptAsSkipped() = runBlocking {
        val body = "Reminder: your order of Rs 1,299 is out for delivery. Track it in the app."

        val result = repository.ingest("AD-SOMEBK", body, Instant.fromEpochMilliseconds(1_785_657_168_000))

        assertEquals(IngestResult.DISCARDED, result)
        val skipped = db.rawSmsDao().observeByStatus(ParseStatus.DISCARDED).first()
        assertEquals(1, skipped.size)
        assertEquals(body, skipped.single().body)
        assertTrue("a skipped message must not become a transaction", db.transactionDao().observeAll().first().isEmpty())
    }

    /** The cap on the bucket: the rest of the inbox must not turn into rows. */
    @Test
    fun aMessageWithNoAmountAtAllIsNotStored() = runBlocking {
        val result = repository.ingest("VM-AMAZON", "Your order has been delivered.", Instant.fromEpochMilliseconds(1))

        assertEquals(IngestResult.IGNORED, result)
        assertEquals(0, db.rawSmsDao().observeByStatus(ParseStatus.DISCARDED).first().size)
    }

    /** Diagnostics, not history: the bucket is capped, oldest out first. */
    @Test
    fun theSkippedBucketKeepsOnlyItsNewestRows() = runBlocking {
        val capped = SmsRepository(
            rawSmsDao = db.rawSmsDao(),
            learnedPatternDao = db.learnedPatternDao(),
            transactionRepository = TransactionRepository(db.transactionDao()),
            parser = parser,
            discardedKeep = 3,
        )
        repeat(5) { index ->
            capped.ingest(
                "AD-SOMEBK",
                "Reminder: your order of Rs 1,29$index is out for delivery.",
                Instant.fromEpochMilliseconds(1_785_657_168_000 + index * 1_000L),
            )
        }

        val skipped = db.rawSmsDao().observeByStatus(ParseStatus.DISCARDED).first()
        assertEquals(3, skipped.size)
        // observeByStatus is newest-first, so the survivors are the last three ingested.
        assertTrue(skipped.first().body.endsWith("Rs 1,294 is out for delivery."))
        assertTrue(skipped.last().body.endsWith("Rs 1,292 is out for delivery."))
    }

    /**
     * The bucket must not be a dead end. A rule published later can read a message the heuristic
     * didn't think was financial at all, and the re-parse walk has to reach it — otherwise skipped
     * is the one status no rule could ever rescue.
     */
    @Test
    fun reparseReachesSkippedMessagesAndPromotesWhatItCanRead() = runBlocking {
        val body = "Reminder: your order of Rs 1,299 is out for delivery. Track it in the app."
        repository.ingest("AD-SOMEBK", body, Instant.fromEpochMilliseconds(1_785_657_168_000))
        assertEquals(1, db.rawSmsDao().observeByStatus(ParseStatus.DISCARDED).first().size)

        val outcome = repository.reparseNeedsReview()

        assertEquals("the walk must look at the skipped row", 1, outcome.checked)
        // Nothing new parses it, so it stays put rather than being quietly dropped again.
        assertEquals(0, outcome.cleared)
        assertEquals(1, db.rawSmsDao().observeByStatus(ParseStatus.DISCARDED).first().size)
    }

    /** The manual escape hatch, for when you can see the heuristic was wrong. */
    @Test
    fun aSkippedMessageCanBeMovedToReviewByHand() = runBlocking {
        val body = "Reminder: your order of Rs 1,299 is out for delivery. Track it in the app."
        repository.ingest("AD-SOMEBK", body, Instant.fromEpochMilliseconds(1_785_657_168_000))
        val skipped = db.rawSmsDao().observeByStatus(ParseStatus.DISCARDED).first().single()

        repository.moveToReview(skipped)

        assertEquals(0, db.rawSmsDao().observeByStatus(ParseStatus.DISCARDED).first().size)
        assertEquals(1, repository.observeNeedsReview().first().size)
    }
}
