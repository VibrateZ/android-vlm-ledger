package com.vibratez.ledger.ledger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vibratez.ledger.security.SecureSettings
import java.nio.charset.StandardCharsets

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["sha256"], unique = true),
        Index(value = ["transactionFingerprint"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val amountMinor: Long?,
    val currency: String?,
    val merchant: String?,
    val counterparty: String?,
    val occurredAt: String?,
    val suggestedTag: String?,
    val sha256: String,
    val transactionFingerprint: String?,
    val sourceUri: String,
    val platform: String,
    val confidence: Double,
    val timeSource: String?,
    val externalId: String?,
    val vlmModel: String?,
    val vlmRequestId: String?,
    val screenshotCapturedAtMillis: Long?,
    @ColumnInfo(defaultValue = "''") val positiveFeatures: String,
    @ColumnInfo(defaultValue = "''") val negativeFeatures: String,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val reasonCode: String,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val localDecision: String,
    @ColumnInfo(defaultValue = "'ledger.prompt.v1'") val promptVersion: String,
    val contractVersion: String,
    val createdAtMillis: Long,
    @ColumnInfo(defaultValue = "NULL") val itemName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val account: String? = null,
    @ColumnInfo(defaultValue = "NULL") val paymentMethod: String? = null,
    @ColumnInfo(defaultValue = "NULL") val note: String? = null,
    @ColumnInfo(defaultValue = "'{}'") val customFieldsJson: String = "{}",
    @ColumnInfo(defaultValue = "NULL") val storedImagePath: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAtMillis: Long = createdAtMillis,
)

@Entity(
    tableName = "screenshot_jobs",
    indices = [
        Index(value = ["sourceUri"], unique = true),
        Index(value = ["status", "nextAttemptAtMillis"]),
        Index(value = ["deleteState"]),
    ],
)
data class ScreenshotJobEntity(
    @PrimaryKey val id: String,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val capturedAtMillis: Long?,
    val addedAtMillis: Long?,
    val width: Int?,
    val height: Int?,
    val sourcePackage: String?,
    val sha256: String?,
    val status: String,
    val attemptCount: Int,
    val nextAttemptAtMillis: Long,
    val lastError: String?,
    val transactionId: String?,
    val storedImagePath: String?,
    val deleteState: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "audit_events",
    indices = [Index(value = ["entityType", "entityId"]), Index(value = ["createdAtMillis"])],
)
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val detail: String?,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "pending_reviews",
    indices = [Index(value = ["sha256"], unique = true)],
)
data class PendingReviewEntity(
    @PrimaryKey val id: String,
    val sha256: String,
    val sourceUri: String,
    val direction: String,
    val amountMinor: Long?,
    val currency: String?,
    val merchant: String?,
    val counterparty: String?,
    val occurredAt: String?,
    val platform: String,
    val timeSource: String?,
    val externalId: String?,
    val suggestedTag: String?,
    val confidence: Double,
    val isPaymentScreenshot: Boolean,
    val positiveFeatures: String,
    val negativeFeatures: String,
    val freshness: String,
    val reasonCode: String,
    val vlmModel: String?,
    val vlmRequestId: String?,
    val screenshotCapturedAtMillis: Long?,
    val status: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "monthly_budgets")
