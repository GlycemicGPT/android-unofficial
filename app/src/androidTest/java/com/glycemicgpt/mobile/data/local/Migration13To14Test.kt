// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glycemicgpt.mobile.di.DatabaseModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the Room 13 -> 14 migration (GLY-250), which splits raw-download progress from
 * processing progress: it adds `raw_history_logs.processed` and the single-row
 * `history_backfill_cursor` table.
 *
 * Three things are on the line, and they run against a POPULATED v13 database because a
 * migration that only works on an empty one proves nothing:
 *
 *  1. `runMigrationsAndValidate` re-creates v13 from the exported `13.json`, applies the real
 *     [DatabaseModule.ALL_MIGRATIONS], and asserts the result matches `14.json` -- a hand-written
 *     `CREATE TABLE` that drifts from what Room generates fails here rather than falling into
 *     `fallbackToDestructiveMigration` on a user's device and wiping their history.
 *  2. Existing raw rows survive and come out marked processed. The v13 anchor
 *     (`MAX(sequenceNumber)`) already declared everything at or below it done, so any other value
 *     would have the flag contradict the cursor from the first launch.
 *  3. The cursor is seeded from that same `MAX(sequenceNumber)`, so an upgrade resumes exactly
 *     where the old build left off instead of re-downloading the pump's entire history.
 *
 * Instrumented (needs real SQLite): run with
 * `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class Migration13To14Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate13To14_marksExistingRawRowsProcessed_andSeedsTheCursorFromTheOldAnchor() {
        // v13: raw_history_logs has no `processed` column and there is no cursor table -- the
        // resume anchor is MAX(sequenceNumber) over these rows.
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO raw_history_logs " +
                    "(sequenceNumber, rawBytesB64, eventTypeId, pumpTimeSeconds, sentToBackend, createdAtMs) " +
                    "VALUES (100, 'dGVzdA==', 399, 572000000, 0, 1000)",
            )
            db.execSQL(
                "INSERT INTO raw_history_logs " +
                    "(sequenceNumber, rawBytesB64, eventTypeId, pumpTimeSeconds, sentToBackend, createdAtMs) " +
                    "VALUES (4200, 'YmluYXJ5', 400, 572000600, 1, 2000)",
            )
            // Derived rows from those logs, to prove the migration leaves them alone.
            db.execSQL(
                "INSERT INTO cgm_readings (glucoseMgDl, trendArrow, source, timestampMs) " +
                    "VALUES (118, 'FLAT', '', 1000)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, *DatabaseModule.ALL_MIGRATIONS)

        db.query("SELECT sequenceNumber, rawBytesB64, processed FROM raw_history_logs ORDER BY sequenceNumber")
            .use { cursor ->
                assertTrue("first raw row should survive the migration", cursor.moveToFirst())
                assertEquals(100, cursor.getInt(0))
                assertEquals("dGVzdA==", cursor.getString(1))
                assertEquals("pre-migration rows are at or below the old anchor", 1, cursor.getInt(2))

                assertTrue("second raw row should survive the migration", cursor.moveToNext())
                assertEquals(4200, cursor.getInt(0))
                assertEquals(1, cursor.getInt(2))
                assertFalse("no extra raw rows", cursor.moveToNext())
            }

        db.query("SELECT id, processedThroughSequence FROM history_backfill_cursor").use { cursor ->
            assertTrue("the cursor row must exist after the migration", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            // Exactly the anchor the old build was using: an upgrade must not re-read the
            // pump's whole history, and must not skip anything either.
            assertEquals(4200, cursor.getInt(1))
            assertFalse("the cursor table holds a single row", cursor.moveToNext())
        }

        db.query("SELECT glucoseMgDl FROM cgm_readings").use { cursor ->
            assertTrue("derived rows are untouched by the migration", cursor.moveToFirst())
            assertEquals(118, cursor.getInt(0))
        }
    }

    @Test
    fun migrate13To14_onAnEmptyRawTable_seedsAFreshInstallCursor() {
        helper.createDatabase(TEST_DB_EMPTY, 13).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB_EMPTY, 14, true, *DatabaseModule.ALL_MIGRATIONS,
        )

        db.query("SELECT processedThroughSequence FROM history_backfill_cursor").use { cursor ->
            assertTrue(cursor.moveToFirst())
            // Nothing downloaded, nothing processed -- the poller reads this as a fresh install
            // and does the full initial sync, exactly as an empty raw table used to mean.
            assertEquals(0, cursor.getInt(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-13-14-test"
        const val TEST_DB_EMPTY = "migration-13-14-empty-test"
    }
}
