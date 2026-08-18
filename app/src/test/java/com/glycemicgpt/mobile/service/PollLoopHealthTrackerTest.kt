// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The liveness contract the polling watchdog (GLY-254) and the debug console read. The
 * orchestrator's own tests drive this through real loops; these pin the state transitions that
 * are hard to force from a virtual-time schedule.
 */
class PollLoopHealthTrackerTest {

    private val tracker = PollLoopHealthTracker()

    @Test
    fun `starts with no heartbeat and nothing running`() {
        PollLoop.entries.forEach { loop ->
            val health = tracker.snapshot(loop)
            assertFalse(health.running)
            assertNull(health.lastSuccessAtMs)
            assertEquals(0L, health.successCount)
        }
    }

    @Test
    fun `a late teardown from a replaced loop job does not mark the live loop stopped`() {
        // Reconnect: the outgoing job is cancelled and the replacement starts before the
        // outgoing job is scheduled again to run its teardown.
        val replaced = tracker.markRunning(PollLoop.FAST, nowMs = 1_000L)
        val current = tracker.markRunning(PollLoop.FAST, nowMs = 2_000L)
        tracker.recordIterationSuccess(PollLoop.FAST, nowMs = 2_500L)

        tracker.markStopped(PollLoop.FAST, replaced)

        val health = tracker.snapshot(PollLoop.FAST)
        assertTrue("the live loop must still report running", health.running)
        assertEquals(2_500L, health.lastSuccessAtMs)

        // The loop that is actually current can still stop itself.
        tracker.markStopped(PollLoop.FAST, current)
        assertFalse(tracker.snapshot(PollLoop.FAST).running)
    }

    @Test
    fun `a new session resets liveness so a fresh loop cannot inherit a heartbeat`() {
        tracker.markRunning(PollLoop.SLOW, nowMs = 1_000L)
        tracker.recordIterationSuccess(PollLoop.SLOW, nowMs = 1_500L)
        tracker.recordStepFailure(PollStep.BATTERY, RuntimeException("boom"), nowMs = 1_600L)

        tracker.markRunning(PollLoop.SLOW, nowMs = 9_000L)

        val health = tracker.snapshot(PollLoop.SLOW)
        assertNull(health.lastSuccessAtMs)
        assertNull(health.lastFailureStep)
        assertEquals(0L, health.successCount)
        assertEquals(0L, health.failureCount)
        assertEquals(9_000L, health.startedAtMs)
    }

    @Test
    fun `failures are attributed to the loop the step belongs to`() {
        PollLoop.entries.forEach { tracker.markRunning(it, nowMs = 1_000L) }

        tracker.recordStepFailure(PollStep.HISTORY_LOGS, IllegalStateException("bad row"), 2_000L)

        val slow = tracker.snapshot(PollLoop.SLOW)
        assertEquals(PollStep.HISTORY_LOGS, slow.lastFailureStep)
        assertEquals(2_000L, slow.lastFailureAtMs)
        assertEquals(1L, slow.failureCount)
        assertTrue(slow.lastFailureMessage!!.contains("bad row"))
        // A failure in one loop says nothing about the others.
        assertEquals(0L, tracker.snapshot(PollLoop.FAST).failureCount)
        assertEquals(0L, tracker.snapshot(PollLoop.MEDIUM).failureCount)
    }

    @Test
    fun `a loop restart is recorded without a step and keeps the previous heartbeat`() {
        tracker.markRunning(PollLoop.MEDIUM, nowMs = 1_000L)
        tracker.recordIterationSuccess(PollLoop.MEDIUM, nowMs = 1_500L)

        tracker.recordLoopRestart(PollLoop.MEDIUM, RuntimeException("escaped"), nowMs = 2_000L)

        val health = tracker.snapshot(PollLoop.MEDIUM)
        assertEquals(1L, health.restartCount)
        assertNull("a loop-level failure has no step", health.lastFailureStep)
        // The heartbeat is history, not a claim about now: it keeps ageing until the relaunched
        // loop completes an iteration of its own.
        assertEquals(1_500L, health.lastSuccessAtMs)
    }

    @Test
    fun `the outage counter tracks the current outage and clears on recovery`() {
        tracker.markRunning(PollLoop.SLOW, nowMs = 1_000L)
        tracker.recordIterationSuccess(PollLoop.SLOW, nowMs = 1_500L)
        tracker.recordStepFailure(PollStep.BATTERY, RuntimeException("boom"), nowMs = 2_000L)
        tracker.recordStepFailure(PollStep.BATTERY, RuntimeException("boom"), nowMs = 2_500L)

        assertEquals(2L, tracker.snapshot(PollLoop.SLOW).failuresSinceLastSuccess)

        // The success returns the pre-success snapshot: that is how the orchestrator knows this
        // iteration ended an outage (and how long it ran) without racing the next one.
        val before = tracker.recordIterationSuccess(PollLoop.SLOW, nowMs = 3_000L)
        assertEquals(2L, before.failuresSinceLastSuccess)
        assertEquals(1_500L, before.lastSuccessAtMs)

        val recovered = tracker.snapshot(PollLoop.SLOW)
        assertEquals(0L, recovered.failuresSinceLastSuccess)
        // The lifetime count is untouched — only the current-outage view resets.
        assertEquals(2L, recovered.failureCount)
    }

