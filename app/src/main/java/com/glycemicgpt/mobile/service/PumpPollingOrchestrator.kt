package com.glycemicgpt.mobile.service

import com.glycemicgpt.mobile.data.local.AppSettingsStore
import com.glycemicgpt.mobile.data.local.GlucoseRangeStore
import com.glycemicgpt.mobile.data.local.SafetyLimitsStore
import com.glycemicgpt.mobile.domain.alerting.AlertTypes
import com.glycemicgpt.mobile.data.local.dao.RawHistoryLogDao
import com.glycemicgpt.mobile.domain.format.GlucoseFormat
import com.glycemicgpt.mobile.domain.model.PumpActivityMode
import com.glycemicgpt.mobile.data.local.entity.RawHistoryLogEntity
import com.glycemicgpt.mobile.data.repository.PumpDataRepository
import com.glycemicgpt.mobile.data.repository.SyncQueueEnqueuer
import com.glycemicgpt.mobile.domain.model.CgmReading
import com.glycemicgpt.mobile.domain.model.ConnectionState
import com.glycemicgpt.mobile.domain.model.GlucoseUnit
import com.glycemicgpt.mobile.domain.pump.HistoryLogParser
import com.glycemicgpt.mobile.domain.pump.PumpDriver
import com.glycemicgpt.mobile.wear.WearDataSender
import com.glycemicgpt.mobile.wear.WearHistorySerializer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates periodic polling of pump data and persists results to Room.
 *
 * Poll intervals:
 * - IoB + basal rate + CGM: every 15 seconds (keep-alive; pump drops idle connections at ~30s)
 * - Bolus history: every 5 minutes (AC2)
 * - Battery + reservoir: every 5 minutes (AC3)
 *
 * Polling pauses when BLE connection is lost and resumes on reconnect (AC7).
 * Reduces frequency when phone battery is low (AC8).
 *
 * Failure model (GLY-249). The loops run under the service's `SupervisorJob`, so an escaping
 * throw kills only its own loop — silently, and for the rest of the service's life. Three layers
 * stop that:
 * 1. Every step runs through [runStep]: a throw is reported against (loop, step) and the loop
 *    moves on to the next step. One bad row, parse, or sync enqueue costs one reading, not history
 *    backfill forever.
 * 2. Each loop body runs under [superviseLoop]: anything that escapes the step guards is reported,
 *    backed off, and the loop is relaunched rather than left dead.
 * 3. Every loop publishes a heartbeat to [PollLoopHealthTracker] — the timestamp of the last
 *    iteration in which every step succeeded — which the debug console shows and the polling
 *    watchdog (GLY-254) judges liveness by. A step that fails forever freezes the heartbeat
 *    instead of faking health. The heartbeat also rides along in telemetry: every failure and
 *    restart report carries [PollLoopHealth.telemetrySummary], and a loop that comes back after
 *    failing reports the recovery, so both edges of an outage are visible remotely.
 *
 * `CancellationException` is never caught anywhere in this chain (it is rethrown first, following
 * [processCgmReading]), so disconnect and `onDestroy` still tear the loops down immediately.
 */
