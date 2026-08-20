// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.Room
import com.glycemicgpt.mobile.data.local.AppDatabase
import com.glycemicgpt.mobile.data.local.AuthTokenStore
import com.glycemicgpt.mobile.data.local.dao.HistoryBackfillCursorDao
import com.glycemicgpt.mobile.data.local.dao.RawHistoryLogDao
import com.glycemicgpt.mobile.data.local.dao.SyncDao
import com.glycemicgpt.mobile.data.remote.InstantAdapter
import com.glycemicgpt.mobile.data.repository.HistoryBackfillWriter
import com.glycemicgpt.mobile.data.repository.PumpDataRepository
import com.glycemicgpt.mobile.data.repository.SyncQueueEnqueuer
import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.CgmReading
import com.glycemicgpt.mobile.domain.model.CgmTrend
import com.glycemicgpt.mobile.domain.model.HistoryLogRecord
import com.glycemicgpt.mobile.domain.model.PumpActivityMode
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.time.Instant

/**
 * Runs one history-backfill batch in a SEPARATE PROCESS so an instrumented test can kill that
 * process for real, mid-batch, and still be alive afterwards to assert what survived (GLY-250).
 *
 * Why it exists at all: an in-process test cannot outlive killing its own process, and a kill is
 * the failure this story is about. So the work moves to `:backfillkill` -- same package, same
 * uid, same database file, different process -- and the test process drives it, kills it and
 * inspects the wreckage. See `BackfillProcessKillTest`.
 *
 * Debug-only by construction: this file lives in the `debug` source set and is not compiled into
 * release builds at all.
 *
 * The batch stalls at whichever [KillPoint] it was asked for, having written [READY_FILE] with
 * its own pid. Stalling inside the batch transaction means stalling while holding SQLite's write
 * lock, which is exactly the state a kill has to be survivable from. Kill points are injected by
 * wrapping the DAO interfaces the writer already takes, so the production path under test is the
 * real one -- nothing here reaches into [HistoryBackfillWriter].
 *
 * A receiver rather than a service, for two reasons: an explicit broadcast starts the process
 * without running into the platform's background service-start rules, and once `onReceive`
 * returns the process holds no component at all, so `am kill` reliably reaps it.
 */
class BackfillKillHarnessReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val killPoint = intent.getStringExtra(EXTRA_KILL_POINT).orEmpty()
        val appContext = context.applicationContext
        // Off the main thread and out of onReceive promptly: while onReceive runs the process
        // counts as busy, and it has to be an ordinary background one by the time it is killed.
        Thread({ runBatch(appContext, killPoint) }, "gly250-backfill-harness").start()
    }

    companion object {
        /** Explicit-component action; nothing else listens for it. */
        const val ACTION_RUN = "com.glycemicgpt.mobile.debug.RUN_BACKFILL_BATCH"

        const val EXTRA_KILL_POINT = "kill_point"

        /** Written once the harness has reached its kill point; contains its pid. */
        const val READY_FILE = "gly250-harness-ready"

        /** Written when a phase ran to completion without being killed. */
        const val DONE_FILE = "gly250-harness-done"

        /** Written if the phase threw; contains the exception class and message. */
        const val FAILED_FILE = "gly250-harness-failed"

        /** The database the harness and the test share. Never the production database. */
        const val TEST_DB = "gly250-process-kill-test"

        /** Anchor the fixture batch sits above. */
        const val ANCHOR = 500
        const val BASE_TIME_MS = 1_700_000_000_000L
    }

    /** Where a kill is made to land. Empty string means "run the whole batch, kill nothing". */
    object KillPoint {
        /** Between the raw insert and the derived commit -- the window the old code lost data in. */
        const val AFTER_RAW = "after_raw"

        /** Inside the batch transaction, as the upload rows go in. */
        const val IN_TRANSACTION_QUEUE = "in_txn_queue"

        /** Inside the batch transaction, as the raw rows are flagged processed. */
        const val IN_TRANSACTION_MARK = "in_txn_mark"

        /** Inside the batch transaction, with everything written but the cursor. */
        const val IN_TRANSACTION_CURSOR = "in_txn_cursor"

        /** Immediately after the transaction commits, before anything in memory moves on. */
        const val AFTER_COMMIT = "after_commit"

        val ALL = listOf(
            AFTER_RAW, IN_TRANSACTION_QUEUE, IN_TRANSACTION_MARK, IN_TRANSACTION_CURSOR,
            AFTER_COMMIT,
        )
    }
}

// -- the batch ---------------------------------------------------------------

private val fixtureRecords = (1..3).map { i ->
    HistoryLogRecord(
        sequenceNumber = BackfillKillHarnessReceiver.ANCHOR + i,
        rawBytesB64 = "cmF3JGk=",
        eventTypeId = 399,
        pumpTimeSeconds = 572_000_000L + i,
    )
}

private val fixtureCgm = (1..3).map { i ->
    CgmReading(
        glucoseMgDl = 110 + i,
        trendArrow = CgmTrend.FLAT,
        timestamp = Instant.ofEpochMilli(BackfillKillHarnessReceiver.BASE_TIME_MS + i * 1_000L),
    )
}

