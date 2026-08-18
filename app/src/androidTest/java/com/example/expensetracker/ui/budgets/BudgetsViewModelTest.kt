package com.example.expensetracker.ui.budgets

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.expensetracker.data.local.AppDatabase
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.repository.BudgetRepository
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BudgetsScreen decides "Needs attention" vs. "No budget set" purely off `BudgetRow.budget`
 * being null, so proving removal clears it back to null is what proves the category reappears.
 */
@RunWith(AndroidJUnit4::class)
class BudgetsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var viewModel: BudgetsViewModel
    private var categoryId: Long = 0

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val categoryRepository = CategoryRepository(db.categoryDao())
        val budgetRepository = BudgetRepository(db.budgetDao())
        val transactionRepository = TransactionRepository(db.transactionDao())

        categoryId = categoryRepository.create(
            CategoryEntity(name = "Groceries", icon = "🛒", colour = 0xFF00FF00),
        )
        viewModel = BudgetsViewModel(budgetRepository, categoryRepository, transactionRepository)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun settingThenRemovingABudgetSendsTheCategoryBackToNoBudgetSet() = runBlocking {
        val initial = viewModel.rows.first { it.isNotEmpty() }
        assertNull("starts with no budget", initial.single().budget)

        viewModel.setBudget(categoryId, 500_000)
        val afterSet = viewModel.rows.first { it.single().budget != null }
        assertEquals(500_000L, afterSet.single().budget!!.monthlyLimitMinor)

        viewModel.removeBudget(categoryId)
        val afterRemove = viewModel.rows.first { it.single().budget == null }
        assertNull("category must reappear under No budget set", afterRemove.single().budget)
    }
}
