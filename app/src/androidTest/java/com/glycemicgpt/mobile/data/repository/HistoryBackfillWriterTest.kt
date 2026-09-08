// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glycemicgpt.mobile.data.local.AppDatabase
import com.glycemicgpt.mobile.data.local.AuthTokenStore
import com.glycemicgpt.mobile.data.remote.InstantAdapter
import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.CgmReading
import com.glycemicgpt.mobile.domain.model.CgmTrend
import com.glycemicgpt.mobile.domain.model.HistoryLogRecord
import com.glycemicgpt.mobile.domain.model.PumpActivityMode
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The data-integrity core of GLY-250, against a real on-disk SQLite database.
 *
 * The bug these tests exist to keep dead: the backfill inserted a batch's raw rows, took
 * `MAX(sequenceNumber)` off that table as the resume anchor, and only afterwards derived and
 * saved the CGM / bolus / basal rows. A process death or a Room failure in between left the
 * anchor above records nothing would ever re-derive -- permanent, silent loss of exactly the
 * data this app exists to keep.
 *
 * Killing a process mid-batch is, at the persistence layer, indistinguishable from a
 * transaction that never commits: SQLite's journal is what makes a torn write impossible, and
 * a kill can only land inside or outside a transaction. So "killed here" is injected two ways --
 * abandoning the writer between its two steps, and aborting the batch transaction at each of the
 * four points inside it -- and every case is then re-opened from the database FILE, so what the
 * assertions see is what a restarted process would see. `BackfillProcessKillTest` does the same
 * thing with a real process kill, against a real second process.
 *
 * Instrumented (needs real SQLite): run with
 * `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class HistoryBackfillWriterTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var writer: HistoryBackfillWriter

    /**
     * The enqueuer is mode-gated on a configured backend, and the queue rows are half of what the
     * batch transaction has to keep consistent -- so answer the gate directly instead of writing
     * a fake server address into the device's real credential store. The store this subclasses
     * still reads the real encrypted prefs; it never writes them, so an aborted run cannot leave
     * the installed app pointed somewhere it should not be.
     */
    private open class FakeAuthTokenStore(context: Context) : AuthTokenStore(context, Dispatchers.IO) {
        override fun isBackendConfigured(): Boolean = true
    }

    /** A store whose mode gate is broken, e.g. a keystore flake. */
    private class FailingAuthTokenStore(context: Context) : FakeAuthTokenStore(context) {
        override fun isBackendConfigured(): Boolean = throw IllegalStateException("keystore flake")
    }

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_DB)
        openDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(TEST_DB)
    }

    private fun openDatabase(authTokenStore: AuthTokenStore = FakeAuthTokenStore(context)) {
        db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB).build()
        writer = HistoryBackfillWriter(
            db = db,
            rawHistoryLogDao = db.rawHistoryLogDao(),
            syncDao = db.syncDao(),
            cursorDao = db.historyBackfillCursorDao(),
            repository = PumpDataRepository(db.pumpDao()),
            syncEnqueuer = SyncQueueEnqueuer(
                syncDao = db.syncDao(),
                authTokenStore = authTokenStore,
                moshi = Moshi.Builder().add(InstantAdapter()).build(),
            ),
        )
    }

    /**
     * What a process kill looks like from the next launch: everything in memory is gone and the
     * database is re-opened from the file, journal and all.
     */
    private fun restartProcess() {
        db.close()
        openDatabase()
    }

    // -- fixtures --------------------------------------------------------------

    private val batchRecords = (1..3).map { i ->
        HistoryLogRecord(
            sequenceNumber = ANCHOR + i,
            rawBytesB64 = "cmF3JGk=",
            eventTypeId = 399,
            pumpTimeSeconds = 572_000_000L + i,
        )
    }

    private val batchCgm = (1..3).map { i ->
        CgmReading(
            glucoseMgDl = 110 + i,
            trendArrow = CgmTrend.FLAT,
            timestamp = Instant.ofEpochMilli(BASE_TIME_MS + i * 1_000L),
        )
    }

    private val batchBoluses = listOf(
        BolusEvent(
            units = 1.5f,
            isAutomated = false,
            isCorrection = false,
            timestamp = Instant.ofEpochMilli(BASE_TIME_MS + 10_000L),
        ),
    )

    private val batchBasal = listOf(
        BasalReading(
            rate = 0.8f,
            isAutomated = true,
            activityMode = PumpActivityMode.NONE,
            timestamp = Instant.ofEpochMilli(BASE_TIME_MS + 20_000L),
        ),
    )

    /** The whole batch, exactly as the poll loop runs it. */
    private suspend fun runWholeBatch(records: List<HistoryLogRecord> = batchRecords) {
        writer.persistRawBatch(records)
        writer.commitDerivedBatch(
            sequenceNumbers = records.map { it.sequenceNumber },
            cgmReadings = batchCgm,
            bolusEvents = batchBoluses,
            basalReadings = batchBasal,
            throughInclusive = records.maxOf { it.sequenceNumber },
        )
    }

    // -- observations ----------------------------------------------------------

    private data class DerivedSnapshot(
        val cgm: List<Pair<Int, Long>>,
        val boluses: List<Pair<Float, Long>>,
        val basal: List<Pair<Float, Long>>,
        val queuedEvents: List<String>,
    )

    private val emptySnapshot = DerivedSnapshot(emptyList(), emptyList(), emptyList(), emptyList())

    private fun snapshotDerived(): DerivedSnapshot = DerivedSnapshot(
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
            "SELECT eventType || '@' || eventTimestampMs FROM sync_queue ORDER BY eventTimestampMs",
        ) { it.getString(0) },
    )

    private fun <T> query(sql: String, read: (android.database.Cursor) -> T): List<T> =
        db.query(sql, emptyArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(read(cursor)) }
        }

    private fun storedCursor(): Int? =
        query("SELECT processedThroughSequence FROM history_backfill_cursor") { it.getInt(0) }
            .firstOrNull()

    private fun processedBySequence(): List<Pair<Int, Int>> =
        query("SELECT sequenceNumber, processed FROM raw_history_logs ORDER BY sequenceNumber") {
            it.getInt(0) to it.getInt(1)
        }

    private fun processedFlags(): List<Int> = processedBySequence().map { it.second }

    /** Makes the next insert into [table] fail, the way a kill makes the rest of a batch fail. */
    private fun abortInsertsOn(table: String) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $ABORT_TRIGGER BEFORE INSERT ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'simulated kill'); END",
        )
    }

    private fun stopAborting() {
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $ABORT_TRIGGER")
    }

    // -- tests -----------------------------------------------------------------

    @Test
    fun aCleanBatchCommitsEverythingAndMovesTheCursorLast() = runBlocking {
        assertNull("no cursor row means fresh install", writer.processedThroughSequence())

        runWholeBatch()
        restartProcess()

        val derived = snapshotDerived()
        assertEquals(3, derived.cgm.size)
        assertEquals(1, derived.boluses.size)
        assertEquals(1, derived.basal.size)
        assertEquals("one queue row per derived event", 2, derived.queuedEvents.size)
        assertEquals(ANCHOR + 3, writer.processedThroughSequence())
        assertEquals(listOf(1, 1, 1), processedFlags())
        assertEquals(0, db.rawHistoryLogDao().countUnprocessed())
    }

    @Test
    fun killedAfterTheRawInsertLosesNoDerivedRecords() = runBlocking {
        // The exact window the old code lost data in: raw rows down, derived writes not yet run.
        writer.persistRawBatch(batchRecords)
        restartProcess()

        // The raw bytes survived, flagged as owing derived records -- and findable as such, which
        // is what a later re-derivation pass runs off.
        assertEquals(listOf(0, 0, 0), processedFlags())
        assertEquals(3, db.rawHistoryLogDao().countUnprocessed())
        assertEquals(
            batchRecords.map { it.sequenceNumber },
            db.rawHistoryLogDao().getUnprocessed().map { it.sequenceNumber },
        )
        // ...and, decisively, the resume cursor did NOT move over them. The old anchor
        // (MAX(sequenceNumber) of the raw table) would already read ANCHOR + 3 here.
        assertNull(writer.processedThroughSequence())
        assertEquals(emptySnapshot, snapshotDerived())

        // The next run re-fetches the same batch and completes it.
        runWholeBatch()
        restartProcess()
        assertEquals(cleanRunSnapshot(), snapshotDerived())
        assertEquals(ANCHOR + 3, writer.processedThroughSequence())
        assertEquals(listOf(1, 1, 1), processedFlags())
    }

    @Test
    fun aKillInsideTheBatchTransactionRollsTheWholeBatchBack() = runBlocking {
        // Four points inside the one transaction, in the order it writes them: the first
        // derived table, the second, the third, and the sync queue. Whichever one the kill
        // lands on, the batch has to come out as if it had never started.
        for (table in listOf("cgm_readings", "bolus_events", "basal_readings", "sync_queue")) {
            writer.persistRawBatch(batchRecords)
            abortInsertsOn(table)

            val failure = runCatching {
                writer.commitDerivedBatch(
                    sequenceNumbers = batchRecords.map { it.sequenceNumber },
                    cgmReadings = batchCgm,
                    bolusEvents = batchBoluses,
                    basalReadings = batchBasal,
                    throughInclusive = ANCHOR + 3,
                )
            }.exceptionOrNull()
            assertNotNull("aborting at $table must fail the batch", failure)

            stopAborting()
            restartProcess()

            assertEquals(
                "aborting at $table must leave no derived rows behind",
                emptySnapshot,
                snapshotDerived(),
            )
            assertNull("aborting at $table must not move the cursor", writer.processedThroughSequence())
            assertEquals(
                "aborting at $table must leave the raw rows owing derived records",
                listOf(0, 0, 0),
                processedFlags(),
            )

            // Clean slate for the next abort point.
            db.clearAllTables()
        }

        // And after all that, a clean run still produces exactly the clean-run record set.
        runWholeBatch()
        restartProcess()
        assertEquals(cleanRunSnapshot(), snapshotDerived())
    }

    @Test
    fun reProcessingARolledBackBatchIsIdempotent() = runBlocking {
        writer.persistRawBatch(batchRecords)
        abortInsertsOn("basal_readings")
        val failure = runCatching {
            writer.commitDerivedBatch(
                sequenceNumbers = batchRecords.map { it.sequenceNumber },
                cgmReadings = batchCgm,
                bolusEvents = batchBoluses,
                basalReadings = batchBasal,
                throughInclusive = ANCHOR + 3,
            )
        }.exceptionOrNull()
        assertNotNull("the batch must fail when a derived write does", failure)
        stopAborting()

        // Re-run it the way the next poll cycle would: same records, same sequence window.
        runWholeBatch()
        restartProcess()

        // Exactly one copy of everything -- the raw insert ignores duplicate sequence numbers,
        // the derived rows collapse on their unique timestamp indices, and the rolled-back
        // batch's queue rows never existed, so nothing is queued for upload twice.
        assertEquals(cleanRunSnapshot(), snapshotDerived())
        assertEquals(3, query("SELECT id FROM raw_history_logs") { it.getLong(0) }.size)
    }

    @Test
    fun reCommittingABatchDuplicatesNothing_includingItsUploads() = runBlocking {
        runWholeBatch()
        val afterFirst = snapshotDerived()

        // A committed batch CAN be re-offered: a commit that lands and then throws
        // CancellationException on the way out leaves the in-memory anchor behind, and a
        // progressive driver rescan re-serves the same records after a reconnect. The derived
        // tables collapse on their unique indices; the queue used to be the one table that did
        // not, so the replay queued -- and uploaded -- every bolus and basal rate twice.
        runWholeBatch()

        assertEquals(afterFirst, snapshotDerived())
        assertEquals(2, query("SELECT id FROM sync_queue") { it.getLong(0) }.size)
        assertEquals(3, query("SELECT id FROM raw_history_logs") { it.getLong(0) }.size)
    }

    @Test
    fun aDeliveredUploadDoesNotBlockTheSameEventBeingQueuedAgainLater() = runBlocking {
        runWholeBatch()
        // The queue processor deletes rows once they are uploaded; the dedupe key must not
        // become a permanent tombstone that silently swallows a genuinely new enqueue.
        db.openHelper.writableDatabase.execSQL("DELETE FROM sync_queue")

        runWholeBatch()

        assertEquals(cleanRunSnapshot().queuedEvents, snapshotDerived().queuedEvents)
    }

    @Test
    fun onlyTheBatchesOwnSequencesAreMarkedProcessed() = runBlocking {
        // A batch that straddles the cursor: the Tandem driver paginates by pump record index,
        // so a rescan after a reconnect re-serves sequences BELOW the cursor alongside new ones.
        // Marking a range above the cursor left those rows unprocessed forever, which is a flag
        // that does not mean what a re-derivation pass reads it as.
        val straddling = listOf(ANCHOR - 20, ANCHOR - 10, ANCHOR + 1, ANCHOR + 2).map { seq ->
            HistoryLogRecord(
                sequenceNumber = seq,
                rawBytesB64 = "cmF3JGk=",
                eventTypeId = 399,
                pumpTimeSeconds = 572_000_000L + seq,
            )
        }
        // A raw row this batch did NOT derive from, sitting inside the same numeric range.
        writer.persistRawBatch(
            listOf(
                HistoryLogRecord(
                    sequenceNumber = ANCHOR - 15,
                    rawBytesB64 = "b3RoZXI=",
                    eventTypeId = 399,
                    pumpTimeSeconds = 572_000_100L,
                ),
            ),
        )

        runWholeBatch(straddling)
        restartProcess()

        assertEquals(
            listOf(
                ANCHOR - 20 to 1,
                // Not part of the batch: a range-based UPDATE would have claimed this one.
                ANCHOR - 15 to 0,
                ANCHOR - 10 to 1,
                ANCHOR + 1 to 1,
                ANCHOR + 2 to 1,
            ),
            processedBySequence(),
        )
        assertEquals(ANCHOR + 2, writer.processedThroughSequence())
    }

    @Test
    fun aBatchThatCannotBuildItsUploadsCommitsButStaysFlaggedForReDerivation() = runBlocking {
        db.close()
        openDatabase(FailingAuthTokenStore(context))

        runWholeBatch()
        restartProcess()

        // The derived records are kept -- losing a batch of CGM/bolus/basal over a failure to
        // serialize an upload would be the worse trade -- and the cursor moves with them.
        val derived = snapshotDerived()
        assertEquals(3, derived.cgm.size)
        assertEquals(1, derived.boluses.size)
        assertEquals(1, derived.basal.size)
        assertEquals(ANCHOR + 3, writer.processedThroughSequence())
        // But the uploads are missing, so the raw rows stay findable. Marking them done here is
        // how a batch's boluses go permanently absent from the backend with nothing recording it.
        assertTrue("no queue rows were built", derived.queuedEvents.isEmpty())
        assertEquals(listOf(0, 0, 0), processedFlags())
        assertEquals(3, db.rawHistoryLogDao().countUnprocessed())
    }

    @Test
    fun theCursorNeverRewinds() = runBlocking {
        runWholeBatch()
        assertEquals(ANCHOR + 3, writer.processedThroughSequence())

        // A stale caller replaying an earlier window must not drag the anchor backwards --
        // that would re-download history the pump has already handed over.
        writer.commitDerivedBatch(
            sequenceNumbers = emptyList(),
            cgmReadings = emptyList(),
            bolusEvents = emptyList(),
            basalReadings = emptyList(),
            throughInclusive = ANCHOR,
        )

        assertEquals(ANCHOR + 3, writer.processedThroughSequence())
    }

    @Test
    fun anEmptyBatchIsStillRecordedAsProcessedThrough() = runBlocking {
        // A window the pump answered with nothing derivable is still a window that has been
        // processed; leaving the cursor behind would re-fetch it forever.
        writer.commitDerivedBatch(
            sequenceNumbers = emptyList(),
            cgmReadings = emptyList(),
            bolusEvents = emptyList(),
            basalReadings = emptyList(),
            throughInclusive = ANCHOR + 3,
        )
        restartProcess()

        assertEquals(ANCHOR + 3, writer.processedThroughSequence())
        assertEquals(ANCHOR + 3, storedCursor())
        assertTrue(snapshotDerived().queuedEvents.isEmpty())
    }

    /** The record set a full, uninterrupted run of [runWholeBatch] produces. */
    private fun cleanRunSnapshot() = DerivedSnapshot(
        cgm = batchCgm.map { it.glucoseMgDl to it.timestamp.toEpochMilli() },
        boluses = batchBoluses.map { it.units to it.timestamp.toEpochMilli() },
        basal = batchBasal.map { it.rate to it.timestamp.toEpochMilli() },
        // One upload row per derived event that has one -- CGM readings ride the raw-log
        // channel, so only the bolus and the basal are queued.
        queuedEvents = listOf(
            "bolus@${batchBoluses.single().timestamp.toEpochMilli()}",
            "basal@${batchBasal.single().timestamp.toEpochMilli()}",
        ),
    )

    private companion object {
        const val TEST_DB = "history-backfill-writer-test"
        const val ABORT_TRIGGER = "gly250_simulated_kill"
        const val ANCHOR = 500
        const val BASE_TIME_MS = 1_700_000_000_000L
    }
}
