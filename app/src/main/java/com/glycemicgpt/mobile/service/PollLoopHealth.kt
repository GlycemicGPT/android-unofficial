// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three independently scheduled poll loops in [PumpPollingOrchestrator]. Each runs in its own
 * job under the service's `SupervisorJob`, so they fail — and recover — independently. The
 * [telemetryName] is the stable key failures are reported under, so a log/Sentry search can tell
 * "the slow loop is dead" from "the fast loop is dead".
 */
enum class PollLoop(val telemetryName: String) {
    FAST("fast"),
    MEDIUM("medium"),
    SLOW("slow"),
    ;

    /**
     * Fault-injection key for making the loop body itself throw, OUTSIDE the per-step guard, so
     * the supervising restart can be exercised. Distinct from the per-step keys in [PollStep].
     */
    val loopFaultKey: String get() = "${telemetryName}_loop"
}

/**
 * One guarded unit of work inside a poll loop. Every step runs behind its own try/catch so a
 * single bad row, parse, or sync enqueue costs that step's data for one iteration instead of
 * killing the loop for the rest of the service's life.
 *
 * [telemetryName] is the second half of the failure coordinate (loop + step) and doubles as the
 * debug-build fault-injection key.
 */
enum class PollStep(val loop: PollLoop, val telemetryName: String) {
    IOB(PollLoop.FAST, "iob"),
    BASAL(PollLoop.FAST, "basal"),
    CGM(PollLoop.FAST, "cgm"),
    BOLUS_HISTORY(PollLoop.MEDIUM, "bolus_history"),
    BATTERY(PollLoop.SLOW, "battery"),
    RESERVOIR(PollLoop.SLOW, "reservoir"),
    HISTORY_LOGS(PollLoop.SLOW, "history_logs"),
    HARDWARE_INFO(PollLoop.SLOW, "hardware_info"),
    WATCH_HISTORY(PollLoop.SLOW, "watch_history"),
}

/**
 * Liveness and failure snapshot for one loop, scoped to the CURRENT polling session: [markRunning]
 * resets it, because a heartbeat carried over from the previous connection would let a watchdog
 * call a freshly relaunched loop healthy before it has completed anything.
 *
 * @param lastSuccessAtMs wall-clock ms of the last iteration in which every step completed without
 *   throwing — the heartbeat a watchdog judges liveness by. Null until the first such iteration;
 *   compare against [startedAtMs] to tell "never got going" from "stopped getting going".
 * @param successCount completed clean iterations this session.
 * @param lastFailureStep the step whose failure is described by [lastFailureAtMs] /
 *   [lastFailureMessage]; null when the failure came from the loop body outside any step.
 * @param failuresSinceLastSuccess failures recorded since the heartbeat last advanced — zero on a
 *   healthy loop, and the length of the current outage on a broken one. Reset by
 *   [PollLoopHealthTracker.recordIterationSuccess], which is what lets the orchestrator report the
 *   recovering edge of an outage and not just its start.
 * @param failureKindsThisOutage the distinct faults seen since the heartbeat last advanced, each
 *   keyed as `step/ExceptionClass` (or [PollLoopHealth.LOOP_BODY] for a failure outside any step).
 *   [failuresSinceLastSuccess] counts how long the outage is; this says how many different things
 *   are wrong, which is what keeps a new fault from being damped into silence by an old one.
 *   Cleared alongside the counter on recovery.
 * @param restartCount how many times the supervisor has relaunched this loop this session. A
 *   non-zero value means something threw where nothing should have.
 * @param sessionId identifies the loop job these numbers belong to. A cancelled job runs its
 *   teardown whenever it is next scheduled, which can be after its replacement has already
 *   started; the id lets the late teardown recognise that it is no longer the current session and
 *   leave the new one alone. [NO_SESSION] before the first start.
 */
