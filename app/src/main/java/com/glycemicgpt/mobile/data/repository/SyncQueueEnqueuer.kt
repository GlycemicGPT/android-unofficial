package com.glycemicgpt.mobile.data.repository

import com.glycemicgpt.mobile.data.local.AuthTokenStore
import com.glycemicgpt.mobile.data.local.dao.SyncDao
import com.glycemicgpt.mobile.data.local.entity.SyncQueueEntity
import com.glycemicgpt.mobile.data.remote.PumpEventMapper
import com.glycemicgpt.mobile.data.remote.dto.PumpEventDto
import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BatteryStatus
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.IoBReading
import com.glycemicgpt.mobile.domain.model.ReservoirReading
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts domain models to PumpEventDto JSON and inserts them
 * into the sync_queue table for later upload.
 *
 * Enqueueing is gated on a backend being configured: with no base URL there is no
 * destination, so rows would only accumulate as undeliverable dead weight. Pump data
 * still reaches the local dashboard -- callers write to Room independently before
 * enqueueing.
 */
@Singleton
class SyncQueueEnqueuer @Inject constructor(
    private val syncDao: SyncDao,
    private val authTokenStore: AuthTokenStore,
    private val moshi: Moshi,
) {

    private val adapter = moshi.adapter(PumpEventDto::class.java)

    suspend fun enqueueIoB(reading: IoBReading) {
        enqueue(listOf(PumpEventMapper.fromIoB(reading)))
    }

    suspend fun enqueueBasal(reading: BasalReading) {
        enqueue(listOf(PumpEventMapper.fromBasal(reading)))
    }

    suspend fun enqueueBasalBatch(readings: List<BasalReading>) {
        enqueue(readings.map { PumpEventMapper.fromBasal(it) })
    }

    suspend fun enqueueBoluses(events: List<BolusEvent>) {
        enqueue(events.map { PumpEventMapper.fromBolus(it) })
    }

    suspend fun enqueueBattery(status: BatteryStatus) {
        enqueue(listOf(PumpEventMapper.fromBattery(status)))
    }

    suspend fun enqueueReservoir(reading: ReservoirReading) {
        enqueue(listOf(PumpEventMapper.fromReservoir(reading)))
    }

    /**
     * The queue rows for a history-backfill batch, and whether building them succeeded.
     *
     * [complete] false means the rows could not be built at all (a keystore flake reading the
     * mode gate, a serialization failure) -- NOT that there was nothing to build. The caller
     * needs the difference: it commits the batch either way, but a batch whose uploads are
     * missing must stay flagged for the re-derivation pass instead of being written off as done.
     */
    data class BackfillQueueRows(
        val rows: List<SyncQueueEntity>,
        val complete: Boolean,
    )

    /**
     * Builds the queue rows for a history-backfill batch WITHOUT inserting them, so the caller
     * can insert them inside the transaction that writes the derived records they describe
     * (GLY-250). A batch that rolls back must not leave queued uploads for rows that no longer
     * exist, and the only way to guarantee that is for the insert to share the batch's
     * transaction.
     *
     * The mode gate and the JSON encoding happen here, deliberately outside that transaction:
     * reading the encrypted token store and serializing a few hundred events have no business
     * holding the write lock.
     *
     * Failures come back as `complete = false` rather than as a throw, matching [enqueue] -- a
     * dropped sync row must not cost the batch its derived records. What the caller does with
     * that is the other half of the story: see [BackfillQueueRows].
     */
    suspend fun buildBackfillRows(
        boluses: List<BolusEvent>,
        basal: List<BasalReading>,
    ): BackfillQueueRows = try {
        if ((boluses.isEmpty() && basal.isEmpty()) || !authTokenStore.isBackendConfigured()) {
            BackfillQueueRows(emptyList(), complete = true)
        } else {
            val dtos = boluses.map { PumpEventMapper.fromBolus(it) } +
                basal.map { PumpEventMapper.fromBasal(it) }
            BackfillQueueRows(dtos.map { it.toQueueRow(dedupe = true) }, complete = true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(
            e,
            "Sync row build failed; %d backfill event(s) left for re-derivation",
            boluses.size + basal.size,
        )
        BackfillQueueRows(emptyList(), complete = false)
    }

    private suspend fun enqueue(dtos: List<PumpEventDto>) {
        try {
            // Single funnel for the mode gate, checked once per call so history-backfill
            // batches don't re-read the encrypted store per event. Callers all run on the
            // polling orchestrator's background coroutines, keeping the read off the main
            // thread.
            if (dtos.isEmpty() || !authTokenStore.isBackendConfigured()) return
            // One transactional insert: all rows land or none do, so the dropped-count log
            // below is accurate on failure.
            syncDao.enqueueAll(dtos.map { it.toQueueRow() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The poll loops upstream have no catch of their own: a failed enqueue (keystore
            // flake, disk full) must not kill pump polling -- a dropped sync row is harmless,
            // a dead poll loop starves the dashboard and the alert floor.
            Timber.w(e, "Sync enqueue failed; dropped %d pump event(s)", dtos.size)
        }
    }

    /**
     * [dedupe] gives the row a stable identity so the same pump event cannot be queued twice.
     * The key is a digest of the payload, which is exactly what would be uploaded: two rows that
     * would produce byte-identical uploads are the same upload. Deriving it per event type
     * instead would have to be kept in step with [PumpEventMapper] forever.
     *
     * Only the history backfill sets it -- that is the path that can legitimately replay a
     * batch. The live poll loops keep insert-always semantics via a null key.
     */
    private fun PumpEventDto.toQueueRow(dedupe: Boolean = false): SyncQueueEntity {
        val json = adapter.toJson(this)
        return SyncQueueEntity(
            eventType = eventType,
            eventTimestampMs = eventTimestamp.toEpochMilli(),
            payload = json,
            dedupeKey = if (dedupe) dedupeKeyFor(eventType, json) else null,
        )
    }

    private companion object {
        fun dedupeKeyFor(eventType: String, payloadJson: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(payloadJson.toByteArray(Charsets.UTF_8))
            return buildString(eventType.length + 1 + digest.size * 2) {
                append(eventType).append(':')
                digest.forEach { append("%02x".format(it)) }
            }
        }
    }
}
