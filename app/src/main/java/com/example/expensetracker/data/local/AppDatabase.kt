package com.example.expensetracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.expensetracker.data.local.dao.BillDao
import com.example.expensetracker.data.local.dao.BudgetDao
import com.example.expensetracker.data.local.dao.CategoryDao
import com.example.expensetracker.data.local.dao.LearnedPatternDao
import com.example.expensetracker.data.local.dao.MerchantCategoryRuleDao
import com.example.expensetracker.data.local.dao.RawSmsDao
import com.example.expensetracker.data.local.dao.TransactionDao
import com.example.expensetracker.data.local.entity.BillEntity
import com.example.expensetracker.data.local.entity.BudgetEntity
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.LearnedPatternEntity
import com.example.expensetracker.data.local.entity.MerchantCategoryRuleEntity
import com.example.expensetracker.data.local.entity.RawSmsEntity
import com.example.expensetracker.data.local.entity.TransactionEntity
import com.example.expensetracker.data.local.entity.UNASSIGNED_CATEGORY_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
        BillEntity::class,
        RawSmsEntity::class,
        LearnedPatternEntity::class,
        MerchantCategoryRuleEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun billDao(): BillDao
    abstract fun rawSmsDao(): RawSmsDao
    abstract fun learnedPatternDao(): LearnedPatternDao
    abstract fun merchantCategoryRuleDao(): MerchantCategoryRuleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "expense_tracker.db")
                .addCallback(SeedCallback(context.applicationContext))
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8,
                )
                .build()

        /**
         * v1 → v2: records the confirmed direction on learned patterns, and makes duplicate SMS
         * ingestion impossible.
         *
         * No destructive fallback anywhere in this class — the app is sideloaded and the database
         * holds the user's only copy of their transactions.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE learned_patterns ADD COLUMN direction TEXT NOT NULL DEFAULT 'DEBIT'",
                )

                // Existing duplicates must go first: CREATE UNIQUE INDEX fails outright if the
                // table already violates it, which would leave the upgrade dead in the water.
                db.execSQL(
                    """
                    DELETE FROM raw_sms
                    WHERE id NOT IN (
                        SELECT MIN(id) FROM raw_sms GROUP BY sender, body, receivedAt
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_raw_sms_sender_body_receivedAt " +
                        "ON raw_sms (sender, body, receivedAt)",
                )
            }
        }

        /**
         * v2 → v3: records the bank's transaction reference so the two messages a bank sends for
         * one transfer can be recognised as the same payment.
         *
         * The index is deliberately non-unique: most transactions have no reference at all, and
         * SQLite treats every NULL as distinct anyway.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN referenceId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_referenceId " +
                        "ON transactions (referenceId)",
                )
            }
        }

        /**
         * v3 → v4: transfers between the user's own accounts.
         *
         * `transferGroupId` links the two legs; `own_accounts` records which masked labels belong
         * to the user, which is what lets a transfer be recognised in the first place.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN transferGroupId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_transferGroupId " +
                        "ON transactions (transferGroupId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS own_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label TEXT NOT NULL,
                        nickname TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_own_accounts_label ON own_accounts (label)",
                )
            }
        }

        /**
         * v4 → v5: "learn as you categorize" merchant rules.
         *
         * `categoryId`'s foreign key is CASCADE — unlike `transactions.categoryId`'s SET_NULL — a
         * rule pointing at a deleted category is a dangling pointer with no reason to survive.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS merchant_category_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        householdId INTEGER NOT NULL,
                        merchantKey TEXT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        displayName TEXT,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_category_rules_merchantKey " +
                        "ON merchant_category_rules (merchantKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_merchant_category_rules_categoryId " +
                        "ON merchant_category_rules (categoryId)",
                )
            }
        }

        /** v5 → v6: remembers that a message's template was already sent off for a rule. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE raw_sms ADD COLUMN submittedAt INTEGER")
            }
        }

        /**
         * v6 → v7: gives existing installs the categories added after the first release. The seed
         * callback can't: Room runs it only when the database file is created, so a phone that
         * already has a database would never see a new entry in [DEFAULT_CATEGORIES].
         *
         * Each insert is conditional on the name not already being present. `categories.name` has
         * no unique index and `CategoryDao.insert` aborts on conflict, so anyone who added
         * "Travel & Holidays" by hand before upgrading would otherwise end up with two — and two
         * categories with one name behave as separate categories everywhere: budgets, merchant
         * rules, every dashboard slice. This way the upgrade is safe whatever the user already did,
         * and safe to re-run.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                CATEGORIES_ADDED_IN_V7.forEach { category ->
                    db.execSQL(
                        "INSERT INTO categories (householdId, name, icon, colour, isIncome) " +
                            "SELECT ?, ?, ?, ?, ? " +
                            "WHERE NOT EXISTS (SELECT 1 FROM categories WHERE name = ?)",
                        arrayOf(
                            category.householdId,
                            category.name,
                            category.icon,
                            category.colour,
                            if (category.isIncome) 1 else 0,
                            category.name,
                        ),
                    )
                }
            }
        }

        /**
         * v7 → v8: parsing moved to one published rule list, so the app no longer pairs transfers
         * or tracks which accounts are the user's (`PARSING_ARCHITECTURE.md` §6).
         *
         * `own_accounts` goes, and every pairing made so far is undone. The columns stay: dropping
         * one in SQLite means rebuilding the table, which isn't worth it for two nullable columns
         * nothing reads any more.
         *
         * This changes what the user sees. A transfer between their own accounts was one row
         * counting as neither spending nor income; its two legs are now an ordinary debit and an
         * ordinary credit, so both totals rise for any month that had one. Nothing is deleted —
         * both legs were always stored — so the rows can be removed by hand if it matters.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE transactions SET transferGroupId = NULL")
                db.execSQL("DROP TABLE IF EXISTS own_accounts")
            }
        }

        /**
         * Added after the first release, so they reach existing installs through [MIGRATION_6_7]
         * rather than the seed — [SeedCallback] only runs when the database file is created.
         *
         * Why each one exists: an EMI is not a utility bill, insurance is neither a bill nor an
         * asset, a flight is not the daily commute, and a subscription is the thing people most
         * want to audit separately from entertainment. `Cash withdrawal` takes the neutral grey
         * Transfers uses, for the same reason: money at an ATM hasn't been spent yet, it's spending
         * not yet recorded, and colouring it like spending double-counts it by eye.
         *
         * The income side is the bigger gap being closed here — one income category (Salary) meant
         * every other credit had nowhere to go, so bank interest and a refund both had to land in
         * "Salary" and corrupt any read of what's actually earned. The three income categories
         * share Salary's green deliberately: the hue means income, and the icon tells them apart.
         */
        val CATEGORIES_ADDED_IN_V7: List<CategoryEntity> = listOf(
            CategoryEntity(name = "Loans & EMI", icon = "credit_card", colour = 0xFF2A78D6L),
            CategoryEntity(name = "Insurance", icon = "security", colour = 0xFFEB6834L),
            CategoryEntity(name = "Education & Fees", icon = "school", colour = 0xFFEDA100L),
            CategoryEntity(name = "Travel & Holidays", icon = "flight", colour = 0xFFE87BA4L),
            CategoryEntity(name = "Subscriptions", icon = "autorenew", colour = 0xFF008300L),
            CategoryEntity(name = "Family & Gifts", icon = "card_giftcard", colour = 0xFF4A3AA7L),
            CategoryEntity(name = "Taxes & Government", icon = "account_balance", colour = 0xFFE34948L),
            CategoryEntity(name = "Cash withdrawal", icon = "local_atm", colour = 0xFF78909CL),
            CategoryEntity(name = "Interest & Dividends", icon = "savings", colour = 0xFF1BAF7AL, isIncome = true),
            CategoryEntity(name = "Refunds & Cashback", icon = "replay", colour = 0xFF1BAF7AL, isIncome = true),
            CategoryEntity(name = "Other income", icon = "payments", colour = 0xFF1BAF7AL, isIncome = true),
        )

        // Colours are the validated 8-slot categorical palette, assigned
        // by category identity (slot order: Bills & Utilities, Investments, Unassigned, Groceries,
        // Entertainment, Health, Food & Dining, Transport). Categories beyond that table cycle back
        // starting at slot 1, paired with their own distinct icon so hue is never the sole signal.
        val DEFAULT_CATEGORIES: List<CategoryEntity> = listOf(
            CategoryEntity(name = "Food & Dining", icon = "restaurant", colour = 0xFF4A3AA7L),
            CategoryEntity(name = "Groceries", icon = "grocery", colour = 0xFFEDA100L),
            CategoryEntity(name = "Transport", icon = "directions_car", colour = 0xFFE34948L),
            CategoryEntity(name = "Shopping", icon = "shopping_cart", colour = 0xFF2A78D6L), // cycled
            CategoryEntity(name = "Bills & Utilities", icon = "receipt", colour = 0xFF2A78D6L),
            CategoryEntity(name = "Entertainment", icon = "movie", colour = 0xFFE87BA4L),
            CategoryEntity(name = "Health", icon = "local_hospital", colour = 0xFF008300L),
            CategoryEntity(name = "Rent & Housing", icon = "home", colour = 0xFFEB6834L), // cycled
            CategoryEntity(name = "Investments", icon = "trending_up", colour = 0xFFEB6834L),
            // Money moved between your own accounts rather than spent — an NPS contribution, a
            // self-transfer. Deliberately neutral in colour so it reads as "not really spend".
            CategoryEntity(name = "Transfers", icon = "swap_horiz", colour = 0xFF78909CL),
            CategoryEntity(name = "Salary", icon = "attach_money", colour = 0xFF1BAF7AL, isIncome = true), // cycled
        ) + CATEGORIES_ADDED_IN_V7

    }

    private class SeedCallback(private val context: Context) : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val dao = getInstance(context).categoryDao()
                dao.insert(
                    CategoryEntity(
                        name = UNASSIGNED_CATEGORY_NAME,
                        icon = "category",
                        colour = 0xFF9E9E9EL,
                        isIncome = false,
                    ),
                )
                DEFAULT_CATEGORIES.forEach { dao.insert(it) }
            }
        }
    }
}