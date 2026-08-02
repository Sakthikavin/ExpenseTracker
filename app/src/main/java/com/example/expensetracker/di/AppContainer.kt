package com.example.expensetracker.di

import android.content.Context
import com.example.expensetracker.data.local.AppDatabase
import com.example.expensetracker.data.repository.BillRepository
import com.example.expensetracker.data.repository.BudgetRepository
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.SmsRepository
import com.example.expensetracker.data.repository.TransactionRepository
import com.example.expensetracker.data.repository.TransferRepository
import com.example.expensetracker.data.sms.SmsParser

/**
 * Manual dependency injection: a single container built once in [com.example.expensetracker.ExpenseTrackerApp]
 * and handed to whatever needs it (ViewModels via a factory, the SMS receiver, WorkManager
 * workers). Kept manual rather than Hilt/Dagger to avoid an annotation-processor dependency
 * before there's a reason for one.
 */
class AppContainer(context: Context) {
    private val database: AppDatabase = AppDatabase.getInstance(context)

    val categoryRepository = CategoryRepository(database.categoryDao())
    val transactionRepository = TransactionRepository(database.transactionDao())
    val budgetRepository = BudgetRepository(database.budgetDao())
    val billRepository = BillRepository(database.billDao())

    val transferRepository = TransferRepository(
        transactionDao = database.transactionDao(),
        ownAccountDao = database.ownAccountDao(),
        categoryDao = database.categoryDao(),
    )

    private val smsParser = SmsParser(database.learnedPatternDao())
    val smsRepository = SmsRepository(
        rawSmsDao = database.rawSmsDao(),
        learnedPatternDao = database.learnedPatternDao(),
        transactionRepository = transactionRepository,
        parser = smsParser,
        transferRepository = transferRepository,
    )
}