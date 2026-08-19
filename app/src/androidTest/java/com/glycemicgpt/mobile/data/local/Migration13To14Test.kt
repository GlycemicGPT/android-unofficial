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
 * processing progress: it adds `raw_history_logs.processed`, the single-row
 * `history_backfill_cursor` table, and a dedupe key on `sync_queue`.
 *
 * These run against a POPULATED v13 database, because a migration that only works on an empty
 * one proves nothing. What is on the line:
 *
 *  1. `runMigrationsAndValidate` re-creates v13 from the exported `13.json`, applies the real
 *     [DatabaseModule.ALL_MIGRATIONS], and asserts the result matches `14.json` -- a hand-written
 *     `CREATE TABLE` that drifts from what Room generates fails here rather than on a user's
 *     device.
 *  2. The migration only ADDS. Existing raw, derived and queued rows survive untouched.
 *  3. Retained raw rows come out `processed = 0`, i.e. "derivation unknown". The v13 bug is
 *     exactly that raw rows could be inserted while their derived rows were never written, and
 *     nothing recorded which batch that was, so those rows have to stay recoverable.
 *  4. The cursor is seeded from `MAX(sequenceNumber)` -- the anchor the old build was already
 *     using -- so an upgrade neither re-downloads the pump's entire history nor skips ahead.
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
    fun migrate13To14_keepsRetainedRawRowsRecoverable_andSeedsTheCursorFromTheOldAnchor() {
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
            // Derived rows from one of those logs -- and none from the other, which is the shape
            // the old cursor bug left behind: raw bytes with nothing derived from them.
            db.execSQL(
                "INSERT INTO cgm_readings (glucoseMgDl, trendArrow, source, timestampMs) " +
                    "VALUES (118, 'FLAT', '', 1000)",
            )
            db.execSQL(
                "INSERT INTO sync_queue " +
                    "(eventType, eventTimestampMs, payload, status, retryCount, createdAtMs, lastAttemptMs) " +
                    "VALUES ('bolus', 1700000000000, '{}', 'pending', 0, 1000, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, *DatabaseModule.ALL_MIGRATIONS)

        db.query("SELECT sequenceNumber, rawBytesB64, processed FROM raw_history_logs ORDER BY sequenceNumber")
            .use { cursor ->
                assertTrue("first raw row should survive the migration", cursor.moveToFirst())
                assertEquals(100, cursor.getInt(0))
                assertEquals("dGVzdA==", cursor.getString(1))
                // NOT marked processed. Nothing recorded which v13 batch died between its raw
                // insert and its derived writes, so declaring these done would hide the only
                // recoverable copy of whatever the old build dropped -- on every existing
                // device, in a step that cannot be walked back. Re-deriving is idempotent; the
                // re-derivation pass finds these rows precisely because they are still at 0.
                assertEquals("retained v13 rows stay recoverable", 0, cursor.getInt(2))

                assertTrue("second raw row should survive the migration", cursor.moveToNext())
                assertEquals(4200, cursor.getInt(0))
                assertEquals(0, cursor.getInt(2))
                assertFalse("no extra raw rows", cursor.moveToNext())
            }

        db.query("SELECT id, processedThroughSequence FROM history_backfill_cursor").use { cursor ->
            assertTrue("the cursor row must exist after the migration", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            // Exactly the anchor the old build was using: an upgrade must not re-read the
            // pump's whole history from BLE, and must not skip anything either. Local
            // re-derivation covers the rows above, without asking the pump again.
            assertEquals(4200, cursor.getInt(1))
            assertFalse("the cursor table holds a single row", cursor.moveToNext())
        }

        db.query("SELECT glucoseMgDl FROM cgm_readings").use { cursor ->
            assertTrue("derived rows are untouched by the migration", cursor.moveToFirst())
            assertEquals(118, cursor.getInt(0))
        }

        db.query("SELECT eventType, dedupeKey FROM sync_queue").use { cursor ->
            assertTrue("queued uploads survive the migration", cursor.moveToFirst())
            assertEquals("bolus", cursor.getString(0))
            // Pre-existing rows get no key. SQLite treats NULLs in a unique index as distinct,
            // so the new index cannot collapse or drop anything already waiting to upload.
            assertTrue("pre-migration queue rows carry no dedupe key", cursor.isNull(1))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun migrate13To14_addsAUniqueDedupeIndexThatOnlyBindsWhereTheKeyIsSet() {
        helper.createDatabase(TEST_DB_QUEUE, 13).use { db ->
            db.execSQL(
                "INSERT INTO sync_queue " +
                    "(eventType, eventTimestampMs, payload, status, retryCount, createdAtMs, lastAttemptMs) " +
                    "VALUES ('basal', 1700000000000, '{}', 'pending', 0, 1000, 0)",
            )
            db.execSQL(
                "INSERT INTO sync_queue " +
                    "(eventType, eventTimestampMs, payload, status, retryCount, createdAtMs, lastAttemptMs) " +
                    "VALUES ('basal', 1700000000000, '{}', 'pending', 0, 1000, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_QUEUE, 14, true, *DatabaseModule.ALL_MIGRATIONS,
        )

        // Two identical keyless rows coexisted before the migration and still do after it -- the
        // live poll paths keep their insert-always semantics.
        db.query("SELECT COUNT(*) FROM sync_queue").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        db.execSQL(
            "INSERT INTO sync_queue " +
                "(eventType, eventTimestampMs, payload, dedupeKey, status, retryCount, createdAtMs, lastAttemptMs) " +
                "VALUES ('bolus', 1700000000000, '{}', 'k1', 'pending', 0, 1000, 0)",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO sync_queue " +
                "(eventType, eventTimestampMs, payload, dedupeKey, status, retryCount, createdAtMs, lastAttemptMs) " +
                "VALUES ('bolus', 1700000000000, '{}', 'k1', 'pending', 0, 1000, 0)",
        )

        db.query("SELECT COUNT(*) FROM sync_queue WHERE dedupeKey = 'k1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("a keyed row cannot be queued twice", 1, cursor.getInt(0))
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
        const val TEST_DB_QUEUE = "migration-13-14-queue-test"
    }
}