data class PollLoopHealth(
    val loop: PollLoop,
    val running: Boolean = false,
    val sessionId: Long = NO_SESSION,
    val startedAtMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    val successCount: Long = 0,
    val lastFailureAtMs: Long? = null,
    val lastFailureStep: PollStep? = null,
    val lastFailureMessage: String? = null,
    val failureCount: Long = 0,
    val failuresSinceLastSuccess: Long = 0,
    val failureKindsThisOutage: Set<String> = emptySet(),
    val restartCount: Long = 0,
) {

    /**
     * Whether the failure recorded immediately AFTER this snapshot is worth an ERROR — which
     * `SentryInitializer` forwards as a Sentry event — or is a repeat that belongs at DEBUG.
     * [failureKind] identifies the fault being recorded — see [failureKindsThisOutage].
     *
     * A loop now keeps iterating through a failing step by design, so a durable fault (a SQLCipher
     * write that never succeeds, a parser that rejects every record) would otherwise emit one event
     * per iteration: four a minute on the fast loop, for as long as the pump stays connected. That
     * exhausts the event quota, costs battery and network, and buries unrelated errors.
     *
     * Two things are worth an event. **A fault not yet seen in this outage** — a different step, or
     * the same step throwing a different exception class — because the first sighting of a fault is
     * the whole point of the report, and a loop that has been failing for hours must not swallow a
     * new one. And **the ladder**: failures 1, 2, 4, 8, 16... of the outage, so a long outage stays
     * visible without growing linearly with its length. A day-long fast-loop outage costs ~13
     * events instead of ~5,700, and the doubling needs no per-loop tuning because it follows each
     * loop's own cadence.
     *
     * Everything else is logged on-device at DEBUG with the throwable attached. Damping costs
     * nothing remotely either: every report that does ship carries `since_ok=` from
     * [telemetrySummary], so an event says how long the outage has run, not just that it exists.
     */
    fun opensFailureReport(failureKind: String): Boolean {
        if (failureKind !in failureKindsThisOutage) return true
        val ordinal = failuresSinceLastSuccess + 1
        return (ordinal and (ordinal - 1)) == 0L
    }

    /**
     * Compact liveness line for telemetry. Attached to every failure and restart report so a
     * Sentry event answers "how long has this loop been without a clean iteration" — the heartbeat
     * only means something next to the failure that froze it. Counters and durations only: no
     * reading values, nothing that could carry PHI through the log pipeline.
     */
    fun telemetrySummary(nowMs: Long = System.currentTimeMillis()): String = buildString {
        append("last_ok=")
        append(lastSuccessAtMs?.let { "${nowMs - it}ms_ago" } ?: "never")
        append(" ok=").append(successCount)
        append(" fail=").append(failureCount)
        append(" since_ok=").append(failuresSinceLastSuccess)
        append(" restarts=").append(restartCount)
    }

    companion object {
        const val NO_SESSION = 0L

        /** Stands in for the step in a [failureKindsThisOutage] key when the loop body itself threw. */
        const val LOOP_BODY = "loop_body"

        /**
         * The key a failure is deduplicated under while an outage lasts: which step, and what it
         * threw. The exception class is part of it because the same step failing a new way is a new
         * fault worth an event — a step that starts throwing `SQLiteFullException` where it used to
         * throw `ParseException` is not the outage anyone is already looking at. Class only, never
         * the message: these keys ride into a Sentry event, and a parser or Room message can quote
         * a reading.
         */
        fun failureKind(step: PollStep?, error: Throwable?): String =
            "${step?.telemetryName ?: LOOP_BODY}/${error?.javaClass?.simpleName ?: "none"}"
    }
}

/**
 * Shared liveness registry for the poll loops (GLY-249).
 *
 * The orchestrator writes; the debug console and the polling watchdog (GLY-254) read. It is a
 * separate singleton rather than state on the orchestrator so a watchdog can observe liveness
 * without holding — or being able to disturb — the thing it is watching.
 *
 * Non-suspending and lock-free: every mutation is a [MutableStateFlow.update]-style compare-and-set
 * on an immutable map, so it is safe to call from a coroutine that is already being cancelled (the
 * supervisor's `finally` does exactly that).
 */
@Singleton
class PollLoopHealthTracker @Inject constructor() {

    private val _health = MutableStateFlow(
        PollLoop.entries.associateWith { PollLoopHealth(it) },
    )

    private val sessionCounter = AtomicLong(PollLoopHealth.NO_SESSION)

    /** Live per-loop health, keyed by loop. Always contains an entry for every [PollLoop]. */
    val health: StateFlow<Map<PollLoop, PollLoopHealth>> = _health.asStateFlow()

    fun snapshot(loop: PollLoop): PollLoopHealth = _health.value.getValue(loop)