private val fixtureBoluses = listOf(
    BolusEvent(
        units = 1.5f,
        isAutomated = false,
        isCorrection = false,
        timestamp = Instant.ofEpochMilli(BackfillKillHarnessReceiver.BASE_TIME_MS + 10_000L),
    ),
)

private val fixtureBasal = listOf(
    BasalReading(
        rate = 0.8f,
        isAutomated = true,
        activityMode = PumpActivityMode.NONE,
        timestamp = Instant.ofEpochMilli(BackfillKillHarnessReceiver.BASE_TIME_MS + 20_000L),
    ),
)

/**
 * Publishes a marker the test process polls for, atomically.
 *
 * The test returns from its wait the moment the file exists and then parses what it reads --
 * the ready marker's contents are a pid it is about to kill. A plain `writeText` creates the
 * file and writes it in two steps, so a poll landing between them reads an empty string and the
 * suite dies on `NumberFormatException` instead of reporting a durability result. Staging under
 * a temporary name and renaming makes the marker appear only once it is complete; both names
 * are in `filesDir`, so the rename is a same-filesystem `rename(2)`.
 */
private fun publishMarker(context: Context, name: String, contents: String) {
    val marker = File(context.filesDir, name)
    val staging = File(context.filesDir, "$name.tmp")
    staging.writeText(contents)
    if (!staging.renameTo(marker)) {
        // The test times out on the missing marker either way; this says why.
        Timber.e("Could not publish harness marker %s", name)
    }
}

private fun runBatch(context: Context, killPoint: String) {
    val stall = { point: String ->
        if (point == killPoint) {
            publishMarker(
                context,
                BackfillKillHarnessReceiver.READY_FILE,
                android.os.Process.myPid().toString(),
            )
            Timber.i("Backfill harness parked at %s, waiting to be killed", point)
            while (true) Thread.sleep(50L)
        }
    }

    try {
        val db = Room.databaseBuilder(
            context, AppDatabase::class.java, BackfillKillHarnessReceiver.TEST_DB,
        ).build()
        val writer = HistoryBackfillWriter(
            db = db,
            rawHistoryLogDao = StallingRawHistoryLogDao(db.rawHistoryLogDao(), stall),
            syncDao = StallingSyncDao(db.syncDao(), stall),
            cursorDao = StallingCursorDao(db.historyBackfillCursorDao(), stall),
            repository = PumpDataRepository(db.pumpDao()),
            syncEnqueuer = SyncQueueEnqueuer(
                syncDao = db.syncDao(),
                authTokenStore = AlwaysConfiguredAuthTokenStore(context),
                moshi = Moshi.Builder().add(InstantAdapter()).build(),
            ),
        )

        runBlocking {
            writer.persistRawBatch(fixtureRecords)
            stall(BackfillKillHarnessReceiver.KillPoint.AFTER_RAW)
            writer.commitDerivedBatch(
                sequenceNumbers = fixtureRecords.map { it.sequenceNumber },
                cgmReadings = fixtureCgm,
                bolusEvents = fixtureBoluses,
                basalReadings = fixtureBasal,
                throughInclusive = fixtureRecords.maxOf { it.sequenceNumber },
            )
            stall(BackfillKillHarnessReceiver.KillPoint.AFTER_COMMIT)
        }
        db.close()
        publishMarker(context, BackfillKillHarnessReceiver.DONE_FILE, "ok")
    } catch (e: Throwable) {
        publishMarker(
            context,
            BackfillKillHarnessReceiver.FAILED_FILE,
            "${e.javaClass.name}: ${e.message}",
        )
        Timber.e(e, "Backfill harness failed")
    }
}

/** Reports a configured backend without touching the device's real credential store. */
private class AlwaysConfiguredAuthTokenStore(context: Context) :
    AuthTokenStore(context, Dispatchers.IO) {
    override fun isBackendConfigured(): Boolean = true
}

private class StallingSyncDao(
    private val delegate: SyncDao,
    private val stall: (String) -> Unit,
) : SyncDao by delegate {
    override suspend fun enqueueAllIgnoringDuplicates(entities: List<com.glycemicgpt.mobile.data.local.entity.SyncQueueEntity>) {
        delegate.enqueueAllIgnoringDuplicates(entities)
        stall(BackfillKillHarnessReceiver.KillPoint.IN_TRANSACTION_QUEUE)
    }
}

private class StallingRawHistoryLogDao(
    private val delegate: RawHistoryLogDao,
    private val stall: (String) -> Unit,
) : RawHistoryLogDao by delegate {
    override suspend fun markProcessed(sequenceNumbers: List<Int>): Int {
        val marked = delegate.markProcessed(sequenceNumbers)
        stall(BackfillKillHarnessReceiver.KillPoint.IN_TRANSACTION_MARK)
        return marked
    }
}

private class StallingCursorDao(
    private val delegate: HistoryBackfillCursorDao,
    private val stall: (String) -> Unit,
) : HistoryBackfillCursorDao by delegate {
    override suspend fun advanceTo(sequence: Int, nowMs: Long): Int {
        // Before the delegate: everything else in the transaction is written and the cursor is
        // the one thing outstanding, which is the interleaving the whole story is about.
        stall(BackfillKillHarnessReceiver.KillPoint.IN_TRANSACTION_CURSOR)
        return delegate.advanceTo(sequence, nowMs)
    }
}
