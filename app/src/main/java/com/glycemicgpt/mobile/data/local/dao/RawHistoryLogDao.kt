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

    @Query("DELETE FROM raw_history_logs WHERE sentToBackend = 1 AND createdAtMs < :cutoffMs")
    suspend fun cleanup(cutoffMs: Long)

    /**
     * Highest raw sequence number stored. This tracks RAW download progress only and is NOT
     * the backfill resume anchor -- that is [HistoryBackfillCursorDao.getProcessedThroughSequence],
     * which moves only once the derived rows are committed. Using this as the anchor is the bug
     * GLY-250 fixed: it advances at the raw insert, so a failure before the derived writes
     * resumed above records nothing ever re-derived.
     */
    @Query("SELECT MAX(sequenceNumber) FROM raw_history_logs")
    suspend fun getMaxSequenceNumber(): Int?

    /**
     * Flags a batch's raw rows as derived. Called from inside the transaction that writes the
     * derived rows, over the sequence window the cursor is about to move across, so raw rows
     * and processing state can never disagree about a committed batch.
     *
     * A range rather than an id list: batches are sized by the BLE driver (larger still on a
     * full initial sync), and an `IN (:ids)` binding would eventually hit SQLite's ceiling on
     * bound variables.
     */
    @Query(
        """
        UPDATE raw_history_logs SET processed = 1
        WHERE processed = 0
          AND sequenceNumber > :fromExclusive
          AND sequenceNumber <= :throughInclusive
        """
    )
    suspend fun markProcessedThrough(fromExclusive: Int, throughInclusive: Int): Int

    /**
     * Raw rows whose derived records are missing, oldest first -- the input to a re-derivation
     * pass (GLY-251), which can rebuild them locally without re-reading the pump.
     */
    @Query("SELECT * FROM raw_history_logs WHERE processed = 0 ORDER BY sequenceNumber LIMIT :limit")
    suspend fun getUnprocessed(limit: Int = 200): List<RawHistoryLogEntity>

    /** How many raw rows still have no derived records. Diagnostics and re-derivation gating. */
    @Query("SELECT COUNT(*) FROM raw_history_logs WHERE processed = 0")
    suspend fun countUnprocessed(): Int

    /**
     * Purge every row except the highest-sequence one. Stand-down path only: with no backend
     * configured the raw upload copies are undeliverable regardless of sent status. Returns the
     * number of rows removed.
     *
     * The surviving max-sequence row is no longer what keeps the poller from re-reading the
     * pump's whole history -- the resume anchor lives in `history_backfill_cursor` now, and this
     * purge cannot touch it (GLY-250). Keeping the row is harmless and stays until the retention
     * anchor is revisited.
     */
    @Query(
        """
        DELETE FROM raw_history_logs
        WHERE sequenceNumber < (SELECT MAX(sequenceNumber) FROM raw_history_logs)
        """
    )
    suspend fun deleteAllButMaxSequence(): Int
}