@Singleton
class PumpPollingOrchestrator @Inject constructor(
    private val pumpDriver: PumpDriver,
    private val repository: PumpDataRepository,
    private val syncEnqueuer: SyncQueueEnqueuer,
    private val rawHistoryLogDao: RawHistoryLogDao,
    private val wearDataSender: WearDataSender,
    private val glucoseRangeStore: GlucoseRangeStore,
    private val safetyLimitsStore: SafetyLimitsStore,
    private val historyLogParser: HistoryLogParser,
    private val appSettingsStore: AppSettingsStore,
    private val alertFloor: AlertFloor,
    private val loopHealth: PollLoopHealthTracker,
) {

    /** Set by PumpConnectionService to trigger immediate sync after enqueue. */
    @Volatile
    var backendSyncManager: BackendSyncManager? = null

    /** Track the last known raw event sequence number to fetch incrementally. */
    @Volatile
    private var lastSequenceNumber: Int = 0

    /** Whether [lastSequenceNumber] has been resolved from Room this process. Until it has,
     *  [lastSequenceNumber] is not a resume anchor, just its zero default — see
     *  [readSequenceAnchor]. */
    @Volatile
    private var sequenceAnchorRestored: Boolean = false

    /** Whether hardware info has been cached this session. */
    @Volatile
    private var hardwareInfoCached: Boolean = false

    /** Whether we have been connected at least once this service session.
     *  Used to distinguish reconnection (accelerated polling) from initial connection. */
    @Volatile
    private var hasBeenConnectedBefore: Boolean = false

    /** Track the last alert type sent to watch to avoid re-sending the same alert.
     *  Guarded by [watchRelayMutex] in [processCgmReading]; the disconnect reset in [start]
     *  is safe unsynchronized (polling is already cancelled there). */
    @Volatile
    private var previousAlertType: String? = null

    /** Serializes the watch alert edge-latch across the poll loop, the Home manual refresh,
     *  and the debug inject. */
    private val watchRelayMutex = Mutex()

    /** Wall-clock ms of the last watch alert push (any kind) and of the last push that was
     *  allowed to buzz, for the ongoing-episode refresh/re-buzz cadence. Guarded by
     *  [watchRelayMutex] like the latch they accompany. */
    private var lastAlertSentAtMs = 0L
    private var lastAlertBuzzAtMs = 0L

    private val lock = Any()
    private var fastJob: Job? = null
    private var mediumJob: Job? = null
    private var slowJob: Job? = null
    private var connectionWatcherJob: Job? = null

    @Volatile
    var phoneBatteryLow: Boolean = false

    /**
     * Start watching connection state and polling when connected.
     * Call this from the foreground service's onCreate.
     */
    fun start(scope: CoroutineScope) {
        stop() // cancel any previous jobs
        synchronized(lock) {
            connectionWatcherJob = scope.launch {
                // Restore last known sequence number from Room to avoid re-downloading.
                // Guarded (GLY-249): this read used to run bare, so a Room/SQLCipher failure here
                // killed the watcher before it ever observed a connection state — no loops, no
                // polling, no telemetry, for the life of the service. The slow loop retries it.
                try {
                    readSequenceAnchor()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to read the history resume anchor; the slow loop will retry")
                }

                pumpDriver.observeConnectionState().collectLatest { state ->
                    if (state == ConnectionState.CONNECTED) {
                        val isReconnection = hasBeenConnectedBefore
                        hasBeenConnectedBefore = true
                        if (isReconnection) {
                            Timber.d("Pump reconnected, starting accelerated polling (reduced initial delays)")
                            startReconnectionPollingLoops(scope)
                        } else {
                            Timber.d("Pump connected (initial), starting normal polling")
                            startPollingLoops(scope)
                        }
                    } else {
                        // Any non-CONNECTED state (e.g. SCANNING/CONNECTING/AUTHENTICATING/DISCONNECTED)
                        // pauses polling; log the actual state instead of always saying "disconnected",
                        // which misleads debugging during a pairing attempt (issue #844).
                        // The latch reset deliberately does NOT clearAlert the wrist: an alert
                        // shown at disconnect may still be true, and the watch ages it out on
                        // its own clock (GLY-116 axis b) instead of flipping to "All clear".
                        Timber.d("Pump not ready (current state=%s), pausing polling", state)
                        previousAlertType = null
                        cancelPollingLoops()
                    }
                }
            }
        }
    }

    /** Stop all polling. Call from service onDestroy. */
    fun stop() {
        synchronized(lock) {
            cancelPollingLoops()
            connectionWatcherJob?.cancel()
            connectionWatcherJob = null
            hasBeenConnectedBefore = false
        }
    }

    private fun startPollingLoops(scope: CoroutineScope) {
        synchronized(lock) {
            cancelPollingLoops()

            fastJob = scope.launch {
                superviseLoop(PollLoop.FAST, INITIAL_POLL_DELAY_MS, ::pollFastLoop)
            }
            mediumJob = scope.launch {
                superviseLoop(PollLoop.MEDIUM, MEDIUM_LOOP_INITIAL_DELAY_MS, ::pollMediumLoop)
            }
            slowJob = scope.launch {
                superviseLoop(PollLoop.SLOW, SLOW_LOOP_INITIAL_DELAY_MS, ::pollSlowLoop)
            }
        }
    }

    /**
     * Start polling with reduced initial delays for reconnection.
     * Bolus history fires within 5s instead of 60s to backfill missed data.
     * Battery/reservoir/history fires within 3s instead of 30s.
     */
    private fun startReconnectionPollingLoops(scope: CoroutineScope) {
        synchronized(lock) {
            cancelPollingLoops()

            fastJob = scope.launch {
                superviseLoop(PollLoop.FAST, INITIAL_POLL_DELAY_MS, ::pollFastLoop)
            }
            mediumJob = scope.launch {
                superviseLoop(PollLoop.MEDIUM, RECONNECT_MEDIUM_DELAY_MS, ::pollMediumLoop)
            }
            slowJob = scope.launch {
                superviseLoop(PollLoop.SLOW, RECONNECT_SLOW_DELAY_MS, ::pollSlowLoop)
            }
        }
    }

    private fun cancelPollingLoops() {
        synchronized(lock) {
            fastJob?.cancel()
            mediumJob?.cancel()
            slowJob?.cancel()
            fastJob = null
            mediumJob = null
            slowJob = null
        }
    }

    /**
     * Resolve the history resume anchor — the highest sequence number already stored — so the
     * backfill continues where it left off instead of re-reading the pump's whole history.
     *
     * Throws whatever Room throws; both callers decide what that means for them.
     */
    private suspend fun readSequenceAnchor() {
        lastSequenceNumber = rawHistoryLogDao.getMaxSequenceNumber() ?: 0
        sequenceAnchorRestored = true
    }

    private fun effectiveInterval(baseMs: Long): Long =
        if (phoneBatteryLow) baseMs * LOW_BATTERY_MULTIPLIER else baseMs

    /**
     * Runs one loop body under a supervising restart (GLY-249). The per-step guards already absorb
     * everything expected, so reaching the catch here means something threw where nothing should
     * have — exactly the case that used to leave a loop permanently dead under the service's
     * `SupervisorJob`. Report it, back off, relaunch.
     *
     * The relaunch passes an initial delay of 0: the loop's own initial delay exists to let BLE
     * settle after connecting, which already happened; the backoff is the only wait that applies.
     *
     * Backoff escalates while restarts keep coming and resets as soon as the loop manages a clean
     * iteration, so a persistently broken loop retries at [RESTART_BACKOFF_MAX_MS] instead of hot
     * -looping, while a one-off blip costs [RESTART_BACKOFF_BASE_MS].
     *
     * `CancellationException` is rethrown untouched, so disconnect and `onDestroy` still stop the
     * loop instead of restarting it.
     */
    private suspend fun superviseLoop(
        loop: PollLoop,
        initialDelayMs: Long,
        body: suspend (Long) -> Unit,
    ) {
        val session = loopHealth.markRunning(loop)
        var restartAttempt = 0
        var nextInitialDelayMs = initialDelayMs
        try {
            while (true) {
                val successesBefore = loopHealth.snapshot(loop).successCount
                var failure: Exception? = null
                try {
                    // The bodies never return normally; if one ever does, the loop is just as dead
                    // as if it had thrown, so it takes the same restart path (with a null cause).
                    body(nextInitialDelayMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                }
                if (loopHealth.snapshot(loop).successCount > successesBefore) {
                    restartAttempt = 0
                }
                loopHealth.recordLoopRestart(loop, failure)
                val backoffMs = restartBackoffMs(restartAttempt)
                // Loop, cause and liveness are separate format args: "which loop died", "what
                // killed it" and "how long it has been without a clean iteration" are three
                // different questions of the same telemetry event.
                Timber.e(
                    failure,
                    "Poll loop %s stopped outside a guarded step (%s); restarting in %d ms [%s]",
                    loop.telemetryName,
                    failure?.javaClass?.simpleName ?: "body returned normally",
                    backoffMs,
                    loopHealth.snapshot(loop).telemetrySummary(),
                )
                delay(backoffMs)
                restartAttempt++
                nextInitialDelayMs = 0L
            }
        } finally {
            loopHealth.markStopped(loop, session)
        }
    }

    private fun restartBackoffMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_RESTART_BACKOFF_SHIFT)
        return (RESTART_BACKOFF_BASE_MS shl shift).coerceAtMost(RESTART_BACKOFF_MAX_MS)
    }

    /**
     * Runs one poll step behind its own guard and reports the outcome against (loop, step).
     *
     * Returns whether the step completed, so the caller can withhold the loop's heartbeat for an
     * iteration that did not fully succeed. A failure is never rethrown: the next step in the same
     * iteration still runs, because battery and reservoir have no business going dark over a
     * history-log parse error.
     *
     * `CancellationException` is rethrown before the catch, following [processCgmReading] — a
     * cancelled loop must die, not log and carry on.
     */
    private suspend fun runStep(step: PollStep, block: suspend () -> Unit): Boolean {
        return try {
            throwIfFaultArmed(step.telemetryName)
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loopHealth.recordStepFailure(step, e)
            // Loop and step are separate format args so a log/Sentry search can isolate either
            // axis: "which loop is broken" and "which step breaks it" are different questions.
            // The liveness summary rides along so the event also answers "and for how long".
            Timber.e(
                e,
                "Poll step failed (loop=%s step=%s); continuing loop [%s]",
                step.loop.telemetryName,
                step.telemetryName,
                loopHealth.snapshot(step.loop).telemetrySummary(),
            )
            false
        }
    }

    /**
     * Publishes the loop's heartbeat for an iteration in which every step succeeded, and reports
     * the recovering edge when the loop had been failing.
     *
     * Only the recovery is logged, at WARN so it reaches telemetry: a healthy heartbeat every 15
     * seconds would be pure noise, while "the slow loop is polling again after 6 failures and 31
     * minutes dark" is the other half of the failure event that opened the outage.
     */
    private fun markIterationSucceeded(loop: PollLoop) {
        val nowMs = System.currentTimeMillis()
        val before = loopHealth.recordIterationSuccess(loop, nowMs)
        if (before.failuresSinceLastSuccess == 0L) return
        val darkSinceMs = before.lastSuccessAtMs ?: before.startedAtMs
        Timber.w(
            "Poll loop %s recovered after %d failure(s), %s without a clean iteration " +
                "(last failing step=%s)",
            loop.telemetryName,
            before.failuresSinceLastSuccess,
            darkSinceMs?.let { "${nowMs - it}ms" } ?: "unknown time",
            before.lastFailureStep?.telemetryName ?: "none (loop body)",
        )
    }

    /**
     * Debug-build fault injection (GLY-249): throws when the armed key matches, so the recovery
     * paths can be watched on a real device. Called from inside [runStep] for step keys and from
     * the top of each loop body for [PollLoop.loopFaultKey] — the latter deliberately outside the
     * step guard, so it exercises the supervising restart rather than the per-step catch.
     *
     * Release builds read an always-empty key from the store, so nothing can ever match.
     */
    private fun throwIfFaultArmed(key: String) {
        if (appSettingsStore.debugFaultPollStep == key) {
            throw IllegalStateException("Injected debug poll fault: $key")
        }
    }

    /**
     * Fast loop: IoB + basal rate + CGM at least every ~15s.
     *
     * Waits [initialDelayMs] for the connection to stabilize, then
     * staggers requests by [REQUEST_STAGGER_MS] to avoid overwhelming the
     * pump with simultaneous BLE writes. The actual period is approximately
     * INTERVAL_FAST_MS + (FAST_REQUEST_COUNT - 1) * REQUEST_STAGGER_MS
     * plus any BLE response latency.
     */
    private suspend fun pollFastLoop(initialDelayMs: Long) {
        delay(initialDelayMs)
        while (true) {
            throwIfFaultArmed(PollLoop.FAST.loopFaultKey)
            var complete = runStep(PollStep.IOB) { pollIoB() }
            delay(REQUEST_STAGGER_MS)
            complete = runStep(PollStep.BASAL) { pollBasal() } && complete
            delay(REQUEST_STAGGER_MS)
            complete = runStep(PollStep.CGM) { pollCgm() } && complete
            if (complete) markIterationSucceeded(PollLoop.FAST)
            delay(effectiveInterval(INTERVAL_FAST_MS))
        }
    }

    /** Medium loop: last bolus status at least every ~5 min. */
    private suspend fun pollMediumLoop(initialDelayMs: Long) {
        delay(initialDelayMs)
        while (true) {
            throwIfFaultArmed(PollLoop.MEDIUM.loopFaultKey)
            if (runStep(PollStep.BOLUS_HISTORY) { pollBolusHistory() }) {
                markIterationSucceeded(PollLoop.MEDIUM)
            }
            delay(effectiveInterval(INTERVAL_MEDIUM_MS))
        }
    }

    /** Slow loop: battery + reservoir + raw history logs + watch history at least every ~5 min. */
    private suspend fun pollSlowLoop(initialDelayMs: Long) {
        delay(initialDelayMs)
        while (true) {
            throwIfFaultArmed(PollLoop.SLOW.loopFaultKey)
            var complete = runStep(PollStep.BATTERY) { pollBattery() }
            delay(REQUEST_STAGGER_MS)
            complete = runStep(PollStep.RESERVOIR) { pollReservoir() } && complete
            delay(REQUEST_STAGGER_MS)
            complete = runStep(PollStep.HISTORY_LOGS) { pollHistoryLogs() } && complete
            delay(REQUEST_STAGGER_MS)
            complete = runStep(PollStep.HARDWARE_INFO) { cacheHardwareInfoOnce() } && complete
            delay(REQUEST_STAGGER_MS)
            complete = runStep(PollStep.WATCH_HISTORY) { sendWatchHistoryOverlays() } && complete
            if (complete) markIterationSucceeded(PollLoop.SLOW)
            delay(effectiveInterval(INTERVAL_SLOW_MS))
        }
    }

    private suspend fun pollIoB() {
        pumpDriver.getIoB()
            .onSuccess {
                repository.saveIoB(it)
                syncEnqueuer.enqueueIoB(it)
                backendSyncManager?.triggerSync()
                try {
                    wearDataSender.sendIoB(it.iob, it.timestamp.toEpochMilli())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best-effort: an absent or unpaired watch must not fail the IoB step, which
                    // has already persisted and enqueued the reading by this point.
                    Timber.w(e, "Failed to send IoB to watch")
                }
            }
            .onFailure { Timber.w(it, "Failed to poll IoB") }
    }

    private suspend fun pollBasal() {
        pumpDriver.getBasalRate()
            .onSuccess {
                repository.saveBasal(it)
                syncEnqueuer.enqueueBasal(it)
                backendSyncManager?.triggerSync()
            }
            .onFailure { Timber.w(it, "Failed to poll basal rate") }
    }

    private suspend fun pollBolusHistory() {
        val since = repository.getLatestBolusTimestamp()
            ?: Instant.now().minus(7, ChronoUnit.DAYS)
        val limits = safetyLimitsStore.toSafetyLimits()
        pumpDriver.getBolusHistory(since, limits)
            .onSuccess { events ->
                if (events.isNotEmpty()) {
                    repository.saveBoluses(events)
                    syncEnqueuer.enqueueBoluses(events)
                    backendSyncManager?.triggerSync()
                    Timber.d("Saved %d new bolus events", events.size)
                }
            }
            .onFailure { Timber.w(it, "Failed to poll bolus history") }
    }

    /** Guarded by [runStep] like every other step — the old blanket `catch (e: Exception)` here
     *  also swallowed `CancellationException`, which kept the fast loop alive through a
     *  disconnect for one more iteration. */
    private suspend fun pollCgm() {
        pumpDriver.getCgmStatus()
            .onSuccess {
                repository.saveCgm(it)
                processCgmReading(it)
            }
            .onFailure { Timber.w(it, "Failed to poll CGM status") }
    }

    /**
     * Everything downstream of a persisted CGM reading: the watch relay and the on-device alert
     * floor. Public ONLY as the debug-harness seam — `BleDebugViewModel.injectTestCgm` drives it
     * on an emulator (which has no BLE pump, so [pollCgm] never runs there) after writing the
     * synthetic reading to Room, exercising the exact production path. The only production
     * caller is [pollCgm].
     */
    suspend fun processCgmReading(
        reading: CgmReading,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        // We send the raw mg/dL value plus a per-account unit flag so the watch
        // renders glucose in the user's unit. The wire value stays canonical mg/dL;
        // only the watch's displayed/spoken number converts.
        val glucoseUnit = appSettingsStore.glucoseUnit
        try {
            wearDataSender.sendCgm(
                mgDl = reading.glucoseMgDl,
                trend = reading.trendArrow.name,
                timestampMs = reading.timestamp.toEpochMilli(),
                low = glucoseRangeStore.low,
                high = glucoseRangeStore.high,
                urgentLow = glucoseRangeStore.urgentLow,
                urgentHigh = glucoseRangeStore.urgentHigh,
                unit = glucoseUnit,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to send CGM to watch")
        }

        // One classification feeds both consumers, off the synced server alert thresholds
        // (GLY-115) rather than the display range: the values the server's alert engine fires
        // from are the ones worth waking anyone for. AlertFloor speaks the server AlertType
        // vocabulary; the watch wire protocol keeps its own strings, so map before sending.
        // The lookup must be non-throwing: a mapping gap may cost the watch relay, never the
        // alert-floor hand-off below.
        val serverAlertType = alertFloor.classify(reading.glucoseMgDl)
        val watchAlertType = serverAlertType?.let { type ->
            SERVER_TO_WATCH_ALERT_TYPE[type]
                ?: run { Timber.w("No watch mapping for alert type %s", type); null }
        }
        // GLY-116 AC-A: the relay shares the floor's data-trust bound — a stale, never-synced,
        // or clock-rewound reading must not alert the wrist any more than it may fire the floor.
        // A not-alertable reading skips the WHOLE relay alert block: no sendAlert, no clearAlert,
        // latch untouched. Retracting the shown alert here would flip a possibly-still-true low
        // into the watch's reassuring "All clear" off data nobody can vouch for; the watch ages
        // the shown alert out on its own clock instead (axis b). clearAlert stays reserved for a
        // genuinely FRESH in-range recovery reading below. NOT an early return — the alert-floor
        // hand-off at the end of this function must still run.
        val alertable = alertFloor.isReadingAlertable(reading, nowMs)
        // Serialized: this seam is reachable from the poll loop, the Home manual refresh, and
        // the debug inject concurrently, and the previousAlertType read-check-write must be
        // atomic or an overlap can double-send or drop a needed clearAlert.
        if (alertable) {
            watchRelayMutex.withLock {
                try {
                    if (watchAlertType != null && watchAlertType != previousAlertType) {
                        sendWatchAlertLocked(watchAlertType, reading, glucoseUnit, rebuzz = true)
                        previousAlertType = watchAlertType
                        lastAlertSentAtMs = nowMs
                        lastAlertBuzzAtMs = nowMs
                    } else if (watchAlertType != null) {
                        // Ongoing episode, same type: the edge-latch alone would leave the
                        // wrist's copy frozen at the first crossing — axis (b) would then grey
                        // a still-live low as "data stale", and the wrist would never re-buzz
                        // a sustained emergency after the floor notification went local-only
                        // (D4). So the relay refreshes the shown alert (silent, new timestamp)
                        // while readings stay alertable, and re-buzzes on the floor's own
                        // re-alarm cadence — the wrist is never quieter than the phone.
                        val rebuzz = nowMs - lastAlertBuzzAtMs >= WRIST_ALERT_REBUZZ_MS
                        if (rebuzz || nowMs - lastAlertSentAtMs >= WRIST_ALERT_REFRESH_MS) {
                            sendWatchAlertLocked(watchAlertType, reading, glucoseUnit, rebuzz)
                            lastAlertSentAtMs = nowMs
                            if (rebuzz) lastAlertBuzzAtMs = nowMs
                        }
                    } else if (serverAlertType == null && previousAlertType != null) {
                        // Keyed on the CLASSIFICATION being in-range, not on watchAlertType
                        // being null: a mapping gap also yields watchAlertType == null, and
                        // clearing there would retract a possibly-still-true alert — a gap may
                        // cost the relay a push, never an "All clear".
                        wearDataSender.clearAlert()
                        previousAlertType = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to send alert to watch")
                }
            }
        } else if (watchAlertType != null) {
            Timber.w(
                "Watch alert relay suppressed: reading not alertable (type=%s)",
                watchAlertType,
            )
        }

        alertFloor.onCgmReading(reading, serverAlertType, nowMs)
    }

    /** Must be called while holding [watchRelayMutex]. */
    private suspend fun sendWatchAlertLocked(
        watchAlertType: String,
        reading: CgmReading,
        glucoseUnit: GlucoseUnit,
        rebuzz: Boolean,
    ) {
        wearDataSender.sendAlert(
            type = watchAlertType,
            bgValue = reading.glucoseMgDl,
            timestampMs = reading.timestamp.toEpochMilli(),
            message = "${alertLabel(watchAlertType)} " +
                GlucoseFormat.formatWithLabel(reading.glucoseMgDl, glucoseUnit),
            rebuzz = rebuzz,
        )
    }

    companion object {
        const val INTERVAL_FAST_MS = 15_000L       // IoB + basal + CGM (keep-alive: pump drops idle connections at ~30s)
        const val INTERVAL_MEDIUM_MS = 300_000L     // bolus history (5 min)
        const val INTERVAL_SLOW_MS = 300_000L       // battery + reservoir (5 min)

        /** Delay before first poll after connection to let the pump settle.
         *  Keep short to avoid idle-timeout disconnects (pump drops idle at ~3-5s). */
        const val INITIAL_POLL_DELAY_MS = 500L

        /** Stagger between consecutive BLE requests within a loop iteration.
         *  500ms gives the pump time to process each request and respond before
         *  the next write arrives. Lower values (200ms) can cause GATT_ERROR. */
        const val REQUEST_STAGGER_MS = 500L

        /** Wait before starting the medium loop (last bolus status).
         *  Let the fast loop run several cycles to establish a stable
         *  connection rhythm before introducing additional request types. */
        const val MEDIUM_LOOP_INITIAL_DELAY_MS = 60_000L  // 60 seconds

        /** Wait before starting the slow loop (battery, reservoir, history).
         *  Gives the fast loop a couple of cycles to stabilize BLE before
         *  introducing additional lightweight status reads. */
        const val SLOW_LOOP_INITIAL_DELAY_MS = 30_000L    // 30 seconds

        /** Delay before starting medium loop on reconnection (bolus history).
         *  Short delay to let the fast loop establish rhythm first, but much
         *  faster than the initial 60s delay for immediate bolus backfill. */
        const val RECONNECT_MEDIUM_DELAY_MS = 5_000L      // 5 seconds

        /** Delay before starting slow loop on reconnection (battery, reservoir).
         *  Let fast loop fire first, then quickly catch up on hardware status. */
        const val RECONNECT_SLOW_DELAY_MS = 3_000L        // 3 seconds

        /** Max time to spend catching up on history logs per poll cycle.
         *  Prevents monopolizing BLE during large backfills while still allowing
         *  complete catch-up within a single cycle for typical gaps (< 2 hours). */
        const val MAX_BACKFILL_DURATION_MS = 120_000L     // 2 minutes

        /** Max time for initial pump history sync on fresh install.
         *  Allows downloading the full pump history (months of data) in one pass.
         *  Increased from 10m to 20m because Medtronic initial sync can take 15+ min. */
        const val MAX_INITIAL_SYNC_DURATION_MS = 1_200_000L  // 20 minutes for full pump download

        /** Pause between consecutive history log batch fetches during catch-up.
         *  Gives fast loop (IoB/CGM) a window to fire between batches. */
        const val BACKFILL_BATCH_STAGGER_MS = 1_000L      // 1 second

        // When phone battery is low, slow everything down by this factor
        const val LOW_BATTERY_MULTIPLIER = 3

        /** First wait before a supervisor relaunches a loop that threw outside a guarded step.
         *  Short enough that a one-off blip costs a fraction of a poll interval. */
        const val RESTART_BACKOFF_BASE_MS = 1_000L

        /** Ceiling for the restart backoff: a loop that is broken for good retries at the slow
         *  loop's own cadence rather than hot-looping on the pump's BLE link and the battery. */
        const val RESTART_BACKOFF_MAX_MS = 300_000L

        /** Doubling stops here so the shift can never overflow on a long-lived service; the
         *  [RESTART_BACKOFF_MAX_MS] ceiling bites well before it anyway. */
        const val MAX_RESTART_BACKOFF_SHIFT = 16

        /** How often the relay re-pushes an ONGOING (unchanged-type) alert while readings stay
         *  alertable — silent refreshes that keep the wrist copy's timestamp current, so
         *  axis (b) never greys a still-live alert as "data stale" (the CGM STALE band starts
         *  at 6 min; 5-min refreshes keep the shown alert inside it). */
        const val WRIST_ALERT_REFRESH_MS = 5 * 60_000L

        /** Re-buzz cadence for a sustained, never-recovering alert, mirroring
         *  [AlertFloor.FLOOR_COOLDOWN_MS]: with the floor notification local-only (D4), the
         *  relay owns the wrist's re-alarm — the wrist must never go permanently silent on an
         *  ongoing emergency while the phone keeps alarming. */
        const val WRIST_ALERT_REBUZZ_MS = AlertFloor.FLOOR_COOLDOWN_MS

        /** Max history records per type sent to watch. Prevents exceeding DataItem size limit. */
        const val MAX_HISTORY_RECORDS = 500

        /** Explicit mapping to avoid ordinal-dependence on PumpActivityMode enum order. */
        fun activityModeToInt(mode: PumpActivityMode): Int = when (mode) {
            PumpActivityMode.NONE -> 0
            PumpActivityMode.SLEEP -> 1
            PumpActivityMode.EXERCISE -> 2
        }

        fun alertLabel(type: String): String = when (type) {
            "urgent_low" -> "URGENT LOW"
            "urgent_high" -> "URGENT HIGH"
            "low" -> "LOW"
            "high" -> "HIGH"
            else -> ""
        }

        /**
         * [AlertFloor] classifies in the server's AlertType vocabulary (shared notification slot
         * + channel routing); the watch wire protocol predates it and keeps its own strings.
         * Wrist alert paths after GLY-116: this relay (gated on
         * [AlertFloor.isReadingAlertable], same bound as the floor) and the bridged SERVER
         * notification (different glucose source — it must stay bridged, see
         * [AlertNotificationManager]); the floor notification is local-only so a single fresh
         * low is one wrist experience, not three.
         */
        val SERVER_TO_WATCH_ALERT_TYPE = mapOf(
            AlertTypes.LOW_URGENT to "urgent_low",
            AlertTypes.LOW_WARNING to "low",
            AlertTypes.HIGH_WARNING to "high",
            AlertTypes.HIGH_URGENT to "urgent_high",
        )
    }

    private suspend fun pollBattery() {
        pumpDriver.getBatteryStatus()
            .onSuccess {
                repository.saveBattery(it)
                syncEnqueuer.enqueueBattery(it)
                backendSyncManager?.triggerSync()
            }
            .onFailure { Timber.w(it, "Failed to poll battery") }
    }

    private suspend fun pollReservoir() {
        pumpDriver.getReservoirLevel()
            .onSuccess {
                repository.saveReservoir(it)
                syncEnqueuer.enqueueReservoir(it)
                backendSyncManager?.triggerSync()
            }
            .onFailure { Timber.w(it, "Failed to poll reservoir") }
    }

    /**
     * Fetch history logs from the pump and backfill CGM, bolus, and basal data.
     *
     * Loops until fully caught up (no more records to fetch) rather than stopping
     * after a single 200-record batch. This ensures gaps are filled completely on
     * reconnect instead of taking 5 minutes per 200 records. Each batch is capped
     * at 200 records / 15 seconds by the BLE driver to keep the connection alive.
     *
     * On fresh installs (lastSequenceNumber == 0), performs a full initial sync
     * with extended duration (10 minutes) and requests the full pump history
     * from the BLE driver (no lookback limit, larger batch caps).
     */
    private suspend fun pollHistoryLogs() {
        // Retry a resume anchor the connection watcher could not read. Deliberately unguarded: a
        // still-broken DB throws into [runStep], which reports it as (slow, history_logs) and
        // retries next cycle — far better than backfilling from a bogus anchor of 0, which would
        // re-download the pump's entire history behind the user's back.
        if (!sequenceAnchorRestored) readSequenceAnchor()

        val limits = safetyLimitsStore.toSafetyLimits()
        if (safetyLimitsStore.isStale()) {
            Timber.w("Safety limits are stale (>%d ms old), using cached values", SafetyLimitsStore.STALE_THRESHOLD_MS)
        }

        val isInitialSync = lastSequenceNumber == 0
        val deadline = if (isInitialSync) MAX_INITIAL_SYNC_DURATION_MS else MAX_BACKFILL_DURATION_MS
        if (isInitialSync) {
            Timber.i("Initial pump history sync starting (no prior data, full download)")
        }

        var totalRecords = 0
        var totalCgm = 0
        var totalBolus = 0
        var totalBasal = 0
        var batchCount = 0
        val startNanos = System.nanoTime()

        while ((System.nanoTime() - startNanos) / 1_000_000 < deadline) {
            val result = if (isInitialSync) {
                pumpDriver.getFullHistoryLogs(sinceSequence = lastSequenceNumber)
            } else {
                pumpDriver.getHistoryLogs(sinceSequence = lastSequenceNumber)
            }

            if (result.isFailure) {
                Timber.w(result.exceptionOrNull(), "Failed to poll history logs (batch %d)", batchCount)
                break
            }
            val records = result.getOrNull() ?: break

            if (records.isEmpty()) {
                if (batchCount > 0) {
                    if (isInitialSync) {
                        Timber.i("Initial pump history sync complete")
                    }
                    Timber.d("History backfill complete: %d records (%d CGM, %d bolus, %d basal) in %d batches",
                        totalRecords, totalCgm, totalBolus, totalBasal, batchCount)
                }
                break
            }

            batchCount++
            totalRecords += records.size

            // Persist raw history log records
            val entities = records.map { record ->
                RawHistoryLogEntity(
                    sequenceNumber = record.sequenceNumber,
                    rawBytesB64 = record.rawBytesB64,
                    eventTypeId = record.eventTypeId,
                    pumpTimeSeconds = record.pumpTimeSeconds,
                )
            }
            rawHistoryLogDao.insertAll(entities)
            val newMaxSeq = records.maxOfOrNull { it.sequenceNumber } ?: lastSequenceNumber
            if (newMaxSeq <= lastSequenceNumber) {
                Timber.w("History sequence not advancing (batch %d, stuck at %d), breaking", batchCount, lastSequenceNumber)
                break
            }
            lastSequenceNumber = newMaxSeq
            Timber.d(
                "Fetched batch %d: %d history records, %d total so far (seq up to %d)",
                batchCount, records.size, totalRecords, lastSequenceNumber,
            )

            // Extract and save CGM readings to fill chart gaps
            val cgmReadings = historyLogParser.extractCgmFromHistoryLogs(records, limits)
            if (cgmReadings.isNotEmpty()) {
                repository.saveCgmBatch(cgmReadings)
                totalCgm += cgmReadings.size
            }

            // Extract and save bolus events
            val bolusEvents = historyLogParser.extractBolusesFromHistoryLogs(records, limits)
            if (bolusEvents.isNotEmpty()) {
                repository.saveBoluses(bolusEvents)
                syncEnqueuer.enqueueBoluses(bolusEvents)
                totalBolus += bolusEvents.size
            }

            // Extract and save basal delivery events
            val basalReadings = historyLogParser.extractBasalFromHistoryLogs(records, limits)
            if (basalReadings.isNotEmpty()) {
                repository.saveBasalBatch(basalReadings)
                syncEnqueuer.enqueueBasalBatch(basalReadings)
                totalBasal += basalReadings.size
            }

            // Trigger backend sync after each batch so data is uploaded incrementally
            backendSyncManager?.triggerSync()

            // Brief pause between batches to let other BLE operations through
            delay(BACKFILL_BATCH_STAGGER_MS)
        }
    }

    /** Guarded by [runStep] (which reports the failure against the slow loop and moves on), so
     *  this no longer carries its own catch. */
    private suspend fun sendWatchHistoryOverlays() {
        val sixHoursAgo = Instant.now().minus(6, ChronoUnit.HOURS)

        // Cap records per type to stay well under the 100KB DataItem limit.
        // 500 basal * 13B = 6.5KB, 500 bolus * 21B = 10.5KB, 500 IoB * 12B = 6KB
        val basalReadings = repository.getBasalSince(sixHoursAgo).takeLast(MAX_HISTORY_RECORDS)
        if (basalReadings.isNotEmpty()) {
            val records = basalReadings.map { r ->
                WearHistorySerializer.BasalRecord(
                    rate = r.rate,
                    timestampMs = r.timestamp.toEpochMilli(),
                    isAutomated = r.isAutomated,
                    activityMode = activityModeToInt(r.activityMode),
                )
            }
            val data = WearHistorySerializer.encodeBasalHistory(records)
            wearDataSender.sendBasalHistory(data, records.size)
        }

        val bolusEvents = repository.getBolusesSince(sixHoursAgo).takeLast(MAX_HISTORY_RECORDS)
        if (bolusEvents.isNotEmpty()) {
            val records = bolusEvents.map { e ->
                WearHistorySerializer.BolusRecord(
                    units = e.units,
                    correctionUnits = e.correctionUnits,
                    mealUnits = e.mealUnits,
                    timestampMs = e.timestamp.toEpochMilli(),
                    isAutomated = e.isAutomated,
                    isCorrection = e.isCorrection,
                )
            }
            val data = WearHistorySerializer.encodeBolusHistory(records)
            wearDataSender.sendBolusHistory(data, records.size)
        }

        val iobReadings = repository.getIoBSince(sixHoursAgo).takeLast(MAX_HISTORY_RECORDS)
        if (iobReadings.isNotEmpty()) {
            val records = iobReadings.map { r ->
                WearHistorySerializer.IoBRecord(
                    iob = r.iob,
                    timestampMs = r.timestamp.toEpochMilli(),
                )
            }
            val data = WearHistorySerializer.encodeIoBHistory(records)
            wearDataSender.sendIoBHistory(data, records.size)
        }

        Timber.d(
            "Sent watch history overlays: %d basal, %d bolus, %d IoB",
            basalReadings.size, bolusEvents.size, iobReadings.size,
        )
    }

    private suspend fun cacheHardwareInfoOnce() {
        if (hardwareInfoCached) return
        pumpDriver.getPumpHardwareInfo()
            .onSuccess { info ->
                backendSyncManager?.cachedHardwareInfo = info
                hardwareInfoCached = true
                Timber.d("Cached pump hardware info: serial=%d", info.serialNumber)
            }
            .onFailure { Timber.w(it, "Failed to get pump hardware info") }
    }
}
