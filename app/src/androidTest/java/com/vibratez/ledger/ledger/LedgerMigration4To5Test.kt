package com.vibratez.ledger.ledger

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerMigration4To5Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "migration-4-5-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationPreservesExistingRowsAndCreatesBudgetTables() {
        open(4, object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE transactions (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("CREATE TABLE pending_reviews (id TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("INSERT INTO transactions(id) VALUES ('transaction-sentinel')")
                db.execSQL("INSERT INTO pending_reviews(id) VALUES ('review-sentinel')")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).close()

        val migrated = open(5, object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                LedgerDatabase.MIGRATION_4_5.migrate(db)
            }
        })
        val db = migrated.writableDatabase

        assertEquals(1, db.query("SELECT COUNT(*) FROM transactions").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, db.query("SELECT COUNT(*) FROM pending_reviews").use { it.moveToFirst(); it.getInt(0) })
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue("monthly_budgets" in tables)
        assertTrue("daily_settlements" in tables)
        assertTrue("budget_fund_entries" in tables)
        migrated.close()
    }

    private fun open(version: Int, callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build(),
        ).also { it.writableDatabase }
}
