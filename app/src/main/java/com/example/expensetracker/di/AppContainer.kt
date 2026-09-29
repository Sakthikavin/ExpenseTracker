package com.example.expensetracker.di

import android.content.Context
import com.example.expensetracker.BuildConfig
import com.example.expensetracker.data.local.AppDatabase
import com.example.expensetracker.data.remoterules.RemoteRulesApi
import com.example.expensetracker.data.remoterules.RemoteRulesRepository
import com.example.expensetracker.data.repository.BillRepository
import com.example.expensetracker.data.repository.BudgetRepository
import com.example.expensetracker.data.repository.CategoryRepository
import com.example.expensetracker.data.repository.MerchantCategoryRuleRepository
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
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val categoryRepository = CategoryRepository(database.categoryDao())
    val transactionRepository = TransactionRepository(database.transactionDao())
    val budgetRepository = BudgetRepository(database.budgetDao())
    val billRepository = BillRepository(database.billDao())

    val transferRepository = TransferRepository(
        transactionDao = database.transactionDao(),
        ownAccountDao = database.ownAccountDao(),
        categoryDao = database.categoryDao(),
    )

    val merchantCategoryRuleRepository = MerchantCategoryRuleRepository(
        ruleDao = database.merchantCategoryRuleDao(),
        rawSmsDao = database.rawSmsDao(),
        transactionRepository = transactionRepository,
    )

    val remoteRulesRepository = RemoteRulesRepository(
        api = RemoteRulesApi(
            projectId = BuildConfig.FIRESTORE_PROJECT_ID,
            baseUrl = BuildConfig.FIRESTORE_BASE_URL,
        ),
        prefs = prefs,
    )

    private val smsParser = SmsParser(
        learnedPatternDao = database.learnedPatternDao(),
        remoteRulesRepository = remoteRulesRepository,
        ignoreBelowMinor = { prefs.getLong(SmsParser.PREF_IGNORE_BELOW_MINOR, SmsParser.DEFAULT_IGNORE_BELOW_MINOR) },
    )
    val smsRepository = SmsRepository(
        rawSmsDao = database.rawSmsDao(),
        learnedPatternDao = database.learnedPatternDao(),
        transactionRepository = transactionRepository,
        parser = smsParser,
        transferRepository = transferRepository,
        merchantCategoryRuleRepository = merchantCategoryRuleRepository,
    )
}