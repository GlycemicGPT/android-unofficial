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
     * Failures return an empty list rather than throwing, matching [enqueue] -- a dropped sync
     * row is harmless, and failing the caller's transaction over one would cost the derived
     * records too.
     */
    suspend fun buildBackfillRows(
        boluses: List<BolusEvent>,
        basal: List<BasalReading>,
    ): List<SyncQueueEntity> = try {
        if ((boluses.isEmpty() && basal.isEmpty()) || !authTokenStore.isBackendConfigured()) {
            emptyList()
        } else {
            val dtos = boluses.map { PumpEventMapper.fromBolus(it) } +
                basal.map { PumpEventMapper.fromBasal(it) }
            dtos.map { it.toQueueRow() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(
            e,
            "Sync row build failed; dropped %d backfill event(s)",
            boluses.size + basal.size,
        )
        emptyList()
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

    private fun PumpEventDto.toQueueRow() = SyncQueueEntity(
        eventType = eventType,
        eventTimestampMs = eventTimestamp.toEpochMilli(),
        payload = adapter.toJson(this),
    )
}