data class MonthlyBudgetEntity(
    @PrimaryKey val yearMonth: String,
    val amountMinor: Long,
    val currency: String,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "daily_settlements",
    indices = [Index(value = ["settlementDate", "revision"], unique = true)],
)
data class DailySettlementEntity(
    @PrimaryKey val id: String,
    val settlementDate: String,
    val revision: Int,
    val baseBudgetMinor: Long,
    val allocatedMinor: Long,
    val netExpenseMinor: Long,
    val incomeMinor: Long,
    val balanceMinor: Long,
    val destination: String,
    val settledAmountMinor: Long,
    val snapshotHash: String,
    val reversesSettlementId: String?,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "budget_fund_entries",
    indices = [
        Index(value = ["settlementId"]),
        Index(value = ["targetDate"]),
    ],
)
data class BudgetFundEntryEntity(
    @PrimaryKey val id: String,
    val entryType: String,
    val poolDeltaMinor: Long,
    val savingsDeltaMinor: Long,
    val allocationMinor: Long,
    val targetDate: String?,
    val settlementId: String?,
    val reversesEntryId: String?,
    val createdAtMillis: Long,
)

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TransactionEntity): Long

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE sha256 = :sha256)")
    suspend fun containsHash(sha256: String): Boolean

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): TransactionEntity?

    @Update
    suspend fun update(entity: TransactionEntity): Int

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE transactions SET storedImagePath = :path, updatedAtMillis = :updatedAtMillis WHERE sha256 = :sha256")
    suspend fun updateStoredImagePath(sha256: String, path: String, updatedAtMillis: Long): Int

    @Query("SELECT * FROM transactions WHERE substr(occurredAt, 1, 10) = :date ORDER BY occurredAt DESC")
    suspend fun onDate(date: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE substr(occurredAt, 1, 7) = :yearMonth ORDER BY occurredAt DESC")
    suspend fun inMonth(yearMonth: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE occurredAt IS NULL OR trim(occurredAt) = '' ORDER BY createdAtMillis DESC")
    suspend fun withoutTime(): List<TransactionEntity>
}

@Dao
interface ScreenshotJobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ScreenshotJobEntity): Long

    @Query("SELECT * FROM screenshot_jobs WHERE status IN ('PENDING','RETRY_WAIT') AND nextAttemptAtMillis <= :nowMillis ORDER BY capturedAtMillis ASC, createdAtMillis ASC LIMIT :limit")
    suspend fun due(nowMillis: Long, limit: Int): List<ScreenshotJobEntity>

    @Query("SELECT * FROM screenshot_jobs WHERE deleteState = 'PENDING' ORDER BY updatedAtMillis ASC LIMIT :limit")
    suspend fun pendingDeletes(limit: Int): List<ScreenshotJobEntity>

    @Query("SELECT COUNT(*) FROM screenshot_jobs WHERE status IN ('PENDING','PROCESSING','RETRY_WAIT')")
    suspend fun activeCount(): Int

    @Query("SELECT MIN(nextAttemptAtMillis) FROM screenshot_jobs WHERE status IN ('PENDING','RETRY_WAIT')")
    suspend fun nextAttemptAtMillis(): Long?

    @Query("SELECT COUNT(*) FROM screenshot_jobs WHERE deleteState = 'PENDING'")
    suspend fun pendingDeleteCount(): Int

    @Query("UPDATE screenshot_jobs SET status = 'RETRY_WAIT', nextAttemptAtMillis = :nowMillis, lastError = 'interrupted', updatedAtMillis = :nowMillis WHERE status = 'PROCESSING' AND updatedAtMillis < :staleBeforeMillis")
    suspend fun recoverStaleProcessing(staleBeforeMillis: Long, nowMillis: Long): Int

    @Query("UPDATE screenshot_jobs SET status = :status, sha256 = :sha256, lastError = :lastError, attemptCount = :attemptCount, nextAttemptAtMillis = :nextAttemptAtMillis, transactionId = :transactionId, storedImagePath = :storedImagePath, deleteState = :deleteState, updatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun updateState(
        id: String,
        status: String,
        sha256: String?,
        lastError: String?,
        attemptCount: Int,
        nextAttemptAtMillis: Long,
        transactionId: String?,
        storedImagePath: String?,
        deleteState: String,
        updatedAtMillis: Long,
    ): Int

    @Query("UPDATE screenshot_jobs SET deleteState = :state, updatedAtMillis = :updatedAtMillis WHERE sourceUri IN (:sourceUris)")
    suspend fun updateDeleteState(sourceUris: List<String>, state: String, updatedAtMillis: Long): Int
}

@Dao
interface AuditEventDao {
    @Insert
    suspend fun insert(entity: AuditEventEntity)