    /** Records one CGM failure and answers whether the orchestrator would report it at ERROR. */
    private fun reportsCgmFailure(error: Throwable, nowMs: Long): Boolean {
        val before = tracker.recordStepFailure(PollStep.CGM, error, nowMs)
        return before.opensFailureReport(PollLoopHealth.failureKind(PollStep.CGM, error))
    }

    @Test
    fun `a repeating failure reports on the outage edge and then ladders off`() {
        tracker.markRunning(PollLoop.FAST, nowMs = 1_000L)

        // Failure ordinals 1, 2, 4, 8, 16 are reportable; everything between them is a repeat.
        val reported = (1..20).filter { ordinal ->
            reportsCgmFailure(RuntimeException("boom"), nowMs = 1_000L + ordinal)
        }
        assertEquals(listOf(1, 2, 4, 8, 16), reported)

        // A clean iteration ends the outage, so the next failure opens a fresh report — neither
        // the ladder nor the seen-faults set may carry over into the new outage.
        tracker.recordIterationSuccess(PollLoop.FAST, nowMs = 2_000L)
        assertEquals(emptySet<String>(), tracker.snapshot(PollLoop.FAST).failureKindsThisOutage)
        assertTrue(
            "a failure after a success opens a new report",
            reportsCgmFailure(RuntimeException("boom"), nowMs = 2_100L),
        )
    }

    @Test
    fun `a fault not yet seen in the outage reports even while an old one is damped`() {
        tracker.markRunning(PollLoop.SLOW, nowMs = 1_000L)
        // A durable fault, run out to ordinal 20 so the failures below land at 21-24 — past the
        // last ladder rung (16) and short of the next (32), so nothing here reports by coincidence.
        (1..20).forEach {
            tracker.recordStepFailure(PollStep.HISTORY_LOGS, RuntimeException("boom"), 1_000L + it)
        }
        val damped = tracker.recordStepFailure(PollStep.HISTORY_LOGS, RuntimeException("boom"), 2_000L)
        assertFalse(
            damped.opensFailureReport(
                PollLoopHealth.failureKind(PollStep.HISTORY_LOGS, RuntimeException("boom")),
            ),
        )

        // A second step starting to fail is news, and so is the first step failing a new way.
        val otherStep = tracker.recordStepFailure(PollStep.BATTERY, RuntimeException("boom"), 2_100L)
        assertTrue(
            otherStep.opensFailureReport(
                PollLoopHealth.failureKind(PollStep.BATTERY, RuntimeException("boom")),
            ),
        )
        val otherCause =
            tracker.recordStepFailure(PollStep.HISTORY_LOGS, IllegalStateException("bad row"), 2_200L)
        assertTrue(
            otherCause.opensFailureReport(
                PollLoopHealth.failureKind(PollStep.HISTORY_LOGS, IllegalStateException("bad row")),
            ),
        )

        // ...and a loop-body failure is its own kind, not one of the steps'.
        val body = tracker.recordLoopRestart(PollLoop.SLOW, RuntimeException("escaped"), 2_300L)
        assertTrue(body.opensFailureReport(PollLoopHealth.failureKind(null, RuntimeException("x"))))
        assertEquals(
            setOf(
                "history_logs/RuntimeException",
                "battery/RuntimeException",
                "history_logs/IllegalStateException",
                "loop_body/RuntimeException",
            ),
            tracker.snapshot(PollLoop.SLOW).failureKindsThisOutage,
        )
    }

    @Test
    fun `loop restarts share the step failures' report ladder`() {
        tracker.markRunning(PollLoop.MEDIUM, nowMs = 1_000L)

        val reported = (1..8).filter { ordinal ->
            val error = RuntimeException("escaped")
            tracker.recordLoopRestart(PollLoop.MEDIUM, error, nowMs = 1_000L + ordinal)
                .opensFailureReport(PollLoopHealth.failureKind(null, error))
        }
        assertEquals(listOf(1, 2, 4, 8), reported)
        assertEquals(8L, tracker.snapshot(PollLoop.MEDIUM).restartCount)
    }

    @Test
    fun `the telemetry summary reports liveness in counters only`() {
        tracker.markRunning(PollLoop.FAST, nowMs = 1_000L)
        assertTrue(tracker.snapshot(PollLoop.FAST).telemetrySummary(2_000L).contains("last_ok=never"))

        tracker.recordIterationSuccess(PollLoop.FAST, nowMs = 2_000L)
        tracker.recordStepFailure(PollStep.CGM, RuntimeException("boom"), nowMs = 3_000L)

        val summary = tracker.snapshot(PollLoop.FAST).telemetrySummary(nowMs = 5_000L)
        assertEquals("last_ok=3000ms_ago ok=1 fail=1 since_ok=1 restarts=0", summary)
    }

    @Test
    fun `every poll step maps to exactly one loop and has a unique telemetry key`() {
        val keys = PollStep.entries.map { it.telemetryName } + PollLoop.entries.map { it.loopFaultKey }
        assertEquals("fault-injection keys must be unambiguous", keys.size, keys.toSet().size)
        PollLoop.entries.forEach { loop ->
            assertTrue(
                "$loop should own at least one step",
                PollStep.entries.any { it.loop == loop },
            )
        }
    }
}
