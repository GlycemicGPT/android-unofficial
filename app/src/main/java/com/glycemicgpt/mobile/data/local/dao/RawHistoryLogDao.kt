package com.glycemicgpt.mobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glycemicgpt.mobile.data.local.entity.RawHistoryLogEntity

@Dao
interface RawHistoryLogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(logs: List<RawHistoryLogEntity>)

    @Query("SELECT * FROM raw_history_logs WHERE sentToBackend = 0 ORDER BY sequenceNumber LIMIT :limit")
    suspend fun getUnsent(limit: Int = 100): List<RawHistoryLogEntity>

    @Query("UPDATE raw_history_logs SET sentToBackend = 1 WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>)

    /**
     * Age out raw rows already delivered to the backend.
     *
     * NOTE: nothing calls this today (the sweep in `BackendSyncManager` calls `SyncDao.cleanup`).
     * If it is ever wired up it needs `AND processed = 1` as well, or it will reap rows whose
     * derived records were never written -- the input a re-derivation pass runs off. The purge
     * that DOES run is [deleteAllButMaxSequence]; see its KDoc.
     */
    @Query("DELETE FROM raw_history_logs WHERE sentToBackend = 1 AND createdAtMs < :cutoffMs")
    suspend fun cleanup(cutoffMs: Long)

    /**
     * Flags exactly the rows a committed batch derived from. Called from inside the transaction
     * that writes the derived rows, so raw rows and processing state can never disagree about a
     * committed batch.
     *
     * The sequences are listed explicitly rather than marked as a range above the cursor
     * (GLY-250). A batch is whatever the driver hands over, which is not the same set as
     * "everything above the old cursor": the Tandem driver paginates by pump record INDEX and
     * re-serves records well below the cursor on a rescan, and a range-based UPDATE left those
     * rows unprocessed forever while also being able to mark rows this batch never derived.
     * Callers chunk long batches -- see [MAX_SEQUENCES_PER_MARK].
     */
    @Query("UPDATE raw_history_logs SET processed = 1 WHERE sequenceNumber IN (:sequenceNumbers)")
    suspend fun markProcessed(sequenceNumbers: List<Int>): Int

    /**
     * Raw rows not known to have their derived records committed, oldest first -- the input to a
     * re-derivation pass (GLY-251), which can rebuild them locally without re-reading the pump.
     */
    @Query("SELECT * FROM raw_history_logs WHERE processed = 0 ORDER BY sequenceNumber LIMIT :limit")
    suspend fun getUnprocessed(limit: Int = 200): List<RawHistoryLogEntity>

    /** How many raw rows are not known to be derived. Diagnostics and re-derivation gating. */
    @Query("SELECT COUNT(*) FROM raw_history_logs WHERE processed = 0")
    suspend fun countUnprocessed(): Int

    /**
     * Purge rows that are neither deliverable nor recoverable, keeping the highest-sequence one.
     * Stand-down path only: with no backend configured the raw upload copies are undeliverable
     * regardless of sent status. Returns the number of rows removed.
     *
     * `processed = 1` is the second condition, added with the flag itself (GLY-250). This purge
     * is the one that actually runs on a BLE-only device, on every stand-down sweep, and without
     * the condition it deletes exactly the rows a re-derivation pass exists to rebuild from --
     * including every row an upgrade from schema 13 carried over as "derivation unknown". A row
     * still owing derived records is not dead weight, so it stays.
     *
     * The surviving max-sequence row is no longer what keeps the poller from re-reading the
     * pump's whole history -- the resume anchor lives in `history_backfill_cursor` now, and this
     * purge cannot touch it. Keeping the row is harmless and stays until the retention anchor is
     * revisited.
     */
    @Query(
        """
        DELETE FROM raw_history_logs
        WHERE processed = 1
          AND sequenceNumber < (SELECT MAX(sequenceNumber) FROM raw_history_logs)
        """
    )
    suspend fun deleteAllButMaxSequence(): Int

    companion object {
        /**
         * Sequences per [markProcessed] call. Each one is a bound variable and SQLite's ceiling
         * is 999 on the oldest Android versions this app supports; a full initial sync hands over
         * batches of up to 500 records, so callers chunk rather than assume they fit.
         */
        const val MAX_SEQUENCES_PER_MARK = 400
    }
}
