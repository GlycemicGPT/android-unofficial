// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.wear

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.glycemicgpt.mobile.service.FgsTimeoutReporter
import com.glycemicgpt.mobile.service.ForegroundServiceStartResult
import com.glycemicgpt.mobile.service.ForegroundServiceStarter
import com.glycemicgpt.mobile.service.ForegroundStartRejectionReason
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The chat relay shares the app's `dataSync` budget with the alert stream, so Android 15 can
 * time it out too (GLY-244). It must stop the same way -- without throwing, and without
 * persisting a resume it has no way to honour.
 *
 * Built but never `create()`d, for the reason spelled out in
 * [com.glycemicgpt.mobile.service.AlertStreamServiceTimeoutTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WearChatRelayServiceTimeoutTest {

    private lateinit var service: WearChatRelayService
    private lateinit var reporter: FgsTimeoutReporter

    @Before
    fun setUp() {
        reporter = FgsTimeoutReporter(ApplicationProvider.getApplicationContext())
        service = Robolectric.buildService(WearChatRelayService::class.java).get().apply {
            fgsTimeoutReporter = reporter
        }
    }

    @After
    fun tearDown() {
        unmockkObject(ForegroundServiceStarter)
    }

    @Test
    fun `startWork survives a rejected promotion and relays unprotected`() {
        // GLY-246 review F5: unlike the pump and alert-stream services, the relay's own trade
        // (see startWork's Rejected branch) is to keep going without foreground protection rather
        // than stop -- a watch chat message is not worth crashing the process over. This pins
        // that a rejection neither stops the service nor blocks the work item from being counted.
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } returns ForegroundServiceStartResult.Rejected(
            FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY,
            "IllegalStateException",
            ForegroundStartRejectionReason.BUDGET_EXHAUSTED,
        )

        service.startWork()

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, service.activeWorkCount.get())
    }

    @Test
    fun `a start command promotes and demotes so the platform gets its startForeground`() {
        // The service is exported and declares foregroundServiceType="dataSync", so any
        // startForegroundService aimed at it puts the process on the platform's stopwatch. Only a
        // startForeground call takes it off: a start command that promotes nothing dies at ~30s
        // with ForegroundServiceDidNotStartInTimeException, and one that answers with a bare
        // stopSelf dies in ~1.5s -- both reproduced on an Android 16 emulator. Watch messages
        // arrive over the GMS binding, so there is no work to hold the promotion for; it is taken
        // and dropped in the same call.
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } returns ForegroundServiceStartResult.Started

        val returned = service.onStartCommand(Intent(), 0, START_ID)

        verify(exactly = 1) {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        }
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, returned)
        // The counter is left where it was found, so the next real chat request still promotes.
        assertEquals(0, service.activeWorkCount.get())
    }

    @Test
    fun `a start command landing mid-request leaves the in-flight relay promoted`() {
        // GMS keeps the service bound while a chat request is being handled, so stopSelf does not
        // destroy it -- but a start command that demoted unconditionally would drop the foreground
        // state out from under the request. The work counter is what prevents that: the demotion
        // is gated on it, so this start leaves the relay promoted with its counter untouched.
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } returns ForegroundServiceStartResult.Started
        service.startWork()

        service.onStartCommand(Intent(), 0, START_ID)

        assertEquals(1, service.activeWorkCount.get())
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `a start command promotes even when a request is already counted`() {
        // The hole a counter-gated promotion leaves. activeWorkCount counts work items, not
        // foreground state, and a refused promotion pulls the two apart: the request below is
        // counted but the service never reached the foreground. If the start command trusted the
        // counter it would skip startForeground entirely and the platform would kill the process
        // ~30s later, which is the exact crash this override exists to prevent. Promoting
        // unconditionally is free -- startForeground on an already-foreground service refreshes
        // the same notification id.
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } returns ForegroundServiceStartResult.Rejected(
            FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY,
            "IllegalStateException",
            ForegroundStartRejectionReason.BUDGET_EXHAUSTED,
        )
        service.startWork()

        service.onStartCommand(Intent(), 0, START_ID)

        verify(exactly = 2) {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        }
        // Still no demotion out from under the request that is running unprotected.
        assertEquals(1, service.activeWorkCount.get())
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `a work item on another thread cannot interleave with a start command`() {
        // Watch messages arrive on GMS binder threads; start commands arrive on the main thread.
        // Both mutate activeWorkCount and then act on the foreground state, and the counter being
        // an AtomicInteger does not make the pair atomic: a startWork landing between a decrement
        // and its clamp is promoted and then immediately demoted by the finishing caller, leaving
        // a 20-60s chat request with no foreground protection. The lock is what makes each
        // counter mutation and the foreground transition it decides one step.
        val insidePromotion = CountDownLatch(1)
        val releasePromotion = CountDownLatch(1)
        val workStarted = CountDownLatch(1)
        val promotions = AtomicInteger(0)
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } answers {
            // Only the FIRST promotion blocks, which is the start command's. `startWork` promotes
            // too when it takes the counter from zero, so a mock that blocked every call would
            // park an unlocked watch-message thread in here instead of letting it reach
            // `workStarted` -- and the negative assertion below would pass with the lock removed,
            // which is the one thing it exists to rule out.
            if (promotions.getAndIncrement() == 0) {
                insidePromotion.countDown()
                releasePromotion.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            ForegroundServiceStartResult.Started
        }

        val startCommand = Thread { service.onStartCommand(Intent(), 0, START_ID) }
        startCommand.start()
        assertTrue(
            "the start command never reached its promotion",
            insidePromotion.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        // Counted down on the watch-message thread immediately before it calls startWork, so the
        // negative assertion below is waiting on a thread that has actually reached the contended
        // call. Without it, a thread still queued by the scheduler would satisfy the assertion
        // without ever contending for anything, and the test would keep passing if the lock were
        // removed outright.
        val watchEntering = CountDownLatch(1)
        val watchMessage = Thread {
            watchEntering.countDown()
            service.startWork()
            workStarted.countDown()
        }
        watchMessage.start()
        assertTrue(
            "the watch message thread never started",
            watchEntering.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        assertFalse(
            "a watch message mutated the work counter while the start command was mid-promotion",
            workStarted.await(CONTENDED_WAIT_MILLIS, TimeUnit.MILLISECONDS),
        )
        assertEquals(0, service.activeWorkCount.get())

        releasePromotion.countDown()
        startCommand.join(JOIN_TIMEOUT_MILLIS)
        watchMessage.join(JOIN_TIMEOUT_MILLIS)
        // join() with a timeout returns silently when it expires, so the joins above prove nothing
        // on their own: a thread still parked on the lock would sail through to the assertions.
        assertFalse("the start command never finished", startCommand.isAlive)
        assertFalse("the watch message never finished", watchMessage.isAlive)

        assertTrue(
            "the watch message never got the lock back",
            workStarted.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        assertEquals(1, service.activeWorkCount.get())
    }

    @Test
    fun `onTimeout stops the service and drops the foreground state`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `onTimeout records the event but owes no resume`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertEquals(1, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            reporter.lastFgsType(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY),
        )
        // A chat request is request-scoped; the watch surfaces its own timeout and the user
        // re-asks. Claiming a resume is owed would strand a marker nothing ever clears.
        assertFalse(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
    }

    @Test
    fun `timing out the relay does not mark the alert stream`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertEquals(0, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        assertFalse(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
    }

    @Test
    fun `the relay stays usable after a timeout`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        // GMS keeps a WearableListenerService bound, so this instance can receive the next watch
        // message, and every reply the relay sends goes through serviceScope. onTimeout cancels
        // the scope's children rather than the scope itself -- swap in serviceScope.cancel() and
        // this launch never runs, leaving the relay permanently unable to answer the watch.
        val ranAfterTimeout = CountDownLatch(1)
        service.serviceScope.launch { ranAfterTimeout.countDown() }

        assertTrue(
            "work launched after a timeout never ran; onTimeout killed serviceScope",
            ranAfterTimeout.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        // And a redelivered timeout on the same instance is still handled.
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertEquals(2, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
    }

    private companion object {
        const val START_ID = 3
        const val LATCH_TIMEOUT_SECONDS = 5L

        /** How long a blocked-on-the-lock thread is given to prove it is actually blocked. */
        const val CONTENDED_WAIT_MILLIS = 500L
        const val JOIN_TIMEOUT_MILLIS = 5_000L
    }
}
