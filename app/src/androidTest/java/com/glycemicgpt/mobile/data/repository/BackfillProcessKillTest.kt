// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.repository

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glycemicgpt.mobile.data.local.AppDatabase
import com.glycemicgpt.mobile.debug.BackfillKillHarnessReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The story's own validation step: kill the process mid-backfill, at several points, and check
 * that the derived record set matches a full clean run (GLY-250).
 *
 * This is a real kill of a real process. The backfill runs in `:backfillkill`
 * ([BackfillKillHarnessReceiver]) -- same package and uid as this test, so the same database file,
 * but a different process -- and this test drives it, parks it at a chosen point inside the batch,
 * kills it from the shell, and then inspects what SQLite left on disk. An in-process test cannot
 * do this: killing its own process ends the run before it can assert anything.
 *
 * Two properties per kill point, which together are AC1 and AC3:
 *
 *  - **Nothing partial.** After the kill, the derived record set is either empty or exactly the
 *    clean-run set, and the resume cursor agrees with it. A cursor that moved with records
 *    missing is the original bug.
 *  - **The next run repairs it.** Re-running the batch, the way the next poll cycle would,
 *    lands exactly the clean-run set: no records lost, no duplicate rows, and no duplicate
 *    upload queued.
 *
 * Instrumented, and it needs an emulator it may kill processes on:
 * `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class BackfillProcessKillTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Before
    fun setUp() {
        resetState()
    }

    @After
    fun tearDown() {
        resetState()
    }

    private fun resetState() {
        killHarnessProcess()
        clearMarkers()
        context.deleteDatabase(BackfillKillHarnessReceiver.TEST_DB)
    }

    // -- driving the other process ---------------------------------------------

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .bufferedReader().readText()
        }

    private fun marker(name: String) = File(context.filesDir, name)

    private fun clearMarkers() {
        listOf(
            BackfillKillHarnessReceiver.READY_FILE,
            BackfillKillHarnessReceiver.DONE_FILE,
            BackfillKillHarnessReceiver.FAILED_FILE,
        ).forEach { marker(it).delete() }
    }

    private fun startHarness(killPoint: String) {
        clearMarkers()
        context.sendBroadcast(
            Intent(context, BackfillKillHarnessReceiver::class.java)
                .setAction(BackfillKillHarnessReceiver.ACTION_RUN)
                .putExtra(BackfillKillHarnessReceiver.EXTRA_KILL_POINT, killPoint),
        )
    }

    private fun awaitMarker(name: String, what: String): String {
        val file = marker(name)
        val failed = marker(BackfillKillHarnessReceiver.FAILED_FILE)
        val deadline = System.currentTimeMillis() + MARKER_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (file.exists()) return file.readText()
            if (failed.exists()) throw AssertionError("harness failed during $what: ${failed.readText()}")
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("timed out waiting for $what (marker $name)")
    }

    /** Runs the whole batch to completion in a fresh process, the way the next poll cycle would. */
    private fun runCleanBatch() {
        startHarness(NO_KILL_POINT)
        awaitMarker(BackfillKillHarnessReceiver.DONE_FILE, "a clean batch")
        killHarnessProcess()
    }

    private fun killHarnessProcess() {
        shell("am kill --user all ${context.packageName}")
    }

    /**
     * Kills the harness where it is parked and does not return until its pid is genuinely gone.
     * `am kill` reaps the package's background processes; this test process is running an
     * instrumentation, which keeps it out of that set.
     */
    private fun killParkedHarness(pid: Int) {
        killHarnessProcess()
        val deadline = System.currentTimeMillis() + KILL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!File("/proc/$pid").exists()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("pid $pid was still alive ${KILL_TIMEOUT_MS}ms after am kill")
    }

    // -- reading what survived --------------------------------------------------

    private data class Durable(
        val cgm: List<Pair<Int, Long>>,
        val boluses: List<Pair<Float, Long>>,
        val basal: List<Pair<Float, Long>>,
        val queuedEvents: List<String>,
        val cursor: Int?,
        val rawSequences: List<Int>,
    )

    /**
     * Opens the shared database FILE the way a restarted app would and reads everything that
     * matters. Closed again immediately so the next harness process has it to itself.
     */
    private fun readDurableState(): Durable {
        val db = Room.databaseBuilder(
            context, AppDatabase::class.java, BackfillKillHarnessReceiver.TEST_DB,
        ).build()
        try {
            fun <T> query(sql: String, read: (android.database.Cursor) -> T): List<T> =
                db.query(sql, emptyArray()).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(read(cursor)) }
                }
            return Durable(
                cgm = query("SELECT glucoseMgDl, timestampMs FROM cgm_readings ORDER BY timestampMs") {
                    it.getInt(0) to it.getLong(1)
                },
                boluses = query("SELECT units, timestampMs FROM bolus_events ORDER BY timestampMs") {
                    it.getFloat(0) to it.getLong(1)
                },
                basal = query("SELECT rate, timestampMs FROM basal_readings ORDER BY timestampMs") {
                    it.getFloat(0) to it.getLong(1)
                },
                queuedEvents = query(
                    "SELECT eventType || '@' || eventTimestampMs FROM sync_queue ORDER BY eventTimestampMs, eventType",
                ) { it.getString(0) },
                cursor = query("SELECT processedThroughSequence FROM history_backfill_cursor") {
                    it.getInt(0)
                }.firstOrNull(),
                rawSequences = query("SELECT sequenceNumber FROM raw_history_logs ORDER BY sequenceNumber") {
                    it.getInt(0)
                },
            )
        } finally {
            db.close()
        }
    }

    private fun Durable.derivedIsEmpty() =
        cgm.isEmpty() && boluses.isEmpty() && basal.isEmpty() && queuedEvents.isEmpty()

    private fun Durable.sameDerivedAs(other: Durable) =
        cgm == other.cgm && boluses == other.boluses && basal == other.basal &&
            queuedEvents == other.queuedEvents

    // -- tests ------------------------------------------------------------------

    @Test
    fun killingTheProcessMidBatchLosesNothingAndTheNextRunMatchesACleanRun() {
        // The yardstick: one uninterrupted run, in its own process, read back off disk.
        runCleanBatch()
        val cleanRun = readDurableState()
        assertTrue("the clean run must actually produce records", cleanRun.cgm.isNotEmpty())
        assertEquals(
            "a clean run leaves the cursor at the batch max",
            cleanRun.rawSequences.max(),
            cleanRun.cursor,
        )

        for ((killPoint, expectCommitted) in KILL_POINTS) {
            context.deleteDatabase(BackfillKillHarnessReceiver.TEST_DB)

            startHarness(killPoint)
            val pid = awaitMarker(BackfillKillHarnessReceiver.READY_FILE, "the harness to park at $killPoint")
                .trim().toInt()
            killParkedHarness(pid)

            // Nothing partial. Either the batch's transaction committed or it did not, and the
            // cursor says which -- it can never be ahead of the records.
            val afterKill = readDurableState()
            val committed = afterKill.cursor != null
            // Also pins where the kill actually landed, so a harness that stopped parking inside
            // the transaction fails here instead of passing on the easy half of the property.
            assertEquals(
                "killed at $killPoint: wrong side of the transaction boundary",
                expectCommitted, committed,
            )
            if (committed) {
                assertTrue(
                    "killed at $killPoint: the cursor moved, so every derived record must be there",
                    afterKill.sameDerivedAs(cleanRun),
                )
            } else {
                assertTrue(
                    "killed at $killPoint: no cursor, so nothing derived may have survived " +
                        "(got ${afterKill.cgm.size} cgm, ${afterKill.boluses.size} bolus, " +
                        "${afterKill.basal.size} basal, ${afterKill.queuedEvents.size} queued)",
                    afterKill.derivedIsEmpty(),
                )
                // The raw bytes are kept regardless -- they are the only copy of what the pump
                // said, and a re-derivation pass runs off exactly these.
                assertEquals(
                    "killed at $killPoint: the raw bytes must survive the kill",
                    cleanRun.rawSequences, afterKill.rawSequences,
                )
            }

            // ...and the next run repairs it, landing exactly the clean-run set: nothing lost,
            // nothing duplicated, and no second upload queued for a batch that already has one.
            runCleanBatch()
            val afterRerun = readDurableState()
            assertEquals("killed at $killPoint: CGM after the re-run", cleanRun.cgm, afterRerun.cgm)
            assertEquals("killed at $killPoint: boluses after the re-run", cleanRun.boluses, afterRerun.boluses)
            assertEquals("killed at $killPoint: basal after the re-run", cleanRun.basal, afterRerun.basal)
            assertEquals("killed at $killPoint: queued uploads after the re-run", cleanRun.queuedEvents, afterRerun.queuedEvents)
            assertEquals("killed at $killPoint: raw rows after the re-run", cleanRun.rawSequences, afterRerun.rawSequences)
            assertEquals("killed at $killPoint: cursor after the re-run", cleanRun.cursor, afterRerun.cursor)
        }
    }

    @Test
    fun killingBetweenTheRawInsertAndTheCommitLeavesTheCursorWhereItWas() {
        // Called out on its own because it is the exact window the old code lost data in: the
        // raw rows are down, the derived writes have not run, and the anchor used to be
        // MAX(sequenceNumber) of the raw table -- already past them.
        startHarness(BackfillKillHarnessReceiver.KillPoint.AFTER_RAW)
        val pid = awaitMarker(BackfillKillHarnessReceiver.READY_FILE, "the harness to park after the raw insert")
            .trim().toInt()
        killParkedHarness(pid)

        val afterKill = readDurableState()
        assertNull("the cursor must not have moved over underived records", afterKill.cursor)
        assertTrue("nothing derived can exist yet", afterKill.derivedIsEmpty())
        assertTrue("the raw bytes survive, ready to be re-derived", afterKill.rawSequences.isNotEmpty())
    }

    private companion object {
        /**
         * Every point the harness can be parked at, and whether the batch transaction is
         * expected to have committed by the time the kill lands. Everything before the cursor
         * write is inside the transaction and must roll back; the last one is the check that a
         * committed batch really is durable across a kill.
         */
        val KILL_POINTS = listOf(
            BackfillKillHarnessReceiver.KillPoint.AFTER_RAW to false,
            BackfillKillHarnessReceiver.KillPoint.IN_TRANSACTION_QUEUE to false,
            BackfillKillHarnessReceiver.KillPoint.IN_TRANSACTION_MARK to false,
            BackfillKillHarnessReceiver.KillPoint.IN_TRANSACTION_CURSOR to false,
            BackfillKillHarnessReceiver.KillPoint.AFTER_COMMIT to true,
        )

        const val NO_KILL_POINT = ""
        const val MARKER_TIMEOUT_MS = 60_000L
        const val KILL_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