    /**
     * A fresh loop job has started. Resets the session's liveness and failure history — carrying
     * the previous connection's heartbeat over would let a watchdog call a loop healthy before it
     * has completed anything.
     *
     * Returns the new session id to hand back to [markStopped].
     */
    fun markRunning(loop: PollLoop, nowMs: Long = System.currentTimeMillis()): Long {
        val sessionId = sessionCounter.incrementAndGet()
        set(loop, PollLoopHealth(loop, running = true, sessionId = sessionId, startedAtMs = nowMs))
        return sessionId
    }

    /**
     * The loop job has ended (disconnect, service teardown). Timestamps are kept for diagnosis;
     * `running = false` tells a watchdog the stale heartbeat is expected, not a fault.
     *
     * Ignored unless [sessionId] is still the current session: a job cancelled during a reconnect
     * may not run its teardown until after its replacement has started, and it must not report
     * the live loop as stopped.
     */
    fun markStopped(loop: PollLoop, sessionId: Long) {
        update(loop) { if (it.sessionId == sessionId) it.copy(running = false) else it }
    }

    /**
     * One iteration completed with every step succeeding — the heartbeat.
     *
     * Returns the health as it was JUST BEFORE this success, so the caller can tell a routine
     * iteration from the end of an outage (`failuresSinceLastSuccess > 0`) and report the recovery
     * without a second read racing the next iteration.
     */
    fun recordIterationSuccess(
        loop: PollLoop,
        nowMs: Long = System.currentTimeMillis(),
    ): PollLoopHealth = update(loop) {
        it.copy(
            lastSuccessAtMs = nowMs,
            successCount = it.successCount + 1,
            failuresSinceLastSuccess = 0,
            failureKindsThisOutage = emptySet(),
        )
    }

    /**
     * A guarded step threw. The loop continues; the heartbeat deliberately does not advance for
     * this iteration, so a step that fails forever surfaces as a frozen heartbeat.
     *
     * Returns the health as it was JUST BEFORE this failure, so the caller can tell the opening
     * edge of an outage from a repeat ([PollLoopHealth.opensFailureReport]) without a second read
     * racing the next iteration.
     */
    fun recordStepFailure(
        step: PollStep,
        error: Throwable,
        nowMs: Long = System.currentTimeMillis(),
    ): PollLoopHealth = update(step.loop) {
        it.copy(
            lastFailureAtMs = nowMs,
            lastFailureStep = step,
            lastFailureMessage = error.toString(),
            failureCount = it.failureCount + 1,
            failuresSinceLastSuccess = it.failuresSinceLastSuccess + 1,
            failureKindsThisOutage = it.failureKindsThisOutage +
                PollLoopHealth.failureKind(step, error),
        )
    }

    /**
     * The loop body threw outside any guarded step and the supervisor is relaunching it.
     *
     * Returns the pre-failure health, on the same terms as [recordStepFailure]: a body that throws
     * forever restarts on backoff indefinitely, and its report needs the same damping.
     */
    fun recordLoopRestart(
        loop: PollLoop,
        error: Throwable?,
        nowMs: Long = System.currentTimeMillis(),
    ): PollLoopHealth = update(loop) {
        it.copy(
            lastFailureAtMs = nowMs,
            lastFailureStep = null,
            lastFailureMessage = error?.toString() ?: "loop body returned unexpectedly",
            failureCount = it.failureCount + 1,
            failuresSinceLastSuccess = it.failuresSinceLastSuccess + 1,
            failureKindsThisOutage = it.failureKindsThisOutage +
                PollLoopHealth.failureKind(step = null, error = error),
            restartCount = it.restartCount + 1,
        )
    }

    /** Applies [transform] to one loop's entry and returns the value it replaced. */
    private fun update(
        loop: PollLoop,
        transform: (PollLoopHealth) -> PollLoopHealth,
    ): PollLoopHealth {
        while (true) {
            val current = _health.value
            val previous = current.getValue(loop)
            val next = current + (loop to transform(previous))
            if (_health.compareAndSet(current, next)) return previous
        }
    }

    private fun set(loop: PollLoop, value: PollLoopHealth) {
        while (true) {
            val current = _health.value
            if (_health.compareAndSet(current, current + (loop to value))) return
        }
    }
}
