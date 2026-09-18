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
class LedgerMigration5To6Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "migration-5-6-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationMakesCoreFieldsNullableAndAddsQueueAndAudit() {
        open(5, object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE transactions (
                        id TEXT NOT NULL PRIMARY KEY, direction TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL, currency TEXT NOT NULL,
                        merchant TEXT, counterparty TEXT, occurredAt TEXT NOT NULL,
                        suggestedTag TEXT, sha256 TEXT NOT NULL, transactionFingerprint TEXT,
                        sourceUri TEXT NOT NULL, platform TEXT NOT NULL, confidence REAL NOT NULL,
                        timeSource TEXT, externalId TEXT, vlmModel TEXT, vlmRequestId TEXT,
                        screenshotCapturedAtMillis INTEGER, positiveFeatures TEXT NOT NULL,
                        negativeFeatures TEXT NOT NULL, reasonCode TEXT NOT NULL,
                        localDecision TEXT NOT NULL, promptVersion TEXT NOT NULL,
                        contractVersion TEXT NOT NULL, createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX index_transactions_sha256 ON transactions(sha256)")
                db.execSQL("CREATE UNIQUE INDEX index_transactions_transactionFingerprint ON transactions(transactionFingerprint)")
                db.execSQL(
                    """INSERT INTO transactions VALUES (
                        'one','EXPENSE',1280,'CNY','merchant',NULL,'2026-09-17T12:00:00+08:00',
                        'food','hash','fingerprint','content://one','WECHAT',0.95,'PAGE_EXACT',NULL,
                        NULL,NULL,NULL,'','','PAYMENT_PAGE_CONFIRMED','AUTO_BOOK','ledger.prompt.v1',
                        'ledger.v1',1
                    )""",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).close()

        val migrated = open(6, object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                LedgerDatabase.MIGRATION_5_6.migrate(db)
            }
        })
        val db = migrated.writableDatabase
        assertEquals(1, db.query("SELECT COUNT(*) FROM transactions").use { it.moveToFirst(); it.getInt(0) })
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue("screenshot_jobs" in tables)
        assertTrue("audit_events" in tables)
        val columns = db.query("PRAGMA table_info(transactions)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getString(1),
                        ColumnInfo(
                            notNull = cursor.getInt(3),
                            defaultValue = if (cursor.isNull(4)) null else cursor.getString(4),
                        ),
                    )
                }
            }
        }
        assertEquals(0, columns["amountMinor"]?.notNull)
        assertEquals(0, columns["currency"]?.notNull)
        assertEquals(0, columns["occurredAt"]?.notNull)
        listOf("itemName", "account", "paymentMethod", "note", "storedImagePath").forEach { name ->
            assertEquals("$name must have Room's declared default", "NULL", columns[name]?.defaultValue)
        }
        migrated.close()
    }

    private data class ColumnInfo(
        val notNull: Int,
        val defaultValue: String?,
    )

    private fun open(version: Int, callback: SupportSQLiteOpenHelper.Callback): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build(),
        ).also { it.writableDatabase }
}
