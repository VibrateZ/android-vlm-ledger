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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vibratez.ledger.security.SecureSettings
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget

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
    val amountMinor: Long,
    val currency: String,
    val merchant: String?,
    val counterparty: String?,
    val occurredAt: String,
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
)

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TransactionEntity): Long

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE sha256 = :sha256)")
    suspend fun containsHash(sha256: String): Boolean
}

@Database(
    entities = [TransactionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        fun open(context: Context, settings: SecureSettings): LedgerDatabase {
            // SQLCipher's native core must be loaded before Room creates the helper.
            System.loadLibrary("sqlcipher")
            Logger.setTarget(NoopTarget())
            val passphrase = settings.databasePassphrase()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context,
                LedgerDatabase::class.java,
                "ledger.db",
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

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
    }
}
