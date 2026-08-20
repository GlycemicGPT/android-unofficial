package com.glycemicgpt.mobile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local sync queue for pump events waiting to be pushed to the backend.
 *
 * Each row represents a single pump event that needs to be synced.
 * The queue processor picks up PENDING items in batches, marks them SENDING,
 * then either deletes (on success) or marks FAILED (on error).
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["status", "createdAtMs"]),
        Index(value = ["dedupeKey"], unique = true),
    ],
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val eventTimestampMs: Long,
    /** JSON-serialized PumpEventDto payload. */
    val payload: String,
    /**
     * Identity of the pump event this row uploads, for rows that must not be queued twice
     * (GLY-250). Unique where set; NULL where not.
     *
     * History-backfill rows carry one because re-processing a batch has to be idempotent all the
     * way to the upload: the derived tables collapse duplicates on their unique indices and this
     * queue used to be the one table that did not, so a replayed batch uploaded its boluses and
     * basal rates twice and ate the queue's size budget doing it.
     *
     * The live poll paths leave it NULL and keep their old insert-always semantics -- SQLite
     * treats NULLs in a unique index as distinct, so nothing there changes. Dedupe covers rows
     * still WAITING to upload; once a row is delivered and deleted the key is free again, which
     * is the intended scope (the backend dedupes on its own natural keys).
     */
    val dedupeKey: String? = null,
    val status: String = STATUS_PENDING,
    val retryCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastAttemptMs: Long = 0L,
    val errorMessage: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_SENDING = "sending"
        const val STATUS_FAILED = "failed"
    }
}
