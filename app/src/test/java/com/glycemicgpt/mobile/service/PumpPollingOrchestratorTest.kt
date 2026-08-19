package com.glycemicgpt.mobile.service

import android.util.Log
import com.glycemicgpt.mobile.data.local.AppSettingsStore
import com.glycemicgpt.mobile.data.local.GlucoseRangeStore
import com.glycemicgpt.mobile.data.local.SafetyLimitsStore
import com.glycemicgpt.mobile.data.repository.HistoryBackfillWriter
import com.glycemicgpt.mobile.data.repository.PumpDataRepository
import com.glycemicgpt.mobile.data.repository.SyncQueueEnqueuer
import com.glycemicgpt.mobile.domain.alerting.AlertTypes
import com.glycemicgpt.mobile.domain.model.BasalReading
import com.glycemicgpt.mobile.domain.model.BatteryStatus
import com.glycemicgpt.mobile.domain.model.BolusEvent
import com.glycemicgpt.mobile.domain.model.CgmReading
import com.glycemicgpt.mobile.domain.model.CgmTrend
import com.glycemicgpt.mobile.domain.model.ConnectionState
import com.glycemicgpt.mobile.domain.model.PumpActivityMode
import com.glycemicgpt.mobile.domain.model.GlucoseUnit
import com.glycemicgpt.mobile.domain.model.HistoryLogRecord
import com.glycemicgpt.mobile.domain.model.IoBReading
import com.glycemicgpt.mobile.domain.model.ReservoirReading
import com.glycemicgpt.mobile.domain.pump.HistoryLogParser
import com.glycemicgpt.mobile.domain.pump.PumpDriver
import com.glycemicgpt.mobile.wear.WearDataSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import timber.log.Timber
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PumpPollingOrchestratorTest {

    private val connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val pumpDriver = mockk<PumpDriver>(relaxed = true) {
        every { observeConnectionState() } returns connectionStateFlow
        coEvery { getIoB() } returns Result.success(
            IoBReading(iob = 2.5f, timestamp = Instant.now()),
        )
        coEvery { getBasalRate() } returns Result.success(
            BasalReading(
                rate = 0.8f,
                isAutomated = true,
                activityMode = PumpActivityMode.NONE,
                timestamp = Instant.now(),
            ),
        )
        coEvery { getBatteryStatus() } returns Result.success(
            BatteryStatus(percentage = 80, isCharging = false, timestamp = Instant.now()),
        )
        coEvery { getReservoirLevel() } returns Result.success(
            ReservoirReading(unitsRemaining = 150f, timestamp = Instant.now()),
        )
        coEvery { getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 120, trendArrow = CgmTrend.FLAT, timestamp = Instant.now()),
        )
        coEvery { getBolusHistory(any(), any()) } returns Result.success(emptyList())
        coEvery { getHistoryLogs(any()) } returns Result.success(emptyList())
        coEvery { getFullHistoryLogs(any()) } returns Result.success(emptyList())
        coEvery { getPumpHardwareInfo() } returns Result.failure(RuntimeException("not connected"))
    }
    private val repository = mockk<PumpDataRepository>(relaxed = true) {
        coEvery { getLatestBolusTimestamp() } returns null
    }
    private val syncEnqueuer = mockk<SyncQueueEnqueuer>(relaxed = true)
    private val historyBackfill = mockk<HistoryBackfillWriter>(relaxed = true) {
        // No cursor row = fresh install, the same thing an empty raw table used to mean.
        coEvery { processedThroughSequence() } returns null
    }
    private val wearDataSender = mockk<WearDataSender>(relaxed = true)
    private val glucoseRangeStore = mockk<GlucoseRangeStore>(relaxed = true) {
        every { urgentLow } returns GlucoseRangeStore.DEFAULT_URGENT_LOW
        every { low } returns GlucoseRangeStore.DEFAULT_LOW
        every { high } returns GlucoseRangeStore.DEFAULT_HIGH
        every { urgentHigh } returns GlucoseRangeStore.DEFAULT_URGENT_HIGH
    }
    private val safetyLimitsStore = mockk<SafetyLimitsStore>(relaxed = true)
    private val historyLogParser = mockk<HistoryLogParser>(relaxed = true)
    private val appSettingsStore = mockk<AppSettingsStore>(relaxed = true) {
        every { glucoseUnit } returns GlucoseUnit.MGDL
    }

    /** AlertFloor's firing gates are covered in AlertFloorTest; here it only classifies (default
     *  thresholds) so the watch relay mapping and the floor hand-off can be verified. The shared
     *  data-trust bound defaults to alertable=true so the relay tests exercise the send/clear
     *  logic; the gate tests below flip it. */
    private val alertFloor = mockk<AlertFloor>(relaxed = true) {
        every { classify(any()) } answers {
            val mgDl = firstArg<Int>()
            when {
                mgDl <= 55 -> AlertTypes.LOW_URGENT
                mgDl >= 250 -> AlertTypes.HIGH_URGENT
                mgDl <= 70 -> AlertTypes.LOW_WARNING
                mgDl >= 180 -> AlertTypes.HIGH_WARNING
                else -> null
            }
        }
        every { isReadingAlertable(any(), any()) } returns true
    }

    /**
     * Time to advance past the fast loop's initial delay + stagger + margin.
     * Fast loop fires at: INITIAL_DELAY + 0 + STAGGER + 0 + STAGGER (= 1500ms for CGM).
     * Second CGM: +INTERVAL_FAST + 2*STAGGER = 17500ms.
     * Tests that advance by SETTLE + INTERVAL_FAST need to reach the second CGM,
     * so we add extra margin (5 staggers) to ensure timing tests pass.
     */
    private val FAST_SETTLE_MS = PumpPollingOrchestrator.INITIAL_POLL_DELAY_MS +
        PumpPollingOrchestrator.REQUEST_STAGGER_MS * 5 + 100

    /**
     * Time to advance past ALL loop initial delays + staggers.
     * Uses the largest initial delay across all loops as the base.
     */
    private val ALL_SETTLE_MS = maxOf(
        PumpPollingOrchestrator.MEDIUM_LOOP_INITIAL_DELAY_MS,
        PumpPollingOrchestrator.SLOW_LOOP_INITIAL_DELAY_MS,
    ) + PumpPollingOrchestrator.REQUEST_STAGGER_MS * 4 + 100

    /** Alias for tests that only need fast loop data. */
    private val SETTLE_TIME_MS = FAST_SETTLE_MS

    /** Virtual time one further fast-loop cycle costs: the interval plus the two request
     *  staggers before CGM fires. Advancing bare INTERVAL_FAST_MS accumulates stagger debt and
     *  silently misses polls from the third cycle on. */
    private val FAST_CYCLE_MS = PumpPollingOrchestrator.INTERVAL_FAST_MS +
        PumpPollingOrchestrator.REQUEST_STAGGER_MS * 2

    /** Real tracker, not a mock: the heartbeat assertions below are about the state transitions it
     *  actually performs, and GLY-254's watchdog will read exactly this. */
    private val loopHealth = PollLoopHealthTracker()

    /** One further medium/slow-loop cycle in virtual time: the interval plus the staggers the
     *  loop spends inside an iteration, plus a margin (`advanceTimeBy` does not run a task
     *  scheduled exactly at the boundary). */
    private val MEDIUM_CYCLE_MS = PumpPollingOrchestrator.INTERVAL_MEDIUM_MS + 100
    private val SLOW_CYCLE_MS = PumpPollingOrchestrator.INTERVAL_SLOW_MS +
        PumpPollingOrchestrator.REQUEST_STAGGER_MS * 4 +
        PumpPollingOrchestrator.BACKFILL_BATCH_STAGGER_MS * 2 + 100

    private fun createOrchestrator() = PumpPollingOrchestrator(pumpDriver, repository, syncEnqueuer, historyBackfill, wearDataSender, glucoseRangeStore, safetyLimitsStore, historyLogParser, appSettingsStore, alertFloor, loopHealth)

    @After
    fun tearDown() {
        // The report-damping tests plant a Timber tree; never leak it into sibling tests.
        Timber.uprootAll()
    }

    /**
     * Captures the priority and formatted text of everything Timber emits. The damping tests are
     * about which failures reach ERROR — the level Sentry turns into an event — so the level is
     * the assertion, not an incidental detail.
     */
    private class RecordingTree : Timber.Tree() {
        val lines = mutableListOf<Pair<Int, String>>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += priority to message
        }

        /** Messages logged at [priority] containing every one of [needles]. */
        fun at(priority: Int, vararg needles: String): List<String> =
            lines.filter { (p, message) -> p == priority && needles.all(message::contains) }
                .map { it.second }
    }

    @Test
    fun `does not poll when disconnected`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        advanceTimeBy(60_000)

        coVerify(exactly = 0) { pumpDriver.getIoB() }
        orchestrator.stop()
    }

    @Test
    fun `does not poll before initial delay elapses`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(100) // well before INITIAL_POLL_DELAY_MS

        coVerify(exactly = 0) { pumpDriver.getIoB() }
        coVerify(exactly = 0) { pumpDriver.getBasalRate() }
        coVerify(exactly = 0) { pumpDriver.getCgmStatus() }
        coVerify(exactly = 0) { pumpDriver.getBatteryStatus() }
        coVerify(exactly = 0) { pumpDriver.getReservoirLevel() }
        coVerify(exactly = 0) { pumpDriver.getBolusHistory(any(), any()) }
        orchestrator.stop()
    }

    @Test
    fun `polls after initial delay when connected`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        // Advance past ALL loop initial delays (medium loop takes 60s)
        advanceTimeBy(ALL_SETTLE_MS)

        coVerify(atLeast = 1) { pumpDriver.getIoB() }
        coVerify(atLeast = 1) { pumpDriver.getBasalRate() }
        coVerify(atLeast = 1) { pumpDriver.getBatteryStatus() }
        coVerify(atLeast = 1) { pumpDriver.getReservoirLevel() }
        coVerify(atLeast = 1) { pumpDriver.getBolusHistory(any(), any()) }
        orchestrator.stop()
    }

    @Test
    fun `saves readings to repository on poll`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        // Advance past ALL loop initial delays (medium loop takes 60s)
        advanceTimeBy(ALL_SETTLE_MS)

        coVerify(atLeast = 1) { repository.saveIoB(any()) }
        coVerify(atLeast = 1) { repository.saveBasal(any()) }
        coVerify(atLeast = 1) { repository.saveBattery(any()) }
        coVerify(atLeast = 1) { repository.saveReservoir(any()) }
        coVerify(atLeast = 1) { repository.saveCgm(any()) }
        orchestrator.stop()
    }

    @Test
    fun `polls IoB again after fast interval`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS) // initial poll (delay + stagger)
        coVerify(exactly = 1) { pumpDriver.getIoB() }

        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)
        coVerify(exactly = 2) { pumpDriver.getIoB() }
        orchestrator.stop()
    }

    @Test
    fun `stops polling on disconnect`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 1) { pumpDriver.getIoB() }

        connectionStateFlow.value = ConnectionState.DISCONNECTED
        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS + SETTLE_TIME_MS)
        // Should not have polled again after disconnect
        coVerify(exactly = 1) { pumpDriver.getIoB() }
        orchestrator.stop()
    }

    @Test
    fun `resumes polling on reconnect`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 1) { pumpDriver.getIoB() }

        connectionStateFlow.value = ConnectionState.DISCONNECTED
        advanceTimeBy(1000)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 2) { pumpDriver.getIoB() }
        orchestrator.stop()
    }

    @Test
    fun `low battery multiplies poll interval`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.phoneBatteryLow = true
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 1) { pumpDriver.getIoB() }

        // Normal interval passes but should NOT trigger another poll
        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)
        coVerify(exactly = 1) { pumpDriver.getIoB() }

        // Full low-battery interval passes
        advanceTimeBy(
            PumpPollingOrchestrator.INTERVAL_FAST_MS *
                (PumpPollingOrchestrator.LOW_BATTERY_MULTIPLIER - 1),
        )
        coVerify(exactly = 2) { pumpDriver.getIoB() }
        orchestrator.stop()
    }

    @Test
    fun `polls CGM in fast loop when connected`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS) // initial poll
        coVerify(exactly = 1) { pumpDriver.getCgmStatus() }

        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)
        coVerify(exactly = 2) { pumpDriver.getCgmStatus() }
        orchestrator.stop()
    }

    @Test
    fun `phoneBatteryLow defaults to false`() {
        val orchestrator = createOrchestrator()
        assertFalse(orchestrator.phoneBatteryLow)
    }

    @Test
    fun `continues polling when individual read fails`() = runTest {
        coEvery { pumpDriver.getIoB() } returns Result.failure(RuntimeException("timeout"))
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        // Advance past ALL loop delays so battery/reservoir have also been polled
        advanceTimeBy(ALL_SETTLE_MS)

        // Other reads should still succeed
        coVerify(atLeast = 1) { repository.saveBasal(any()) }
        coVerify(atLeast = 1) { repository.saveBattery(any()) }

        // Should continue polling despite IoB failure
        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)
        coVerify(atLeast = 2) { pumpDriver.getIoB() }
        orchestrator.stop()
    }

    @Test
    fun `fast loop requests are staggered in order IoB then basal then CGM`() = runTest {
        val callOrder = mutableListOf<String>()
        coEvery { pumpDriver.getIoB() } coAnswers {
            callOrder.add("iob")
            Result.success(IoBReading(iob = 2.5f, timestamp = Instant.now()))
        }
        coEvery { pumpDriver.getBasalRate() } coAnswers {
            callOrder.add("basal")
            Result.success(BasalReading(rate = 0.8f, isAutomated = true, activityMode = PumpActivityMode.NONE, timestamp = Instant.now()))
        }
        coEvery { pumpDriver.getCgmStatus() } coAnswers {
            callOrder.add("cgm")
            Result.success(CgmReading(glucoseMgDl = 120, trendArrow = CgmTrend.FLAT, timestamp = Instant.now()))
        }

        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)

        // First 3 entries should be in stagger order
        assertTrue("Expected at least 3 calls, got ${callOrder.size}", callOrder.size >= 3)
        assertEquals("iob", callOrder[0])
        assertEquals("basal", callOrder[1])
        assertEquals("cgm", callOrder[2])
        orchestrator.stop()
    }

    // Alert classification lives in AlertFloor (server alert thresholds, server vocabulary);
    // the orchestrator maps to the watch wire strings. Tested through watch alert sends below.

    @Test
    fun `alertLabel returns correct labels`() {
        assertEquals("URGENT LOW", PumpPollingOrchestrator.alertLabel("urgent_low"))
        assertEquals("URGENT HIGH", PumpPollingOrchestrator.alertLabel("urgent_high"))
        assertEquals("LOW", PumpPollingOrchestrator.alertLabel("low"))
        assertEquals("HIGH", PumpPollingOrchestrator.alertLabel("high"))
        assertEquals("", PumpPollingOrchestrator.alertLabel("unknown"))
    }

    @Test
    fun `sends alert to watch when threshold crossed`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS) // initial poll: 120 mg/dL = normal

        // No alert for normal reading
        coVerify(exactly = 0) { wearDataSender.sendAlert(any(), any(), any(), any()) }

        // Change to low reading
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 65, trendArrow = CgmTrend.SINGLE_DOWN, timestamp = Instant.now()),
        )
        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)

        coVerify(exactly = 1) { wearDataSender.sendAlert("low", 65, any(), any()) }
        orchestrator.stop()
    }

    @Test
    fun `watch CGM and alert render in the user's mmol unit`() = runTest {
        every { appSettingsStore.glucoseUnit } returns GlucoseUnit.MMOL
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS) // initial poll: 120 mg/dL = normal

        // Raw mg/dL stays on the wire; the unit flag tells the watch how to render it.
        coVerify(atLeast = 1) {
            wearDataSender.sendCgm(120, any(), any(), any(), any(), any(), any(), GlucoseUnit.MMOL)
        }

        // Drop to a low reading -> the pre-formatted watch alert message is in mmol/L (65 -> 3.6)
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 65, trendArrow = CgmTrend.SINGLE_DOWN, timestamp = Instant.now()),
        )
        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)

        coVerify(exactly = 1) { wearDataSender.sendAlert("low", 65, any(), "LOW 3.6 mmol/L") }
        orchestrator.stop()
    }

    @Test
    fun `every polled CGM reading is handed to the alert floor with its server-vocab type`() = runTest {
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 65, trendArrow = CgmTrend.SINGLE_DOWN, timestamp = Instant.now()),
        )
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)

        // The floor is evaluated on EVERY poll (not edge-latched like the watch relay) — a low
        // that persists across an offline window must keep meeting the floor's own gates.
        coVerify(exactly = 1) {
            alertFloor.onCgmReading(match { it.glucoseMgDl == 65 }, AlertTypes.LOW_WARNING, any())
        }

        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)
        coVerify(exactly = 2) {
            alertFloor.onCgmReading(match { it.glucoseMgDl == 65 }, AlertTypes.LOW_WARNING, any())
        }
        orchestrator.stop()
    }

    @Test
    fun `clears alert when returning to normal range`() = runTest {
        // Start with low reading
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 65, trendArrow = CgmTrend.SINGLE_DOWN, timestamp = Instant.now()),
        )
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS) // initial poll: low alert

        coVerify(exactly = 1) { wearDataSender.sendAlert("low", 65, any(), any()) }

        // Return to normal
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 120, trendArrow = CgmTrend.FLAT, timestamp = Instant.now()),
        )
        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)

        coVerify(exactly = 1) { wearDataSender.clearAlert() }
        orchestrator.stop()
    }

    // -- GLY-116 AC-A: the relay gates on the shared data-trust bound ----------
    // These are the discriminating tests for the relay gate: they drive the REAL
    // processCgmReading path and flip on a gate revert (the predicate unit tests alone would
    // stay green with the call site removed).

    @Test
    fun `not-alertable low reading is not relayed to the watch and nothing is cleared`() = runTest {
        every { alertFloor.isReadingAlertable(any(), any()) } returns false
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 54, trendArrow = CgmTrend.FLAT, timestamp = Instant.now()),
        )
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)

        coVerify(exactly = 0) { wearDataSender.sendAlert(any(), any(), any(), any()) }
        coVerify(exactly = 0) { wearDataSender.clearAlert() }
        // The CGM display push and the floor hand-off are NOT gated — only the alert relay is.
        coVerify(atLeast = 1) {
            wearDataSender.sendCgm(any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(atLeast = 1) { alertFloor.onCgmReading(any(), any(), any()) }
        orchestrator.stop()
    }

    @Test
    fun `not-alertable reading leaves the shown alert and latch untouched - no false all-clear`() = runTest {
        // A fresh low latches the watch alert...
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 65, trendArrow = CgmTrend.SINGLE_DOWN, timestamp = Instant.now()),
        )
        val orchestrator = createOrchestrator()
        orchestrator.start(this)
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 1) { wearDataSender.sendAlert("low", 65, any(), any()) }

        // ...then the feed goes stale while the meter drifts in-range: the relay must NOT
        // retract the shown low into the watch's green "All clear" off data nobody can vouch
        // for. Axis (b) ages it out on the watch instead. (Each further fast-loop cycle costs
        // INTERVAL + 2 staggers of virtual time.)
        every { alertFloor.isReadingAlertable(any(), any()) } returns false
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 120, trendArrow = CgmTrend.FLAT, timestamp = Instant.now()),
        )
        advanceTimeBy(FAST_CYCLE_MS)
        coVerify(exactly = 0) { wearDataSender.clearAlert() }

        // The latch survived untouched: a genuinely FRESH in-range recovery still clears once.
        every { alertFloor.isReadingAlertable(any(), any()) } returns true
        advanceTimeBy(FAST_CYCLE_MS)
        coVerify(exactly = 1) { wearDataSender.clearAlert() }
        orchestrator.stop()
    }

    @Test
    fun `same low is not re-sent when readings become alertable again - latch not stranded`() = runTest {
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 65, trendArrow = CgmTrend.SINGLE_DOWN, timestamp = Instant.now()),
        )
        val orchestrator = createOrchestrator()
        orchestrator.start(this)
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 1) { wearDataSender.sendAlert("low", 65, any(), any()) }

        // Stale window passes with the same low...
        every { alertFloor.isReadingAlertable(any(), any()) } returns false
        advanceTimeBy(FAST_CYCLE_MS)

        // ...and a fresh reading of the SAME type does not double-send (the latch still holds
        // "low"), while a fresh reading of a NEW type still fires — the gate skipped the latch,
        // it did not corrupt it.
        every { alertFloor.isReadingAlertable(any(), any()) } returns true
        advanceTimeBy(FAST_CYCLE_MS)
        coVerify(exactly = 1) { wearDataSender.sendAlert(any(), any(), any(), any()) }

        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 54, trendArrow = CgmTrend.SINGLE_DOWN, timestamp = Instant.now()),
        )
        advanceTimeBy(FAST_CYCLE_MS)
        coVerify(exactly = 1) { wearDataSender.sendAlert("urgent_low", 54, any(), any()) }
        orchestrator.stop()
    }

    // -- GLY-116: ongoing-episode refresh + re-buzz (relay owns the wrist re-alarm, D4) -------
    // Driven through processCgmReading directly with an injected clock: the refresh/re-buzz
    // cadence is wall-clock-based, which the virtual-time poll loop cannot advance.

    private val lowReading = { at: Long ->
        CgmReading(glucoseMgDl = 65, trendArrow = CgmTrend.SINGLE_DOWN, timestamp = Instant.ofEpochMilli(at))
    }

    @Test
    fun `ongoing alert is silently refreshed so the wrist copy never ages into data-stale`() = runTest {
        val orchestrator = createOrchestrator()
        val t0 = 1_750_000_000_000L

        orchestrator.processCgmReading(lowReading(t0), nowMs = t0)
        coVerify(exactly = 1) { wearDataSender.sendAlert("low", 65, t0, any(), true) }

        // Within the refresh window: no re-push (DataLayer traffic stays bounded).
        orchestrator.processCgmReading(lowReading(t0 + 60_000L), nowMs = t0 + 60_000L)
        coVerify(exactly = 1) { wearDataSender.sendAlert(any(), any(), any(), any(), any()) }

        // Past the refresh window: silent refresh with the new reading's timestamp — axis (b)
        // on the watch keeps seeing a current alert, not a frozen T0 one.
        val t1 = t0 + PumpPollingOrchestrator.WRIST_ALERT_REFRESH_MS
        orchestrator.processCgmReading(lowReading(t1), nowMs = t1)
        coVerify(exactly = 1) { wearDataSender.sendAlert("low", 65, t1, any(), false) }
        orchestrator.stop()
    }

    @Test
    fun `sustained alert re-buzzes on the floor's re-alarm cadence`() = runTest {
        val orchestrator = createOrchestrator()
        val t0 = 1_750_000_000_000L

        orchestrator.processCgmReading(lowReading(t0), nowMs = t0)
        coVerify(exactly = 1) { wearDataSender.sendAlert("low", 65, t0, any(), true) }

        // A sustained, never-recovering low must re-buzz the wrist after the same window the
        // phone floor re-alarms in — the wrist is never quieter than the phone.
        val t1 = t0 + PumpPollingOrchestrator.WRIST_ALERT_REBUZZ_MS
        orchestrator.processCgmReading(lowReading(t1), nowMs = t1)
        coVerify(exactly = 2) { wearDataSender.sendAlert("low", 65, any(), any(), true) }
        orchestrator.stop()
    }

    @Test
    fun `a watch-mapping gap never clears a shown alert - only a genuine in-range recovery does`() = runTest {
        val orchestrator = createOrchestrator()
        val t0 = 1_750_000_000_000L
        orchestrator.processCgmReading(lowReading(t0), nowMs = t0)
        coVerify(exactly = 1) { wearDataSender.sendAlert(any(), any(), any(), any(), any()) }

        // classify returns a server type with no watch mapping: the relay loses its push
        // (logged), but it must NOT retract the shown low into the watch's "All clear".
        every { alertFloor.classify(any()) } returns "some_future_alert_type"
        orchestrator.processCgmReading(lowReading(t0 + 15_000L), nowMs = t0 + 15_000L)
        coVerify(exactly = 0) { wearDataSender.clearAlert() }

        // A genuinely in-range classification still clears.
        every { alertFloor.classify(any()) } returns null
        orchestrator.processCgmReading(
            CgmReading(glucoseMgDl = 120, trendArrow = CgmTrend.FLAT, timestamp = Instant.ofEpochMilli(t0 + 30_000L)),
            nowMs = t0 + 30_000L,
        )
        coVerify(exactly = 1) { wearDataSender.clearAlert() }
        orchestrator.stop()
    }

    @Test
    fun `not-alertable readings do not refresh the wrist alert either`() = runTest {
        val orchestrator = createOrchestrator()
        val t0 = 1_750_000_000_000L
        orchestrator.processCgmReading(lowReading(t0), nowMs = t0)
        coVerify(exactly = 1) { wearDataSender.sendAlert(any(), any(), any(), any(), any()) }

        // Feed goes stale: no refresh, no re-buzz — the wrist copy ages out honestly instead
        // of being re-stamped with data nobody can vouch for.
        every { alertFloor.isReadingAlertable(any(), any()) } returns false
        val t1 = t0 + PumpPollingOrchestrator.WRIST_ALERT_REBUZZ_MS
        orchestrator.processCgmReading(lowReading(t0), nowMs = t1)
        coVerify(exactly = 1) { wearDataSender.sendAlert(any(), any(), any(), any(), any()) }
        orchestrator.stop()
    }

    // -- Reconnection accelerated polling tests --------------------------------

    @Test
    fun `reconnection uses reduced medium loop delay`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        // First connection
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 1) { pumpDriver.getIoB() }

        // Disconnect
        connectionStateFlow.value = ConnectionState.DISCONNECTED
        advanceTimeBy(1000)

        // Reconnection: bolus history should fire within RECONNECT_MEDIUM_DELAY_MS (5s)
        // not MEDIUM_LOOP_INITIAL_DELAY_MS (60s)
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(PumpPollingOrchestrator.RECONNECT_MEDIUM_DELAY_MS + 100)
        coVerify(atLeast = 1) { pumpDriver.getBolusHistory(any(), any()) }
        orchestrator.stop()
    }

    @Test
    fun `initial connection uses full medium loop delay`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        // First connection -- should NOT poll bolus history before MEDIUM_LOOP_INITIAL_DELAY_MS
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(PumpPollingOrchestrator.RECONNECT_MEDIUM_DELAY_MS + 100)

        // At 5.1 seconds, bolus history should NOT have been polled (initial delay is 60s)
        coVerify(exactly = 0) { pumpDriver.getBolusHistory(any(), any()) }
        orchestrator.stop()
    }

    @Test
    fun `reconnection uses reduced slow loop delay`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        // First connection -- only settle briefly (not long enough for slow loop at 30s)
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)

        // Disconnect
        connectionStateFlow.value = ConnectionState.DISCONNECTED
        advanceTimeBy(1000)

        // Reconnection: battery should fire within RECONNECT_SLOW_DELAY_MS (3s)
        // instead of SLOW_LOOP_INITIAL_DELAY_MS (30s)
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(PumpPollingOrchestrator.RECONNECT_SLOW_DELAY_MS + 100)
        coVerify(atLeast = 1) { pumpDriver.getBatteryStatus() }
        orchestrator.stop()
    }

    @Test
    fun `does not resend same alert type`() = runTest {
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 200, trendArrow = CgmTrend.SINGLE_UP, timestamp = Instant.now()),
        )
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS) // first high alert

        coVerify(exactly = 1) { wearDataSender.sendAlert("high", 200, any(), any()) }

        // Still high on next poll
        coEvery { pumpDriver.getCgmStatus() } returns Result.success(
            CgmReading(glucoseMgDl = 210, trendArrow = CgmTrend.SINGLE_UP, timestamp = Instant.now()),
        )
        advanceTimeBy(PumpPollingOrchestrator.INTERVAL_FAST_MS)

        // Should NOT send again for same type
        coVerify(exactly = 1) { wearDataSender.sendAlert(eq("high"), any(), any(), any()) }
        orchestrator.stop()
    }

    // -- HistoryLogParser integration tests ------------------------------------

    @Test
    fun `delegates history log extraction to HistoryLogParser`() = runTest {
        val fakeRecords = listOf(
            HistoryLogRecord(
                sequenceNumber = 100,
                rawBytesB64 = "dGVzdA==",
                eventTypeId = 399,
                pumpTimeSeconds = 572_000_000L,
            ),
        )
        coEvery { pumpDriver.getHistoryLogs(any()) } returns Result.success(fakeRecords)
        coEvery { pumpDriver.getFullHistoryLogs(any()) } returns Result.success(fakeRecords)
        every { historyLogParser.extractCgmFromHistoryLogs(any(), any()) } returns emptyList()
        every { historyLogParser.extractBolusesFromHistoryLogs(any(), any()) } returns emptyList()
        every { historyLogParser.extractBasalFromHistoryLogs(any(), any()) } returns emptyList()

        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        // Advance past slow loop initial delay so history logs get polled
        advanceTimeBy(ALL_SETTLE_MS)

        // Verify historyLogParser was called with the records from the driver
        verify(atLeast = 1) { historyLogParser.extractCgmFromHistoryLogs(fakeRecords, any()) }
        verify(atLeast = 1) { historyLogParser.extractBolusesFromHistoryLogs(fakeRecords, any()) }
        verify(atLeast = 1) { historyLogParser.extractBasalFromHistoryLogs(fakeRecords, any()) }
        orchestrator.stop()
    }

    // -- GLY-249: fault injection, liveness, and clean cancellation -------------
    // The loops run under the service's SupervisorJob, so before this story a single throw from
    // Room, a parser, or the sync queue killed one loop silently and permanently while the
    // notification still claimed "connected". Each test below injects that throw at a real
    // collaborator and asserts the loop is still polling on the NEXT iteration.

    private val historyRecords = listOf(
        HistoryLogRecord(
            sequenceNumber = 100,
            rawBytesB64 = "dGVzdA==",
            eventTypeId = 399,
            pumpTimeSeconds = 572_000_000L,
        ),
    )

    /** Makes the slow loop's history step do real work (fetch → persist → parse) so the DAO,
     *  parser and enqueuer on that path are actually reached. */
    private fun stubHistoryBackfill() {
        coEvery { pumpDriver.getHistoryLogs(any()) } returns Result.success(historyRecords)
        coEvery { pumpDriver.getFullHistoryLogs(any()) } returns Result.success(historyRecords)
        every { historyLogParser.extractCgmFromHistoryLogs(any(), any()) } returns emptyList()
        every { historyLogParser.extractBolusesFromHistoryLogs(any(), any()) } returns emptyList()
        every { historyLogParser.extractBasalFromHistoryLogs(any(), any()) } returns emptyList()
    }

    /** Makes the medium loop's bolus step do real work, so its save + enqueue are reached. */
    private fun stubBolusHistory() {
        coEvery { pumpDriver.getBolusHistory(any(), any()) } returns Result.success(
            listOf(
                BolusEvent(
                    units = 1.5f,
                    isAutomated = false,
                    isCorrection = false,
                    timestamp = Instant.now(),
                ),
            ),
        )
    }

    @Test
    fun `fast loop survives a throwing repository write and keeps polling`() = runTest {
        coEvery { repository.saveIoB(any()) } throws RuntimeException("Room write failed")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 1) { pumpDriver.getIoB() }
        // The throw costs the IoB step only: the rest of the same iteration still ran.
        coVerify(exactly = 1) { pumpDriver.getBasalRate() }
        coVerify(exactly = 1) { pumpDriver.getCgmStatus() }

        advanceTimeBy(FAST_CYCLE_MS)
        coVerify(exactly = 2) { pumpDriver.getIoB() }
        assertEquals(PollStep.IOB, loopHealth.snapshot(PollLoop.FAST).lastFailureStep)
        orchestrator.stop()
    }

    @Test
    fun `fast loop survives a throwing sync enqueuer and keeps polling`() = runTest {
        coEvery { syncEnqueuer.enqueueBasal(any()) } throws RuntimeException("sync queue full")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        coVerify(exactly = 1) { pumpDriver.getCgmStatus() }

        advanceTimeBy(FAST_CYCLE_MS)
        coVerify(exactly = 2) { pumpDriver.getBasalRate() }
        assertEquals(PollStep.BASAL, loopHealth.snapshot(PollLoop.FAST).lastFailureStep)
        orchestrator.stop()
    }

    @Test
    fun `medium loop survives a throwing sync enqueuer and keeps polling`() = runTest {
        stubBolusHistory()
        coEvery { syncEnqueuer.enqueueBoluses(any()) } throws RuntimeException("sync queue full")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        coVerify(exactly = 1) { pumpDriver.getBolusHistory(any(), any()) }

        advanceTimeBy(MEDIUM_CYCLE_MS)
        coVerify(exactly = 2) { pumpDriver.getBolusHistory(any(), any()) }
        assertEquals(PollStep.BOLUS_HISTORY, loopHealth.snapshot(PollLoop.MEDIUM).lastFailureStep)
        orchestrator.stop()
    }

    @Test
    fun `medium loop survives a throwing repository write and keeps polling`() = runTest {
        stubBolusHistory()
        coEvery { repository.saveBoluses(any()) } throws RuntimeException("Room write failed")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        advanceTimeBy(MEDIUM_CYCLE_MS)

        coVerify(exactly = 2) { pumpDriver.getBolusHistory(any(), any()) }
        assertNull(loopHealth.snapshot(PollLoop.MEDIUM).lastSuccessAtMs)
        orchestrator.stop()
    }

    @Test
    fun `slow loop survives a throwing history DAO and keeps polling`() = runTest {
        stubHistoryBackfill()
        coEvery { historyBackfill.persistRawBatch(any()) } throws RuntimeException("SQLCipher error")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        coVerify(exactly = 1) { historyBackfill.persistRawBatch(any()) }
        // Steps after the failing one still run in the same iteration.
        coVerify(exactly = 1) { pumpDriver.getPumpHardwareInfo() }

        advanceTimeBy(SLOW_CYCLE_MS)
        coVerify(exactly = 2) { pumpDriver.getBatteryStatus() }
        coVerify(exactly = 2) { historyBackfill.persistRawBatch(any()) }
        assertEquals(PollStep.HISTORY_LOGS, loopHealth.snapshot(PollLoop.SLOW).lastFailureStep)
        orchestrator.stop()
    }

    @Test
    fun `slow loop survives a throwing history parser and keeps polling`() = runTest {
        stubHistoryBackfill()
        // A pump that keeps producing records: every fetch answers past the sequence it was
        // asked from, so the parser is reached again on the next cycle rather than short
        // -circuiting on the "sequence not advancing" guard.
        coEvery { pumpDriver.getHistoryLogs(any()) } coAnswers {
            Result.success(listOf(historyRecords.first().copy(sequenceNumber = firstArg<Int>() + 1)))
        }
        every {
            historyLogParser.extractCgmFromHistoryLogs(any(), any())
        } throws IllegalArgumentException("unparseable record")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        verify(exactly = 1) { historyLogParser.extractCgmFromHistoryLogs(any(), any()) }

        advanceTimeBy(SLOW_CYCLE_MS)
        verify(exactly = 2) { historyLogParser.extractCgmFromHistoryLogs(any(), any()) }
        coVerify(exactly = 2) { pumpDriver.getReservoirLevel() }
        assertEquals(PollStep.HISTORY_LOGS, loopHealth.snapshot(PollLoop.SLOW).lastFailureStep)
        orchestrator.stop()
    }

    @Test
    fun `slow loop survives a throwing sync enqueuer and keeps polling`() = runTest {
        coEvery { syncEnqueuer.enqueueBattery(any()) } throws RuntimeException("sync queue full")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        // Battery threw; reservoir and the rest of the iteration are untouched.
        coVerify(exactly = 1) { repository.saveReservoir(any()) }

        advanceTimeBy(SLOW_CYCLE_MS)
        coVerify(exactly = 2) { pumpDriver.getBatteryStatus() }
        assertEquals(PollStep.BATTERY, loopHealth.snapshot(PollLoop.SLOW).lastFailureStep)
        orchestrator.stop()
    }

    @Test
    fun `every loop publishes a last-successful-iteration heartbeat`() = runTest {
        val orchestrator = createOrchestrator()
        PollLoop.entries.forEach { assertNull(loopHealth.snapshot(it).lastSuccessAtMs) }

        orchestrator.start(this)
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        PollLoop.entries.forEach { loop ->
            val health = loopHealth.snapshot(loop)
            assertTrue("$loop should be running", health.running)
            assertNotNull("$loop should have a heartbeat", health.lastSuccessAtMs)
            assertTrue("$loop should have completed an iteration", health.successCount >= 1)
        }
        orchestrator.stop()
    }

    @Test
    fun `heartbeat freezes while a step keeps failing and resumes once it recovers`() = runTest {
        // The debug fault toggle is the same seam the on-device harness uses.
        every { appSettingsStore.debugFaultPollStep } returns PollStep.IOB.telemetryName
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        advanceTimeBy(FAST_CYCLE_MS)

        // Polling continues, but a loop that never completes an iteration must not claim health.
        coVerify(atLeast = 2) { pumpDriver.getBasalRate() }
        assertNull(loopHealth.snapshot(PollLoop.FAST).lastSuccessAtMs)
        assertTrue(loopHealth.snapshot(PollLoop.FAST).failureCount >= 2)
        // The current-outage counter is what makes the recovery reportable to telemetry.
        assertTrue(loopHealth.snapshot(PollLoop.FAST).failuresSinceLastSuccess >= 2)

        every { appSettingsStore.debugFaultPollStep } returns ""
        advanceTimeBy(FAST_CYCLE_MS)
        val recovered = loopHealth.snapshot(PollLoop.FAST)
        assertNotNull(recovered.lastSuccessAtMs)
        assertEquals(0L, recovered.failuresSinceLastSuccess)
        assertTrue("lifetime failures survive the recovery", recovered.failureCount >= 2)
        orchestrator.stop()
    }

    @Test
    fun `a throwing history anchor read does not stop polling and is retried`() = runTest {
        stubHistoryBackfill()
        // A broken database must cost one backfill cycle, not the whole service: this throw used
        // to reach the connection watcher and kill it outright -- no loops, no polling, no
        // telemetry -- and must never be read as "fresh install, download everything" either.
        coEvery { historyBackfill.processedThroughSequence() } throws RuntimeException("SQLCipher")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        coVerify(atLeast = 1) { pumpDriver.getIoB() }
        PollLoop.entries.forEach { assertTrue("$it should run", loopHealth.snapshot(it).running) }
        // The slow loop retried the anchor, reported the retry's failure against its own step, and
        // did NOT backfill from a bogus anchor of 0 (which would re-read the pump's full history).
        coVerify(exactly = 0) { pumpDriver.getHistoryLogs(any()) }
        coVerify(exactly = 0) { pumpDriver.getFullHistoryLogs(any()) }
        assertEquals(PollStep.HISTORY_LOGS, loopHealth.snapshot(PollLoop.SLOW).lastFailureStep)
        // The rest of the slow iteration is untouched by it.
        coVerify(atLeast = 1) { pumpDriver.getBatteryStatus() }

        // Once Room answers again, the backfill resumes from the stored anchor.
        coEvery { historyBackfill.processedThroughSequence() } returns 4_200
        advanceTimeBy(SLOW_CYCLE_MS)
        coVerify(atLeast = 1) { pumpDriver.getHistoryLogs(4_200) }
        orchestrator.stop()
    }

    @Test
    fun `a loop that throws outside a step guard is restarted with backoff`() = runTest {
        every { appSettingsStore.debugFaultPollStep } returns PollLoop.FAST.loopFaultKey
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(10_000)

        // The body never reaches a step, but the loop job is alive and being relaunched.
        coVerify(exactly = 0) { pumpDriver.getIoB() }
        val restarted = loopHealth.snapshot(PollLoop.FAST)
        assertTrue("expected restarts, got ${restarted.restartCount}", restarted.restartCount >= 3)
        assertTrue(restarted.running)
        // Backoff, not a hot loop: unbounded retries would be far more than one per second.
        assertTrue("restarts should back off", restarted.restartCount <= 10)
        // The other loops are untouched by the fast loop's failure.
        assertEquals(0L, loopHealth.snapshot(PollLoop.SLOW).restartCount)

        every { appSettingsStore.debugFaultPollStep } returns ""
        advanceTimeBy(60_000)
        coVerify(atLeast = 1) { pumpDriver.getIoB() }
        assertNotNull(loopHealth.snapshot(PollLoop.FAST).lastSuccessAtMs)
        orchestrator.stop()
    }

    @Test
    fun `a step that fails forever reports once at ERROR, then on the reminder ladder`() = runTest {
        // ERROR is what Sentry turns into an event, and the loop keeps iterating through a failure
        // by design — so a stuck step must not bill one event per iteration for as long as the
        // pump stays connected.
        val tree = RecordingTree()
        Timber.plant(tree)
        every { appSettingsStore.debugFaultPollStep } returns PollStep.IOB.telemetryName
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        repeat(7) { advanceTimeBy(FAST_CYCLE_MS) }

        assertEquals(8L, loopHealth.snapshot(PollLoop.FAST).failuresSinceLastSuccess)
        // Failures 1, 2, 4 and 8 of the outage: the opening edge plus doubling reminders.
        assertEquals(4, tree.at(Log.ERROR, "Poll step failed", "step=iob").size)
        // Failures 3, 5, 6 and 7 stay on the device.
        assertEquals(4, tree.at(Log.DEBUG, "Poll step still failing", "step=iob").size)
        orchestrator.stop()
    }

    @Test
    fun `recovery still reports, and the next outage opens a fresh ERROR`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        every { appSettingsStore.debugFaultPollStep } returns PollStep.IOB.telemetryName
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        repeat(3) { advanceTimeBy(FAST_CYCLE_MS) }
        assertEquals(3, tree.at(Log.ERROR, "Poll step failed", "step=iob").size)

        // Damping the repeats must not damp the recovery: the WARN that closes the outage is the
        // other half of the opening event.
        every { appSettingsStore.debugFaultPollStep } returns ""
        advanceTimeBy(FAST_CYCLE_MS)
        assertEquals(1, tree.at(Log.WARN, "Poll loop fast recovered").size)

        // A different step failing after that success is a new outage, not a damped repeat.
        every { appSettingsStore.debugFaultPollStep } returns PollStep.BASAL.telemetryName
        advanceTimeBy(FAST_CYCLE_MS)
        assertEquals(1, tree.at(Log.ERROR, "Poll step failed", "step=basal").size)
        orchestrator.stop()
    }

    @Test
    fun `a second step failing mid-outage is reported even though the ladder is quiet`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        every { appSettingsStore.debugFaultPollStep } returns PollStep.IOB.telemetryName
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(SETTLE_TIME_MS)
        repeat(2) { advanceTimeBy(FAST_CYCLE_MS) }
        assertEquals(3L, loopHealth.snapshot(PollLoop.FAST).failuresSinceLastSuccess)

        // A different step starts failing while the IoB outage is still running.
        coEvery { syncEnqueuer.enqueueBasal(any()) } throws RuntimeException("sync queue full")
        advanceTimeBy(FAST_CYCLE_MS)

        // It lands at outage ordinal 5, so the ladder would have kept it silent; it is reported
        // because nothing in this outage has failed this way before.
        assertEquals(5L, loopHealth.snapshot(PollLoop.FAST).failuresSinceLastSuccess)
        assertEquals(1, tree.at(Log.ERROR, "Poll step failed", "step=basal").size)
        orchestrator.stop()
    }

    @Test
    fun `a loop that restarts forever reports on the same ladder`() = runTest {
        val tree = RecordingTree()
        Timber.plant(tree)
        every { appSettingsStore.debugFaultPollStep } returns PollLoop.FAST.loopFaultKey
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(10_000)

        // Restarts at 1s, 2s, 4s and 8s of backoff; only the 1st, 2nd and 4th are events.
        assertEquals(4L, loopHealth.snapshot(PollLoop.FAST).restartCount)
        assertEquals(3, tree.at(Log.ERROR, "Poll loop fast stopped outside a guarded step").size)
        assertEquals(1, tree.at(Log.DEBUG, "Poll loop fast still failing outside a guarded step").size)
        orchestrator.stop()
    }

    @Test
    fun `a CancellationException from a step is not swallowed - the loop dies, others live`() =
        runTest {
            coEvery { repository.saveIoB(any()) } throws CancellationException("scope cancelled")
            val orchestrator = createOrchestrator()
            orchestrator.start(this)

            connectionStateFlow.value = ConnectionState.CONNECTED
            advanceTimeBy(ALL_SETTLE_MS)

            // Cancellation propagates through runStep and the supervisor: the fast loop is gone
            // (no restart, no second poll), and its liveness says so rather than going stale
            // while claiming to run.
            coVerify(exactly = 1) { pumpDriver.getIoB() }
            val fast = loopHealth.snapshot(PollLoop.FAST)
            assertFalse("cancelled loop must not report running", fast.running)
            assertEquals(0L, fast.restartCount)
            // Sibling loops are unaffected — they share only a SupervisorJob.
            assertTrue(loopHealth.snapshot(PollLoop.SLOW).running)
            coVerify(atLeast = 1) { pumpDriver.getBatteryStatus() }
            orchestrator.stop()
        }

    @Test
    fun `disconnect and stop cancel every loop cleanly - no leaked loops`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        PollLoop.entries.forEach { assertTrue("$it should run", loopHealth.snapshot(it).running) }

        connectionStateFlow.value = ConnectionState.DISCONNECTED
        advanceTimeBy(1000)
        PollLoop.entries.forEach {
            assertFalse("$it should stop on disconnect", loopHealth.snapshot(it).running)
        }

        // ...and again through the onDestroy path, from a running state.
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        assertTrue(loopHealth.snapshot(PollLoop.FAST).running)
        val pollsBeforeStop = loopHealth.snapshot(PollLoop.FAST).successCount

        orchestrator.stop()
        advanceTimeBy(ALL_SETTLE_MS)
        PollLoop.entries.forEach {
            assertFalse("$it should stop on onDestroy", loopHealth.snapshot(it).running)
        }
        assertEquals(pollsBeforeStop, loopHealth.snapshot(PollLoop.FAST).successCount)
    }

    @Test
    fun `restarting a loop resets its session liveness`() = runTest {
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        assertTrue(loopHealth.snapshot(PollLoop.FAST).successCount >= 1)

        // A reconnect starts fresh loop jobs; carrying the old heartbeat over would let a
        // watchdog call a loop that has not yet done anything healthy.
        connectionStateFlow.value = ConnectionState.DISCONNECTED
        advanceTimeBy(1000)
        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(1)

        val fresh = loopHealth.snapshot(PollLoop.FAST)
        assertEquals(0L, fresh.successCount)
        assertNull(fresh.lastSuccessAtMs)
        assertNotNull(fresh.startedAtMs)

        // The relaunched loop polls and reports live. (The outgoing job's teardown racing the
        // replacement's start is a scheduling order this virtual-time test cannot force; the
        // session guard that settles it is covered in PollLoopHealthTrackerTest.)
        advanceTimeBy(SETTLE_TIME_MS)
        assertTrue("relaunched loop must report running", loopHealth.snapshot(PollLoop.FAST).running)
        coVerify(atLeast = 2) { pumpDriver.getIoB() }
        orchestrator.stop()
    }

    // -- GLY-250: the resume cursor must never run ahead of committed derived data ----
    // The backfill used to insert raw rows, take MAX(sequenceNumber) off that table as the
    // resume anchor, and only then derive and save CGM/bolus/basal rows. Anything that failed
    // in between left the anchor above records nothing would ever re-derive. These tests pin
    // the ordering; HistoryBackfillWriterTest pins the atomicity it relies on.

    /** A resume, not a fresh install: a non-zero anchor takes the incremental fetch path. */
    private val RESUME_ANCHOR = 500

    /** One batch sitting above [RESUME_ANCHOR]. */
    private val resumeBatch = listOf(historyRecords.first().copy(sequenceNumber = 600))

    /** Backfill that resumes from [RESUME_ANCHOR] and answers one batch of [resumeBatch]. */
    private fun stubResumedBackfill() {
        stubHistoryBackfill()
        coEvery { historyBackfill.processedThroughSequence() } returns RESUME_ANCHOR
        coEvery { pumpDriver.getHistoryLogs(any()) } returns Result.success(resumeBatch)
    }

    @Test
    fun `the resume anchor comes from the backfill cursor, not the raw table`() = runTest {
        stubResumedBackfill()
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        coVerify(exactly = 1) { pumpDriver.getHistoryLogs(RESUME_ANCHOR) }
        // A stored cursor is not a fresh install, so no full-history download.
        coVerify(exactly = 0) { pumpDriver.getFullHistoryLogs(any()) }
        orchestrator.stop()
    }

    @Test
    fun `a batch persists raw bytes first, then commits everything derived in one call`() = runTest {
        stubResumedBackfill()
        val cgm = listOf(
            CgmReading(glucoseMgDl = 118, trendArrow = CgmTrend.FLAT, timestamp = Instant.now()),
        )
        every { historyLogParser.extractCgmFromHistoryLogs(any(), any()) } returns cgm
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        coVerifyOrder {
            historyBackfill.persistRawBatch(resumeBatch)
            historyBackfill.commitDerivedBatch(listOf(600), cgm, emptyList(), emptyList(), 600, any())
        }
        // The derived writes and their queue rows belong to the batch transaction now; the
        // backfill must not reach around it to the loose per-reading writers.
        coVerify(exactly = 0) { repository.saveCgmBatch(any()) }
        coVerify(exactly = 0) { syncEnqueuer.enqueueBasalBatch(any()) }
        orchestrator.stop()
    }

    @Test
    fun `a failed derived commit leaves the anchor where it was and the batch is refetched`() = runTest {
        stubResumedBackfill()
        coEvery {
            historyBackfill.commitDerivedBatch(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("killed mid-batch")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        coVerify(exactly = 1) { pumpDriver.getHistoryLogs(RESUME_ANCHOR) }

        // Next cycle asks from the SAME sequence: an uncommitted batch is not progress. Before
        // GLY-250 the anchor had already moved to 600 and those records were gone for good.
        advanceTimeBy(SLOW_CYCLE_MS)
        coVerify(exactly = 2) { pumpDriver.getHistoryLogs(RESUME_ANCHOR) }
        coVerify(exactly = 0) { pumpDriver.getHistoryLogs(600) }
        assertEquals(PollStep.HISTORY_LOGS, loopHealth.snapshot(PollLoop.SLOW).lastFailureStep)
        orchestrator.stop()
    }

    @Test
    fun `an unparseable record fails the batch before anything derived is committed`() = runTest {
        stubResumedBackfill()
        every {
            historyLogParser.extractBasalFromHistoryLogs(any(), any())
        } throws IllegalArgumentException("unparseable record")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        // Raw bytes are kept -- they are the only copy of what the pump said, and a later build
        // (or the re-derivation pass) can still read them.
        coVerify(exactly = 1) { historyBackfill.persistRawBatch(resumeBatch) }
        coVerify(exactly = 0) {
            historyBackfill.commitDerivedBatch(any(), any(), any(), any(), any(), any())
        }
        advanceTimeBy(SLOW_CYCLE_MS)
        coVerify(exactly = 2) { pumpDriver.getHistoryLogs(RESUME_ANCHOR) }
        orchestrator.stop()
    }

    @Test
    fun `a committed batch moves the anchor so the next batch resumes above it`() = runTest {
        stubResumedBackfill()
        // A pump with more history than one batch: each fetch answers just past what it was
        // asked from, so the anchor's movement is visible in the next request.
        coEvery { pumpDriver.getHistoryLogs(any()) } coAnswers {
            Result.success(listOf(historyRecords.first().copy(sequenceNumber = firstArg<Int>() + 10)))
        }
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        coVerify(exactly = 1) { pumpDriver.getHistoryLogs(RESUME_ANCHOR) }
        coVerify(exactly = 1) { pumpDriver.getHistoryLogs(RESUME_ANCHOR + 10) }
        coVerifyOrder {
            historyBackfill.commitDerivedBatch(
                listOf(RESUME_ANCHOR + 10), any(), any(), any(), RESUME_ANCHOR + 10, any(),
            )
            historyBackfill.commitDerivedBatch(
                listOf(RESUME_ANCHOR + 20), any(), any(), any(), RESUME_ANCHOR + 20, any(),
            )
        }
        orchestrator.stop()
    }

    @Test
    fun `the driver's scan position is acknowledged only after the batch commits`() = runTest {
        stubResumedBackfill()
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        coVerifyOrder {
            historyBackfill.commitDerivedBatch(any(), any(), any(), any(), any(), any())
            pumpDriver.acknowledgeHistoryLogs()
        }
        orchestrator.stop()
    }

    @Test
    fun `a failed commit leaves the driver's scan position unacknowledged`() = runTest {
        stubResumedBackfill()
        coEvery {
            historyBackfill.commitDerivedBatch(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("killed mid-batch")
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        // The whole point of the acknowledgement: a driver that keeps its own scan position must
        // not step past a batch nobody persisted. A BLE flap between fetch and commit is exactly
        // this shape, and moving the position there loses the batch for good.
        coVerify(exactly = 0) { pumpDriver.acknowledgeHistoryLogs() }
        orchestrator.stop()
    }

    @Test
    fun `a batch at or below the cursor is acknowledged so the scan can move past it`() = runTest {
        stubHistoryBackfill()
        coEvery { historyBackfill.processedThroughSequence() } returns RESUME_ANCHOR
        // What a Tandem rescan hands over after a reconnect: records the cursor already covers.
        coEvery { pumpDriver.getHistoryLogs(any()) } returns Result.success(
            listOf(historyRecords.first().copy(sequenceNumber = RESUME_ANCHOR - 50)),
        )
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)

        // Nothing to commit -- but withholding the acknowledgement here would pin a progressive
        // scanner on the same window forever, so the backfill would never reach what IS new.
        coVerify(exactly = 0) {
            historyBackfill.commitDerivedBatch(any(), any(), any(), any(), any(), any())
        }
        coVerify(atLeast = 1) { pumpDriver.acknowledgeHistoryLogs() }
        orchestrator.stop()
    }

    @Test
    fun `the anchor is re-read every cycle, so the in-memory mirror cannot lag the cursor`() = runTest {
        stubResumedBackfill()
        val orchestrator = createOrchestrator()
        orchestrator.start(this)

        connectionStateFlow.value = ConnectionState.CONNECTED
        advanceTimeBy(ALL_SETTLE_MS)
        coVerify(exactly = 1) { pumpDriver.getHistoryLogs(RESUME_ANCHOR) }

        // The durable cursor moved without this process seeing it: a commit that lands and then
        // throws CancellationException on the way out (a BLE flap during the commit does that)
        // leaves the in-memory mirror behind. Re-reading is what stops the next cycle from
        // re-fetching -- and re-uploading -- a batch that is already committed.
        coEvery { historyBackfill.processedThroughSequence() } returns RESUME_ANCHOR + 500
        advanceTimeBy(SLOW_CYCLE_MS)
        coVerify(exactly = 1) { pumpDriver.getHistoryLogs(RESUME_ANCHOR + 500) }
        orchestrator.stop()
    }
}
