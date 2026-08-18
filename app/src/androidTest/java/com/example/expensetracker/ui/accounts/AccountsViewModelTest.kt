package com.example.expensetracker.ui.accounts

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.expensetracker.data.local.AppDatabase
import com.example.expensetracker.data.local.entity.Direction
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.data.local.entity.TransactionSource
import com.example.expensetracker.data.repository.TransferRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TransferRepository
    private lateinit var viewModel: AccountsViewModel

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = TransferRepository(db.transactionDao(), db.ownAccountDao(), db.categoryDao())
        viewModel = AccountsViewModel(repository)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun deletingAGarbageClaimRemovesItFromTheListEntirely() = runBlocking {
        repository.claimAccount("XX0000", nickname = "Junk")
        val beforeDelete = viewModel.accounts.first { rows -> rows.any { it.label == "XX0000" } }
        assertTrue(beforeDelete.single { it.label == "XX0000" }.isOwned)

        viewModel.delete("XX0000")

        val afterDelete = viewModel.accounts.first { rows -> rows.none { it.label == "XX0000" } }
        assertTrue("label must be gone entirely, not just unowned", afterDelete.none { it.label == "XX0000" })
    }

    @Test
    fun deletingALabelWithTransactionHistoryUnclaimsButKeepsItInTheList() = runBlocking {
        db.transactionDao().insert(
            TransactionEntity(
                amountMinor = 10_000,
                direction = Direction.DEBIT,
                occurredAt = Instant.parse("2026-08-01T00:00:00Z"),
                merchant = "Test",
                accountLabel = "XX1234",
                categoryId = null,
                source = TransactionSource.MANUAL,
            ),
        )
        repository.claimAccount("XX1234", nickname = "HDFC")
        val beforeDelete = viewModel.accounts.first { rows -> rows.any { it.label == "XX1234" && it.isOwned } }
        assertTrue(beforeDelete.single { it.label == "XX1234" }.hasTransactionHistory)

        viewModel.delete("XX1234")

        val afterDelete = viewModel.accounts.first { rows ->
            rows.any { it.label == "XX1234" && !it.isOwned }
        }
        val row = afterDelete.single { it.label == "XX1234" }
        assertFalse("ownership should be cleared", row.isOwned)
        assertTrue("still shown because a real transaction used this label", row.hasTransactionHistory)
    }
}
