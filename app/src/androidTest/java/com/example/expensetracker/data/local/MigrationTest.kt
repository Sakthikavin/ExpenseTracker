package com.example.expensetracker.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app is sideloaded and the database holds the user's only copy of their transactions, so the
 * v1 → v2 upgrade has to be proven rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_keepsExistingDataAndAddsDirection() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO raw_sms (sender, body, receivedAt, parseStatus) " +
                    "VALUES ('AD-FEDBNK', 'Debited Rs 1.00 to X', 1000, 'PARSED')",
            )
            db.execSQL(
                "INSERT INTO learned_patterns (senderPattern, regex, fieldMap, confirmedCount) " +
                    "VALUES ('FEDBNK', 'x', 'amount:1', 3)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)

        db.query("SELECT sender, body FROM raw_sms").use { cursor ->
            assertEquals("the user's message must survive the upgrade", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("AD-FEDBNK", cursor.getString(0))
        }
        db.query("SELECT direction, confirmedCount FROM learned_patterns").use { cursor ->
            cursor.moveToFirst()
            assertEquals("pre-existing patterns default to DEBIT", "DEBIT", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
        }
    }

    @Test
    fun migrate1To2_clearsDuplicatesBeforeAddingTheUniqueIndex() {
        // v1 had no dedup guard, so a redelivered broadcast could already have been stored twice.
        // CREATE UNIQUE INDEX fails outright on such a table, which would brick the upgrade.
        helper.createDatabase(dbName, 1).use { db ->
            repeat(3) {
                db.execSQL(
                    "INSERT INTO raw_sms (sender, body, receivedAt, parseStatus) " +
                        "VALUES ('AD-FEDBNK', 'Debited Rs 1.00 to X', 1000, 'PARSED')",
                )
            }
            db.execSQL(
                "INSERT INTO raw_sms (sender, body, receivedAt, parseStatus) " +
                    "VALUES ('AD-FEDBNK', 'Debited Rs 2.00 to Y', 2000, 'PARSED')",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)

        db.query("SELECT COUNT(*) FROM raw_sms").use { cursor ->
            cursor.moveToFirst()
            assertEquals("duplicates collapse to one row each", 2, cursor.getInt(0))
        }
        db.query("SELECT MIN(id) FROM raw_sms").use { cursor ->
            cursor.moveToFirst()
            assertEquals("the earliest row is the one kept", 1, cursor.getInt(0))
        }

        // And the index now actually prevents a repeat.
        val threw = runCatching {
            db.execSQL(
                "INSERT INTO raw_sms (sender, body, receivedAt, parseStatus) " +
                    "VALUES ('AD-FEDBNK', 'Debited Rs 1.00 to X', 1000, 'PARSED')",
            )
        }.isFailure
        assertTrue("the unique index must reject a duplicate", threw)
    }

    @Test
    fun migrate2To3_addsReferenceIdAndKeepsTransactions() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                "INSERT INTO transactions (householdId, userId, amountMinor, direction, occurredAt, " +
                    "merchant, accountLabel, categoryId, note, tags, source, isPrivate) " +
                    "VALUES (1, 1, 45000, 'DEBIT', 1000, 'SWIGGY', 'X1', NULL, '', '', 'SMS', 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)

        db.query("SELECT merchant, referenceId FROM transactions").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("SWIGGY", cursor.getString(0))
            assertTrue("pre-existing rows have no reference", cursor.isNull(1))
        }
    }

    @Test
    fun migrate3To4_addsTransferColumnsAndAccountsTable() {
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                "INSERT INTO transactions (householdId, userId, amountMinor, direction, occurredAt, " +
                    "merchant, accountLabel, categoryId, note, tags, source, isPrivate) " +
                    "VALUES (1, 1, 45000, 'DEBIT', 1000, 'SWIGGY', 'X1', NULL, '', '', 'SMS', 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)

        db.query("SELECT merchant, transferGroupId FROM transactions").use { cursor ->
            cursor.moveToFirst()
            assertEquals("SWIGGY", cursor.getString(0))
            assertTrue("existing rows are not transfers", cursor.isNull(1))
        }
        db.query("SELECT COUNT(*) FROM own_accounts").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migrate4To5_addsMerchantCategoryRulesTable() {
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                "INSERT INTO categories (householdId, name, icon, colour, isIncome) " +
                    "VALUES (1, 'Groceries', 'grocery', 16737996, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)

        db.execSQL(
            "INSERT INTO merchant_category_rules (householdId, merchantKey, categoryId, displayName, updatedAt) " +
                "VALUES (1, 'BIGBASKET', 1, NULL, 1000)",
        )
        db.query("SELECT merchantKey, categoryId, displayName FROM merchant_category_rules").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("BIGBASKET", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertTrue("no display name set yet", cursor.isNull(2))
        }

        // The unique index rejects a second rule for the same merchant.
        val threw = runCatching {
            db.execSQL(
                "INSERT INTO merchant_category_rules (householdId, merchantKey, categoryId, updatedAt) " +
                    "VALUES (1, 'BIGBASKET', 1, 2000)",
            )
        }.isFailure
        assertTrue("the unique index must reject a duplicate merchantKey", threw)
    }

    @Test
    fun migrate5To6_addsSubmittedAtAndKeepsQueuedMessages() {
        helper.createDatabase(dbName, 5).use { db ->
            db.execSQL(
                "INSERT INTO raw_sms (sender, body, receivedAt, parseStatus) " +
                    "VALUES ('AD-AXISBK', 'Your A/c has been debited towards Google Play', 1000, 'NEEDS_REVIEW')",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)

        db.query("SELECT sender, submittedAt FROM raw_sms").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("AD-AXISBK", cursor.getString(0))
            assertTrue("a message queued before this column has never been submitted", cursor.isNull(1))
        }
    }

    /** The path a phone still on the original release actually takes. */
    @Test
    fun migrate1To3_runsBothStepsInOrder() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO raw_sms (sender, body, receivedAt, parseStatus) " +
                    "VALUES ('AD-FEDBNK', 'Debited Rs 1.00 to X', 1000, 'PARSED')",
            )
            db.execSQL(
                "INSERT INTO transactions (householdId, userId, amountMinor, direction, occurredAt, " +
                    "merchant, accountLabel, categoryId, note, tags, source, isPrivate) " +
                    "VALUES (1, 1, 100, 'DEBIT', 1000, 'X', '', NULL, '', '', 'SMS', 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
        )

        db.query("SELECT COUNT(*) FROM transactions").use { cursor ->
            cursor.moveToFirst()
            assertEquals("the user's data survives both upgrades", 1, cursor.getInt(0))
        }
        db.query("SELECT direction FROM learned_patterns").use { cursor ->
            assertEquals(0, cursor.count) // column exists and is queryable
        }
    }
}
