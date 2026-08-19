// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The history backfill resume cursor: the highest pump sequence number whose DERIVED data
 * (CGM / bolus / basal rows plus their sync-queue entries) is committed.
 *
 * Single-row table, keyed on [CURSOR_ROW_ID].
 *
 * It exists as its own table rather than as `MAX(sequenceNumber)` over `raw_history_logs`
 * because those are two different kinds of progress and they are allowed to diverge
 * (GLY-250). The raw rows are inserted first so the pump bytes survive a failure; the cursor
 * moves only inside the transaction that commits everything derived from them. Reading the
 * resume anchor off the raw table conflated the two, so a process death between the raw
 * insert and the derived writes resumed ABOVE records that were never derived — silent,
 * permanent loss of CGM/bolus/basal data.
 *
 * A missing row means "nothing has ever been processed" (fresh install), which is the same
 * thing an empty raw table used to mean. A read failure is NOT that, and callers must keep
 * telling the two apart.
 */
@Entity(tableName = "history_backfill_cursor")
data class HistoryBackfillCursorEntity(
    @PrimaryKey val id: Int = CURSOR_ROW_ID,
    val processedThroughSequence: Int,
    val updatedAtMs: Long,
) {
    companion object {
        /** The only row this table ever holds. */
        const val CURSOR_ROW_ID = 0
    }
}