    @Query("SELECT * FROM audit_events ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditEventEntity>
}

@Dao
interface PendingReviewDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PendingReviewEntity): Long

    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING_CONFIRMATION' ORDER BY createdAtMillis DESC")
    suspend fun pending(): List<PendingReviewEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM pending_reviews WHERE sha256 = :sha256 AND status = 'PENDING_CONFIRMATION')")
    suspend fun containsPending(sha256: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pending_reviews WHERE sourceUri = :sourceUri)")
    suspend fun containsSourceUri(sourceUri: String): Boolean

    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String): Int

    @Query("DELETE FROM pending_reviews WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

@Dao
interface BudgetDao {
    @Upsert
    suspend fun upsertMonthlyBudget(entity: MonthlyBudgetEntity)

    @Query("SELECT * FROM monthly_budgets WHERE yearMonth = :yearMonth")
    suspend fun monthlyBudget(yearMonth: String): MonthlyBudgetEntity?

    @Insert
    suspend fun insertSettlement(entity: DailySettlementEntity)

    @Query("SELECT * FROM daily_settlements WHERE settlementDate = :date ORDER BY revision DESC LIMIT 1")
    suspend fun latestSettlement(date: String): DailySettlementEntity?

    @Insert
    suspend fun insertFundEntry(entity: BudgetFundEntryEntity)

    @Query("SELECT COALESCE(SUM(poolDeltaMinor), 0) FROM budget_fund_entries")
    suspend fun poolBalance(): Long

    @Query("SELECT COALESCE(SUM(savingsDeltaMinor), 0) FROM budget_fund_entries")
    suspend fun savingsBalance(): Long

    @Query("SELECT COALESCE(SUM(allocationMinor), 0) FROM budget_fund_entries WHERE targetDate = :date")
    suspend fun allocatedToDate(date: String): Long

    @Query("SELECT * FROM budget_fund_entries WHERE settlementId = :settlementId AND entryType IN ('SETTLE_POOL', 'SETTLE_SAVINGS') LIMIT 1")
    suspend fun settlementCredit(settlementId: String): BudgetFundEntryEntity?

    @Query("SELECT * FROM budget_fund_entries WHERE savingsDeltaMinor != 0 ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun recentSavingsEntries(limit: Int): List<BudgetFundEntryEntity>
}

@Database(
    entities = [
        TransactionEntity::class,
        PendingReviewEntity::class,
        MonthlyBudgetEntity::class,
        DailySettlementEntity::class,
        BudgetFundEntryEntity::class,
        ScreenshotJobEntity::class,
        AuditEventEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun pendingReviewDao(): PendingReviewDao
    abstract fun budgetDao(): BudgetDao
    abstract fun screenshotJobDao(): ScreenshotJobDao
    abstract fun auditEventDao(): AuditEventDao

    companion object {
        fun open(context: Context, settings: SecureSettings): LedgerDatabase {
            migrateEncryptedDatabaseIfNeeded(context)
            return Room.databaseBuilder(
                context,
                LedgerDatabase::class.java,
                "ledger.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
        }

        private fun migrateEncryptedDatabaseIfNeeded(context: Context) {
            val databaseFile = context.getDatabasePath("ledger.db")
            if (!databaseFile.isFile || databaseFile.length() < SQLITE_HEADER.size) return
            val header = runCatching {
                databaseFile.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also { input.read(it) } }
            }.getOrNull() ?: return
            if (header.contentEquals(SQLITE_HEADER)) return
            val backup = java.io.File(
                databaseFile.parentFile,
                "ledger.db.encrypted-backup-${System.currentTimeMillis()}",
            )
            check(databaseFile.renameTo(backup)) { "Unable to preserve legacy encrypted database" }
            listOf("ledger.db-wal", "ledger.db-shm").forEach { suffix ->
                val sidecar = java.io.File(databaseFile.parentFile, suffix)
                if (sidecar.isFile) sidecar.renameTo(java.io.File(backup.parentFile, "${backup.name}-$suffix"))
            }
        }

        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN vlmRequestId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN screenshotCapturedAtMillis INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN counterparty TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN positiveFeatures TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN negativeFeatures TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN reasonCode TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN localDecision TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN promptVersion TEXT NOT NULL DEFAULT 'ledger.prompt.v1'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN transactionFingerprint TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_transactions_transactionFingerprint " +
                        "ON transactions(transactionFingerprint)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_reviews (
                        id TEXT NOT NULL PRIMARY KEY,
                        sha256 TEXT NOT NULL,
                        sourceUri TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        amountMinor INTEGER,
                        currency TEXT,
                        merchant TEXT,
                        counterparty TEXT,
                        occurredAt TEXT,
                        platform TEXT NOT NULL,
                        timeSource TEXT,
                        externalId TEXT,
                        suggestedTag TEXT,
                        confidence REAL NOT NULL,
                        isPaymentScreenshot INTEGER NOT NULL,
                        positiveFeatures TEXT NOT NULL,
                        negativeFeatures TEXT NOT NULL,
                        freshness TEXT NOT NULL,
                        reasonCode TEXT NOT NULL,
                        vlmModel TEXT,
                        vlmRequestId TEXT,
                        screenshotCapturedAtMillis INTEGER,
                        status TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_sha256 ON pending_reviews(sha256)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS monthly_budgets (
                        yearMonth TEXT NOT NULL PRIMARY KEY,
                        amountMinor INTEGER NOT NULL,
                        currency TEXT NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_settlements (
                        id TEXT NOT NULL PRIMARY KEY,
                        settlementDate TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        baseBudgetMinor INTEGER NOT NULL,
                        allocatedMinor INTEGER NOT NULL,
                        netExpenseMinor INTEGER NOT NULL,
                        incomeMinor INTEGER NOT NULL,
                        balanceMinor INTEGER NOT NULL,
                        destination TEXT NOT NULL,
                        settledAmountMinor INTEGER NOT NULL,
                        snapshotHash TEXT NOT NULL,
                        reversesSettlementId TEXT,
                        createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_daily_settlements_settlementDate_revision " +
                        "ON daily_settlements(settlementDate, revision)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budget_fund_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        entryType TEXT NOT NULL,
                        poolDeltaMinor INTEGER NOT NULL,
                        savingsDeltaMinor INTEGER NOT NULL,
                        allocationMinor INTEGER NOT NULL,
                        targetDate TEXT,
                        settlementId TEXT,
                        reversesEntryId TEXT,
                        createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_budget_fund_entries_settlementId " +
                        "ON budget_fund_entries(settlementId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_budget_fund_entries_targetDate " +
                        "ON budget_fund_entries(targetDate)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions RENAME TO transactions_v5")
                db.execSQL(
                    """
                    CREATE TABLE transactions (
                        id TEXT NOT NULL PRIMARY KEY,
                        direction TEXT NOT NULL,
                        amountMinor INTEGER,
                        currency TEXT,
                        merchant TEXT,
                        counterparty TEXT,
                        occurredAt TEXT,
                        suggestedTag TEXT,
                        sha256 TEXT NOT NULL,
                        transactionFingerprint TEXT,
                        sourceUri TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        timeSource TEXT,
                        externalId TEXT,
                        vlmModel TEXT,
                        vlmRequestId TEXT,
                        screenshotCapturedAtMillis INTEGER,
                        positiveFeatures TEXT NOT NULL DEFAULT '',
                        negativeFeatures TEXT NOT NULL DEFAULT '',
                        reasonCode TEXT NOT NULL DEFAULT 'UNKNOWN',
                        localDecision TEXT NOT NULL DEFAULT 'UNKNOWN',
                        promptVersion TEXT NOT NULL DEFAULT 'ledger.prompt.v1',
                        contractVersion TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        itemName TEXT DEFAULT NULL,
                        account TEXT DEFAULT NULL,
                        paymentMethod TEXT DEFAULT NULL,
                        note TEXT DEFAULT NULL,
                        customFieldsJson TEXT NOT NULL DEFAULT '{}',
                        storedImagePath TEXT DEFAULT NULL,
                        updatedAtMillis INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO transactions (
                        id,direction,amountMinor,currency,merchant,counterparty,occurredAt,
                        suggestedTag,sha256,transactionFingerprint,sourceUri,platform,confidence,
                        timeSource,externalId,vlmModel,vlmRequestId,screenshotCapturedAtMillis,
                        positiveFeatures,negativeFeatures,reasonCode,localDecision,promptVersion,
                        contractVersion,createdAtMillis,updatedAtMillis
                    ) SELECT
                        id,direction,amountMinor,currency,merchant,counterparty,occurredAt,
                        suggestedTag,sha256,transactionFingerprint,sourceUri,platform,confidence,
                        timeSource,externalId,vlmModel,vlmRequestId,screenshotCapturedAtMillis,
                        positiveFeatures,negativeFeatures,reasonCode,localDecision,promptVersion,
                        contractVersion,createdAtMillis,createdAtMillis
                    FROM transactions_v5
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE transactions_v5")
                db.execSQL("CREATE UNIQUE INDEX index_transactions_sha256 ON transactions(sha256)")
                db.execSQL("CREATE UNIQUE INDEX index_transactions_transactionFingerprint ON transactions(transactionFingerprint)")
                db.execSQL(
                    """
                    CREATE TABLE screenshot_jobs (
                        id TEXT NOT NULL PRIMARY KEY,
                        sourceUri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        capturedAtMillis INTEGER,
                        addedAtMillis INTEGER,
                        width INTEGER,
                        height INTEGER,
                        sourcePackage TEXT,
                        sha256 TEXT,
                        status TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextAttemptAtMillis INTEGER NOT NULL,
                        lastError TEXT,
                        transactionId TEXT,
                        storedImagePath TEXT,
                        deleteState TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX index_screenshot_jobs_sourceUri ON screenshot_jobs(sourceUri)")
                db.execSQL("CREATE INDEX index_screenshot_jobs_status_nextAttemptAtMillis ON screenshot_jobs(status,nextAttemptAtMillis)")
                db.execSQL("CREATE INDEX index_screenshot_jobs_deleteState ON screenshot_jobs(deleteState)")
                db.execSQL(
                    """
                    CREATE TABLE audit_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        entityType TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        detail TEXT,
                        createdAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_audit_events_entityType_entityId ON audit_events(entityType,entityId)")
                db.execSQL("CREATE INDEX index_audit_events_createdAtMillis ON audit_events(createdAtMillis)")
            }
        }
    }
}
