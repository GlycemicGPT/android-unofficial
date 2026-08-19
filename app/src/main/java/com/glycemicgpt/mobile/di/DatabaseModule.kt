package com.glycemicgpt.mobile.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import com.glycemicgpt.mobile.data.local.AppDatabase
import com.glycemicgpt.mobile.data.local.dao.AlertDao
import com.glycemicgpt.mobile.data.local.dao.HistoryBackfillCursorDao
import com.glycemicgpt.mobile.data.local.dao.PumpDao
import com.glycemicgpt.mobile.data.local.dao.RawHistoryLogDao
import com.glycemicgpt.mobile.data.local.dao.SyncDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val PASSPHRASE_PREFS = "db_passphrase_prefs"
    private const val PASSPHRASE_KEY = "db_passphrase"
    private const val PASSPHRASE_LENGTH = 32

    /** Migration 6->7: make cgm_readings.timestampMs index unique for dedup. */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """DELETE FROM cgm_readings WHERE id NOT IN (
                    SELECT MAX(id) FROM cgm_readings GROUP BY timestampMs
                )""",
            )
            db.execSQL("DROP INDEX IF EXISTS index_cgm_readings_timestampMs")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_cgm_readings_timestampMs ON cgm_readings(timestampMs)",
            )
        }
    }

    /** Migration 7->8: make basal_readings.timestampMs index unique for dedup. */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """DELETE FROM basal_readings WHERE id NOT IN (
                    SELECT MAX(id) FROM basal_readings GROUP BY timestampMs
                )""",
            )
            db.execSQL("DROP INDEX IF EXISTS index_basal_readings_timestampMs")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_basal_readings_timestampMs ON basal_readings(timestampMs)",
            )
        }
    }

    /** Migration 8->9: rename controlIqMode -> activityMode, STANDARD -> NONE. */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE basal_readings RENAME COLUMN controlIqMode TO activityMode")
            db.execSQL("UPDATE basal_readings SET activityMode = 'NONE' WHERE activityMode = 'STANDARD'")
        }
    }

    /** Migration 9->10: add bolus dose breakdown columns (correctionUnits, mealUnits, source). */
    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE bolus_events ADD COLUMN correctionUnits REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE bolus_events ADD COLUMN mealUnits REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE bolus_events ADD COLUMN source TEXT NOT NULL DEFAULT ''")
        }
    }

    /** Migration 10->11: add bolus category column for pump-agnostic category taxonomy. */
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE bolus_events ADD COLUMN category TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * Migration 11->12: add a `source` column to cgm_readings and basal_readings so
     * cloud-sourced rows (the Nightscout-source plugin, Story 43.8) carry the same
     * per-row attribution that bolus_events already has. BLE writers leave it "".
     */
    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cgm_readings ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE basal_readings ADD COLUMN source TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * Migration 12->13: add an `ack_synced` column to alerts (GLY-130). `acknowledged` becomes
     * local/user-intent truth (set unconditionally at ack time so an alarm is always silenceable
     * offline); `ack_synced` tracks whether the server ack POST has landed. Existing acknowledged
     * rows are backfilled as synced: pre-13, `acknowledged` was only ever written after the
     * server confirmed the ack (HTTP 2xx) or from a server-acked payload, so the server already
     * knows about every one of them — no need to re-POST them on the first reconnect.
     */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE alerts ADD COLUMN ack_synced INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE alerts SET ack_synced = 1 WHERE acknowledged = 1")
        }
    }

    /**
     * Migration 13->14: split raw-download progress from processing progress (GLY-250).
     *
     * Adds `raw_history_logs.processed`, a single-row `history_backfill_cursor` table, and a
     * dedupe key on `sync_queue`. From here on the backfill inserts raw rows unprocessed, then
     * commits the derived CGM/bolus/basal rows, their sync-queue entries, the `processed` flags
     * and the cursor in ONE transaction -- so a process death mid-batch rolls the whole batch
     * back and the next run redoes it, instead of resuming above records that were never derived.
     *
     * WHAT IT DOES TO EXISTING DATA: it only adds. Nothing is dropped, recreated, copied or
     * rewritten; every existing row keeps its values.
     *
     * The two backfill statements say different things on purpose, because on this one upgrade
     * they genuinely know different amounts:
     *  - the cursor starts at `MAX(sequenceNumber)`, which IS the anchor the old poller was
     *    using, so an upgrade neither re-downloads the pump's whole history nor skips anything
     *    the old build had already fetched; but
     *  - retained raw rows stay `processed = 0`, i.e. "derivation unknown". The v13 bug this
     *    story fixes is precisely that a batch's raw rows could be inserted while its derived
     *    rows were never written, and NOTHING recorded which batch that was. Declaring them all
     *    processed would hide the only recoverable copy of exactly the data that was lost, on
     *    every existing device, in a step that cannot be walked back. Leaving them unprocessed
     *    costs one idempotent local re-derivation pass over the retained rows (GLY-251) and
     *    recovers whatever the old build dropped; the derived tables and the upload queue all
     *    dedupe, so re-deriving a row that was fine is a no-op.
     *
     * WHAT A DOWNGRADE DOES: nothing, loudly. See the database builder -- an older build meeting
     * schema 14 now fails to open instead of wiping the database.
     */
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE raw_history_logs ADD COLUMN processed INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `history_backfill_cursor` " +
                    "(`id` INTEGER NOT NULL, `processedThroughSequence` INTEGER NOT NULL, " +
                    "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO history_backfill_cursor " +
                    "(id, processedThroughSequence, updatedAtMs) " +
                    "SELECT 0, COALESCE(MAX(sequenceNumber), 0), 0 FROM raw_history_logs",
            )
            // Upload identity for rows that must not be queued twice. Existing rows keep NULL,
            // which SQLite treats as distinct in a unique index, so nothing already queued is
            // collapsed or dropped by the new index.
            db.execSQL("ALTER TABLE sync_queue ADD COLUMN dedupeKey TEXT")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_queue_dedupeKey` " +
                    "ON `sync_queue` (`dedupeKey`)",
            )
        }
    }

    /** All schema migrations, in order. Single source of truth shared by the database builder
     *  and the instrumented migration tests. */
    internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
    )

    /** Schema versions with no migration path forward: [ALL_MIGRATIONS] starts at 6->7, so a
     *  database still at 1..5 can only be recreated. These are the only versions the builder
     *  is allowed to be destructive about. */
    internal val PRE_MIGRATION_CHAIN_VERSIONS: IntArray = intArrayOf(1, 2, 3, 4, 5)

    /**
     * Retrieve or generate the database passphrase from EncryptedSharedPreferences.
     *
     * The passphrase is generated once using SecureRandom and stored encrypted
     * via Android Keystore (AES-256-GCM). This ensures the DB is encrypted at
     * rest and the key is hardware-backed where possible.
     */
    private fun getOrCreatePassphrase(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PASSPHRASE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        val existing = prefs.getString(PASSPHRASE_KEY, null)
        if (existing != null) {
            return existing.toByteArray(Charsets.UTF_8)
        }

        // Generate a new random passphrase
        val random = SecureRandom()
        val bytes = ByteArray(PASSPHRASE_LENGTH)
        random.nextBytes(bytes)
        // Encode as hex for safe storage and SQLCipher compatibility
        val passphrase = bytes.joinToString("") { "%02x".format(it) }

        val saved = prefs.edit().putString(PASSPHRASE_KEY, passphrase).commit()
        check(saved) { "Failed to persist SQLCipher passphrase" }
        Timber.i("Generated new database encryption passphrase")

        return passphrase.toByteArray(Charsets.UTF_8)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        // Load SQLCipher native library
        System.loadLibrary("sqlcipher")

        val passphrase = getOrCreatePassphrase(context)
        val factory = SupportOpenHelperFactory(passphrase)

        // One-time migration: delete old unencrypted database (data re-syncs from backend)
        val migrationPrefs = context.getSharedPreferences("db_migration", Context.MODE_PRIVATE)
        if (!migrationPrefs.getBoolean("sqlcipher_migrated", false)) {
            val oldDbFile = context.getDatabasePath("glycemicgpt.db")
            var migrationReadyToMark = true
            if (oldDbFile.exists()) {
                Timber.w("Deleting unencrypted database for migration to SQLCipher")
                val deleted = context.deleteDatabase("glycemicgpt.db")
                if (!deleted) {
                    migrationReadyToMark = false
                    Timber.e("Failed to delete legacy unencrypted database; will retry next launch")
                }
            }
            if (migrationReadyToMark) {
                val marked = migrationPrefs.edit().putBoolean("sqlcipher_migrated", true).commit()
                check(marked) { "Failed to persist SQLCipher migration flag" }
            }
        }

        return Room.databaseBuilder(context, AppDatabase::class.java, "glycemicgpt_encrypted.db")
            .openHelperFactory(factory)
            .addMigrations(*ALL_MIGRATIONS)
            // Destructive fallback ONLY for the schema versions that predate the migration chain
            // (the chain starts at 6->7, so 1..5 have no path forward and never had one). What
            // this deliberately no longer covers is the DOWNGRADE case: the plain
            // `fallbackToDestructiveMigration()` that used to be here also sets
            // `allowDestructiveMigrationOnDowngrade`, so a device that had migrated to a newer
            // schema and then installed an older build had its database silently dropped and
            // recreated -- local pump history, the raw rows a re-derivation pass needs, and the
            // whole pending upload queue, gone with no error and no way back. Failing to open is
            // recoverable (reinstall the newer build, or ship a real downgrade migration);
            // wiping is not. Add the version here only when losing that data is genuinely the
            // intended outcome. (GLY-250)
            .fallbackToDestructiveMigrationFrom(
                dropAllTables = true,
                *PRE_MIGRATION_CHAIN_VERSIONS,
            )
            .build()
    }

    @Provides
    fun providePumpDao(db: AppDatabase): PumpDao = db.pumpDao()

    @Provides
    fun provideSyncDao(db: AppDatabase): SyncDao = db.syncDao()

    @Provides
    fun provideRawHistoryLogDao(db: AppDatabase): RawHistoryLogDao = db.rawHistoryLogDao()

    @Provides
    fun provideAlertDao(db: AppDatabase): AlertDao = db.alertDao()

    @Provides
    fun provideHistoryBackfillCursorDao(db: AppDatabase): HistoryBackfillCursorDao =
        db.historyBackfillCursorDao()
}
