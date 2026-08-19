package com.glycemicgpt.mobile.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores raw BLE history log bytes received from the Tandem pump.
 *
 * Preserves the exact binary record format for local diagnostics and
 * potential future history-replay features. Older builds also forwarded
 * these bytes to the backend's Tandem cloud-upload pipeline, which was
 * removed in PR1c; the backend continues to accept the field for
 * back-compat but discards it. The [sequenceNumber] is unique per pump
 * and used to deduplicate.
 */
@Entity(
    tableName = "raw_history_logs",
    indices = [Index(value = ["sequenceNumber"], unique = true)],
)
data class RawHistoryLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceNumber: Int,
    val rawBytesB64: String,
    val eventTypeId: Int,
    val pumpTimeSeconds: Long,
    val sentToBackend: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    /**
     * Whether the CGM / bolus / basal rows derived from these bytes -- and their upload rows --
     * are known to be committed (GLY-250).
     *
     * The backfill inserts a batch's raw rows FIRST, unprocessed, so the pump bytes survive
     * whatever happens next; the derived writes then flip this flag inside the same transaction
     * that writes them and advances the resume cursor.
     *
     * `false` means NOT KNOWN TO BE DERIVED, which is deliberately weaker than "definitely not
     * derived", and it is the safe direction: re-deriving is idempotent (the derived tables
     * collapse on their unique indices and the queue rows carry a dedupe key), whereas declaring
     * a row done when it isn't loses it for good. Three things leave a row here:
     *  - a batch whose derived writes have not run, or rolled back;
     *  - a batch that committed but could not build its upload rows, so the re-derivation pass
     *    still owes it a queue entry;
     *  - every row carried over by the upgrade from schema 13, where nothing recorded which
     *    batch the old cursor bug died in, so completeness is genuinely unknown.
     * All of them are re-derivable locally, without asking the pump again.
     */
    @ColumnInfo(defaultValue = "0") val processed: Boolean = false,
)
