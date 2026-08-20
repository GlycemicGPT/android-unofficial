// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glycemicgpt.mobile.di.DatabaseModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What happens when a build meets a database written by a NEWER build than itself -- the phone
 * equivalent of a rollback: migrate forward, then install an older APK.
 *
 * There are two different questions here and they have different answers, which is the whole
 * point of the pair of tests below (GLY-250).
 *
 * **Going forward, this build refuses.** The production builder used to say
 * `fallbackToDestructiveMigration()`, whose no-argument form also enables destructive DOWNGRADE:
 * Room's answer to an unknown downgrade was to drop every table and recreate the schema, silently.
 * It now only recreates schema versions 1..5, which predate the migration chain. Anything else --
 * including any future rollback onto schema 14 -- fails to open and keeps the file. That is
 * recoverable (reinstall the newer build, or ship a real downgrade migration); a wipe is not.
 *
 * **Going backward from here, the already-shipped build wipes, and nothing in this change can
 * stop it.** A user who installs the previously-shipped schema-13 APK runs THAT build's builder,
 * with its no-argument destructive fallback, and it drops the schema-14 database on open. The new
 * policy cannot reach backwards into an APK that is already on devices. The second test pins that
 * loss so it is a measured fact in the record rather than a claim, and so the day someone does
 * ship a 14 -> 13 downgrade migration, a test starts failing and says so.
 *
 * The database version is moved by hand rather than by building a second full `AppDatabase`,
 * because what Room actually branches on is the file's `user_version`.
 *
 * Instrumented (needs real SQLite): run with
 * `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseDowngradePolicyTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    /** The production builder's migration configuration, minus SQLCipher. */
    private fun openLikeProduction(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationFrom(
                dropAllTables = true,
                *DatabaseModule.PRE_MIGRATION_CHAIN_VERSIONS,
            )
            .build()

    /**
     * The builder the previously-shipped build uses, on that build's schema version: the plain
     * no-argument destructive fallback, which is what `1e94fab` and everything before it ships.
     * It is deprecated in the Room version this app resolves, not removed, and the deprecated
     * form is exactly the behaviour under test.
     *
     * The only difference from the shipped builder is that this one is handed the 13 -> 14
     * migration as well, which Room cannot use in this direction and ignores.
     */
    @Suppress("DEPRECATION")
    private fun openLikeThePreviouslyShippedBuild(): LegacySchema13Database =
        Room.databaseBuilder(context, LegacySchema13Database::class.java, TEST_DB)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .fallbackToDestructiveMigration()
            .build()

    private fun setUserVersion(version: Int) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DB).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { it.execSQL("PRAGMA user_version = $version") }
    }

    private inline fun <T> withProductionDatabase(block: (AppDatabase) -> T): T {
        val db = openLikeProduction()
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun seedOneRawRow() {
        withProductionDatabase { db ->
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO raw_history_logs " +
                    "(sequenceNumber, rawBytesB64, eventTypeId, pumpTimeSeconds, sentToBackend, createdAtMs, processed) " +
                    "VALUES (4200, 'YmluYXJ5', 399, 572000600, 0, 1000, 0)",
            )
        }
        // Otherwise "the row is gone" is not evidence of anything.
        assertEquals("the seed row is in the schema-14 file", 1, countRawRows())
    }

    private fun countRawRows(): Int = withProductionDatabase { db ->
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM raw_history_logs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    @Test
    fun aDatabaseFromANewerBuildFailsToOpenInsteadOfBeingWiped() {
        seedOneRawRow()

        // The user upgrades, then rolls back: the file is now stamped with a schema this build
        // has never heard of and has no path down from.
        setUserVersion(FUTURE_SCHEMA_VERSION)

        val failure = runCatching { withProductionDatabase { it.openHelper.writableDatabase } }
            .exceptionOrNull()

        assertNotNull("an unknown downgrade must fail loudly, not silently recreate", failure)
        assertTrue(
            "expected a migration complaint, got: ${failure?.javaClass?.simpleName}: ${failure?.message}",
            failure?.message?.contains("migration", ignoreCase = true) == true,
        )

        // And the point of failing: the data is still there. Put the version back the way
        // reinstalling the newer build would, and everything is intact.
        setUserVersion(CURRENT_SCHEMA_VERSION)
        withProductionDatabase { db ->
            db.query("SELECT sequenceNumber, rawBytesB64 FROM raw_history_logs", emptyArray())
                .use { cursor ->
                    assertTrue("the raw row survived the refused downgrade", cursor.moveToFirst())
                    assertEquals(4200, cursor.getInt(0))
                    assertEquals("YmluYXJ5", cursor.getString(1))
                }
        }
    }

    @Test
    fun installingThePreviouslyShippedBuildStillWipesTheDatabase() {
        seedOneRawRow()

        // No hand-stamping this time: the file really is at schema 14, written by this build's
        // migration, and the reader really is configured the way the shipped schema-13 build is.
        val legacy = openLikeThePreviouslyShippedBuild()
        val rowsAfterRollback = try {
            legacy.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM raw_history_logs")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getInt(0)
                }
        } finally {
            legacy.close()
        }

        // Not the outcome anybody wants -- it is the outcome that is already on devices. The old
        // builder opens the file happily and hands back an empty, recreated table: the raw rows a
        // re-derivation pass rebuilds from, local CGM/bolus/basal, alerts and the pending upload
        // queue are all gone, with no error and no way back.
        //
        // If this ever starts failing, someone has made the rollback non-destructive -- update
        // the story record and the builder comment to match, do not just re-pin the number.
        assertEquals(
            "the previously-shipped build recreates the database instead of refusing it",
            0,
            rowsAfterRollback,
        )
    }

    private companion object {
        const val TEST_DB = "downgrade-policy-test"
        const val CURRENT_SCHEMA_VERSION = 14
        const val FUTURE_SCHEMA_VERSION = 15
    }
}

/**
 * `raw_history_logs` as schema 13 declares it -- the same table, without the `processed` column
 * this story adds. Enough of the old schema for Room to recreate the table after it drops it.
 */
@Entity(
    tableName = "raw_history_logs",
    indices = [Index(value = ["sequenceNumber"], unique = true)],
)
data class LegacyRawHistoryLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceNumber: Int,
    val rawBytesB64: String,
    val eventTypeId: Int,
    val pumpTimeSeconds: Long,
    val sentToBackend: Boolean = false,
    val createdAtMs: Long = 0,
)

@Dao
interface LegacyRawHistoryLogDao {
    @Query("SELECT COUNT(*) FROM raw_history_logs")
    fun countRawRows(): Int
}

/**
 * Stands in for the shipped schema-13 `AppDatabase`. Only the version number and the builder
 * configuration decide what Room does to a schema-14 file it is asked to open -- it compares the
 * file's `user_version` against this one before it looks at a single table -- so this carries one
 * entity rather than a copy of all ten, and that entity is the table the assertion reads.
 */
@Database(
    entities = [LegacyRawHistoryLogEntity::class],
    version = 13,
    exportSchema = false,
)
abstract class LegacySchema13Database : RoomDatabase() {
    abstract fun legacyRawHistoryLogDao(): LegacyRawHistoryLogDao
}
