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
import com.example.expensetracker.data.local.dao.OwnAccountDao
import com.example.expensetracker.data.local.dao.RawSmsDao
import com.example.expensetracker.data.local.dao.TransactionDao
import com.example.expensetracker.data.local.entity.BillEntity
import com.example.expensetracker.data.local.entity.BudgetEntity
import com.example.expensetracker.data.local.entity.CategoryEntity
import com.example.expensetracker.data.local.entity.LearnedPatternEntity
import com.example.expensetracker.data.local.entity.OwnAccountEntity
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
        OwnAccountEntity::class,
    ],
    version = 4,
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
    abstract fun ownAccountDao(): OwnAccountDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "expense_tracker.db")
                .addCallback(SeedCallback(context.applicationContext))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

        // Colours are the validated 8-slot categorical palette from UX_REDESIGN_PLAN.md, assigned
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
        )
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