// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.glycemicgpt.mobile.data.local.entity.HistoryBackfillCursorEntity

/**
 * Reads and advances the history backfill resume cursor
 * ([HistoryBackfillCursorEntity]).
 *
 * Writers must call [seed] then [advanceTo] from inside the same transaction as the derived
 * writes they are recording — that ordering is the whole point of the cursor.
 */
@Dao
interface HistoryBackfillCursorDao {

    /**
     * The highest sequence number whose derived data is committed, or null when the cursor has
     * never been written (fresh install / post-destructive-migration). Null is the only value
     * that may be read as "start from the beginning"; an exception must not be.
     */
    @Query("SELECT processedThroughSequence FROM history_backfill_cursor WHERE id = 0")
    suspend fun getProcessedThroughSequence(): Int?

    /** Creates the single row if it is missing; leaves an existing one untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seed(cursor: HistoryBackfillCursorEntity)

    /**
     * Moves the cursor forward, never backward — the `<` guard makes a stale or out-of-order
     * caller a no-op instead of a rewind that would re-fetch history the pump has already
     * given us. Returns the number of rows updated (0 when the cursor is already at or past
     * [sequence]).
     */
    @Query(
        """
        UPDATE history_backfill_cursor
        SET processedThroughSequence = :sequence, updatedAtMs = :nowMs
        WHERE id = 0 AND processedThroughSequence < :sequence
        """
    )
    suspend fun advanceTo(sequence: Int, nowMs: Long): Int
}
