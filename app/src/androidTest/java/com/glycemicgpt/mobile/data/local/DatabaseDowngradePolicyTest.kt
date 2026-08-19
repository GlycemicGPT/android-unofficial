// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
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
 * equivalent of a rollback: migrate forward, then install the previous APK.
 *
 * The production builder used to answer that with `fallbackToDestructiveMigration()`, whose
 * no-argument form also enables destructive DOWNGRADE. Room's response to an unknown downgrade
 * was therefore to drop every table and recreate the schema: local pump history, the raw rows a
 * re-derivation pass rebuilds from, and the entire pending upload queue, gone silently and with
 * no way back. The policy this pins (GLY-250) is the opposite one -- fail to open, keep the
 * data. That is recoverable (reinstall the newer build, or ship a real downgrade migration);
 * a wipe is not.
 *
 * The database version is moved by hand rather than by building a second `AppDatabase` class,
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

    @Test
    fun aDatabaseFromANewerBuildFailsToOpenInsteadOfBeingWiped() {
        withProductionDatabase { db ->
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO raw_history_logs " +
                    "(sequenceNumber, rawBytesB64, eventTypeId, pumpTimeSeconds, sentToBackend, createdAtMs, processed) " +
                    "VALUES (4200, 'YmluYXJ5', 399, 572000600, 0, 1000, 0)",
            )
        }

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

    private companion object {
        const val TEST_DB = "downgrade-policy-test"
        const val CURRENT_SCHEMA_VERSION = 14
        const val FUTURE_SCHEMA_VERSION = 15
    }
}
