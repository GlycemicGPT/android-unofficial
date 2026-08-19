// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.repository

import androidx.room.withTransaction
import com.glycemicgpt.mobile.data.local.AppDatabase
import com.glycemicgpt.mobile.data.local.dao.HistoryBackfillCursorDao
import com.glycemicgpt.mobile.data.local.dao.RawHistoryLogDao
import com.glycemicgpt.mobile.data.local.dao.SyncDao
import com.glycemicgpt.mobile.data.local.entity.HistoryBackfillCursorEntity
import com.glycemicgpt.mobile.data.local.entity.RawHistoryLogEntity
import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.CgmReading
import com.glycemicgpt.mobile.domain.model.HistoryLogRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for one history-backfill batch, in two steps with an explicit boundary between
 * them (GLY-250).
 *
 * The old path wrote raw rows, took `MAX(sequenceNumber)` off that table as the resume anchor,
 * and only then derived and saved CGM / bolus / basal rows. Anything that went wrong in between
 * -- process death, a Room failure, an unparseable record -- left the anchor above records
 * nothing would ever re-derive: silent, permanent data loss on exactly the reconnect this app
 * exists to cover.
 *
 * The split here:
 *
 *  1. [persistRawBatch] stores the pump bytes, marked unprocessed. Raw data is the only copy of
 *     what the pump said, so it is written first and never rolled back for a derivation failure;
 *     an unprocessed row is re-derivable locally without asking the pump again.
 *  2. [commitDerivedBatch] writes the derived rows, their sync-queue entries, the `processed`
 *     flags and the resume cursor in ONE transaction. Either all of that is durable or none of
 *     it is, so a kill at any instant leaves the cursor where it was and the next run redoes the
 *     batch from the pump. Re-doing it is idempotent: raw inserts ignore duplicate sequence
 *     numbers, derived rows collapse on their unique timestamp indices, and the queue rows for a
 *     rolled-back batch never existed.
 *
 * Nothing here catches: callers run inside the poll loop's step guard, which reports the failure
 * and retries the batch on the next cycle.
 */
@Singleton
class HistoryBackfillWriter @Inject constructor(
    private val db: AppDatabase,
    private val rawHistoryLogDao: RawHistoryLogDao,
    private val syncDao: SyncDao,
    private val cursorDao: HistoryBackfillCursorDao,
    private val repository: PumpDataRepository,
    private val syncEnqueuer: SyncQueueEnqueuer,
) {

    /**
     * The resume anchor: the highest sequence number whose derived records are committed.
     *
     * Null means the cursor has never been written -- a genuinely fresh install. A Room failure
     * throws instead, and callers must keep treating the two differently: a transient DB error
     * that read as "fresh install" would re-download the pump's entire history behind the user's
     * back.
     */
    suspend fun processedThroughSequence(): Int? = cursorDao.getProcessedThroughSequence()

    /**
     * Step 1: store a batch's raw bytes, unprocessed. Duplicate sequence numbers are ignored, so
     * re-running a batch after a failure adds nothing.
     */
    suspend fun persistRawBatch(records: List<HistoryLogRecord>) {
        if (records.isEmpty()) return
        rawHistoryLogDao.insertAll(
            records.map { record ->
                RawHistoryLogEntity(
                    sequenceNumber = record.sequenceNumber,
                    rawBytesB64 = record.rawBytesB64,
                    eventTypeId = record.eventTypeId,
                    pumpTimeSeconds = record.pumpTimeSeconds,
                    processed = false,
                )
            },
        )
    }

    /**
     * Step 2: commit everything derived from a batch, atomically with the cursor that says so.
     *
     * [fromExclusive] is the cursor's value before this batch and [throughInclusive] the batch's
     * highest sequence number; together they are the window the batch covers, and the window the
     * `processed` flags are set over.
     *
     * The sync rows are built before the transaction opens -- serializing events and reading the
     * encrypted token store are not things to do while holding the write lock -- but inserted
     * inside it, so a rolled-back batch cannot leave queued uploads for records that no longer
     * exist.
     */
    suspend fun commitDerivedBatch(
        cgmReadings: List<CgmReading>,
        bolusEvents: List<BolusEvent>,
        basalReadings: List<BasalReading>,
        fromExclusive: Int,
        throughInclusive: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val queueRows = syncEnqueuer.buildBackfillRows(bolusEvents, basalReadings)
        db.withTransaction {
            // Same repository writes the live poll loops use, so the domain -> entity mapping
            // stays in one place; inside this block they join the batch's transaction.
            repository.saveCgmBatch(cgmReadings)
            repository.saveBoluses(bolusEvents)
            repository.saveBasalBatch(basalReadings)
            if (queueRows.isNotEmpty()) {
                syncDao.enqueueAll(queueRows)
            }
            rawHistoryLogDao.markProcessedThrough(fromExclusive, throughInclusive)
            // Seed-then-advance rather than an upsert: the update carries a `<` guard, so a
            // cursor that is already further along stays put instead of rewinding.
            cursorDao.seed(
                HistoryBackfillCursorEntity(
                    processedThroughSequence = throughInclusive,
                    updatedAtMs = nowMs,
                ),
            )
            cursorDao.advanceTo(throughInclusive, nowMs)
        }
    }
}
