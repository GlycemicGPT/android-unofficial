package com.glycemicgpt.mobile.data.repository

import com.glycemicgpt.mobile.data.local.AuthTokenStore
import com.glycemicgpt.mobile.data.local.dao.SyncDao
import com.glycemicgpt.mobile.data.local.entity.SyncQueueEntity
import com.glycemicgpt.mobile.data.remote.InstantAdapter
import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BatteryStatus
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.PumpActivityMode
import com.glycemicgpt.mobile.domain.model.IoBReading
import com.glycemicgpt.mobile.domain.model.ReservoirReading
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncQueueEnqueuerTest {

    private val syncDao = mockk<SyncDao>(relaxed = true)
    private val authTokenStore = mockk<AuthTokenStore> {
        every { isBackendConfigured() } returns true
    }
    private val moshi = Moshi.Builder().add(InstantAdapter()).build()
    private val enqueuer = SyncQueueEnqueuer(syncDao, authTokenStore, moshi)

    @Test
    fun `enqueueIoB creates entity with bg_reading type`() = runTest {
        val slot = slot<List<SyncQueueEntity>>()
        coEvery { syncDao.enqueueAll(capture(slot)) } returns Unit

        val now = Instant.now()
        enqueuer.enqueueIoB(IoBReading(iob = 2.5f, timestamp = now))

        val entity = slot.captured.single()
        assertEquals("bg_reading", entity.eventType)
        assertEquals(now.toEpochMilli(), entity.eventTimestampMs)
        assertTrue(entity.payload.contains("2.5"))
        assertEquals(SyncQueueEntity.STATUS_PENDING, entity.status)
    }

    @Test
    fun `enqueueBasal creates entity with basal type`() = runTest {
        val slot = slot<List<SyncQueueEntity>>()
        coEvery { syncDao.enqueueAll(capture(slot)) } returns Unit

        enqueuer.enqueueBasal(
            BasalReading(
                rate = 1.2f,
                isAutomated = true,
                activityMode = PumpActivityMode.EXERCISE,
                timestamp = Instant.now(),
            ),
        )

        val entity = slot.captured.single()
        assertEquals("basal", entity.eventType)
        assertTrue(entity.payload.contains("exercise"))
    }

    @Test
    fun `enqueueBoluses enqueues one entity per event`() = runTest {
        val events = listOf(
            BolusEvent(units = 3.0f, isAutomated = false, isCorrection = false, timestamp = Instant.now()),
            BolusEvent(units = 1.5f, isAutomated = true, isCorrection = true, timestamp = Instant.now()),
        )

        val slot = slot<List<SyncQueueEntity>>()
        coEvery { syncDao.enqueueAll(capture(slot)) } returns Unit

        enqueuer.enqueueBoluses(events)

        assertEquals(2, slot.captured.size)
    }

    @Test
    fun `enqueue is a no-op for every event type when no backend is configured`() = runTest {
        every { authTokenStore.isBackendConfigured() } returns false

        enqueuer.enqueueIoB(IoBReading(iob = 2.5f, timestamp = Instant.now()))
        enqueuer.enqueueBasal(
            BasalReading(
                rate = 1.2f,
                isAutomated = true,
                activityMode = PumpActivityMode.NONE,
                timestamp = Instant.now(),
            ),
        )
        enqueuer.enqueueBasalBatch(
            listOf(
                BasalReading(
                    rate = 0.5f,
                    isAutomated = false,
                    activityMode = PumpActivityMode.NONE,
                    timestamp = Instant.now(),
                ),
            ),
        )
        enqueuer.enqueueBoluses(
            listOf(BolusEvent(units = 3.0f, isAutomated = false, isCorrection = false, timestamp = Instant.now())),
        )
        enqueuer.enqueueBattery(BatteryStatus(percentage = 80, isCharging = false, timestamp = Instant.now()))
        enqueuer.enqueueReservoir(ReservoirReading(unitsRemaining = 150f, timestamp = Instant.now()))

        coVerify(exactly = 0) { syncDao.enqueueAll(any()) }
    }

    @Test
    fun `enqueue swallows storage failures instead of crashing the caller`() = runTest {
        // The polling loops upstream have no catch of their own -- an enqueue failure
        // (keystore flake, disk full) must not propagate and kill pump polling.
        coEvery { syncDao.enqueueAll(any()) } throws RuntimeException("disk full")

        enqueuer.enqueueIoB(IoBReading(iob = 2.5f, timestamp = Instant.now()))
    }

    // -- history-backfill rows (GLY-250) ---------------------------------------

    private val backfillBolus = BolusEvent(
        units = 3.0f,
        isAutomated = false,
        isCorrection = false,
        timestamp = Instant.ofEpochMilli(1_700_000_000_000L),
    )
    private val backfillBasal = BasalReading(
        rate = 0.8f,
        isAutomated = true,
        activityMode = PumpActivityMode.NONE,
        timestamp = Instant.ofEpochMilli(1_700_000_020_000L),
    )

    @Test
    fun `backfill rows are built, not inserted -- the caller's transaction inserts them`() = runTest {
        val built = enqueuer.buildBackfillRows(listOf(backfillBolus), listOf(backfillBasal))

        assertEquals(2, built.rows.size)
        assertTrue(built.complete)
        coVerify(exactly = 0) { syncDao.enqueueAll(any()) }
        coVerify(exactly = 0) { syncDao.enqueueAllIgnoringDuplicates(any()) }
    }

    @Test
    fun `backfill rows carry a dedupe key, identical for the same event and distinct across events`() = runTest {
        val first = enqueuer.buildBackfillRows(listOf(backfillBolus), listOf(backfillBasal)).rows
        val second = enqueuer.buildBackfillRows(listOf(backfillBolus), listOf(backfillBasal)).rows

        // Re-processing a batch must not queue its uploads twice; the key is what the unique
        // index collapses on, so it has to be stable across calls and per event.
        assertEquals(first.map { it.dedupeKey }, second.map { it.dedupeKey })
        assertEquals(2, first.mapNotNull { it.dedupeKey }.toSet().size)
    }

    @Test
    fun `a bolus differing only in units gets a different dedupe key`() = runTest {
        val a = enqueuer.buildBackfillRows(listOf(backfillBolus), emptyList()).rows.single()
        val b = enqueuer.buildBackfillRows(
            listOf(backfillBolus.copy(units = 3.5f)),
            emptyList(),
        ).rows.single()

        // Two boluses at the same instant with different doses are two events, not one.
        assertEquals(a.eventTimestampMs, b.eventTimestampMs)
        assertTrue(a.dedupeKey != b.dedupeKey)
    }

    @Test
    fun `live poll rows carry no dedupe key, so their insert-always semantics are unchanged`() = runTest {
        val slot = slot<List<SyncQueueEntity>>()
        coEvery { syncDao.enqueueAll(capture(slot)) } returns Unit

        enqueuer.enqueueBoluses(listOf(backfillBolus))

        assertNull(slot.captured.single().dedupeKey)
    }

    @Test
    fun `nothing to upload is complete, not a failure`() = runTest {
        every { authTokenStore.isBackendConfigured() } returns false

        val built = enqueuer.buildBackfillRows(listOf(backfillBolus), listOf(backfillBasal))

        // A BLE-only device has no destination, which is not the same as failing to build the
        // rows -- the caller must still be able to write the batch off as fully processed.
        assertTrue(built.rows.isEmpty())
        assertTrue(built.complete)
    }

    @Test
    fun `a failed build reports incomplete instead of throwing or looking empty`() = runTest {
        every { authTokenStore.isBackendConfigured() } throws RuntimeException("keystore flake")

        val built = enqueuer.buildBackfillRows(listOf(backfillBolus), listOf(backfillBasal))

        // Silently returning an empty list here is how a batch commits with its uploads missing
        // and its raw rows marked done -- permanently absent from the backend, with nothing left
        // recording it. The caller needs to see the difference.
        assertTrue(built.rows.isEmpty())
        assertFalse(built.complete)
    }

    @Test
    fun `enqueue inserts again once a backend is configured`() = runTest {
        every { authTokenStore.isBackendConfigured() } returns false
        enqueuer.enqueueIoB(IoBReading(iob = 1.0f, timestamp = Instant.now()))
        coVerify(exactly = 0) { syncDao.enqueueAll(any()) }

        every { authTokenStore.isBackendConfigured() } returns true
        enqueuer.enqueueIoB(IoBReading(iob = 1.0f, timestamp = Instant.now()))
        coVerify(exactly = 1) { syncDao.enqueueAll(any()) }
    }
}
