// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.glycemicgpt.mobile.data.remote.SimulateUnreachableInterceptor
import io.mockk.mockk
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
import kotlin.system.measureTimeMillis

/**
 * [AlertStreamService.onTimeout] is the path Android 15 takes when the app's 6 h-per-24 h
 * `dataSync` budget runs out (GLY-244). Miss the few-second window it gives and the system
 * throws `RemoteServiceException` and kills the process, taking `PumpConnectionService` and the
 * pump connection with it -- so what these tests pin is that the callback stops the service,
 * does it without throwing, and leaves an honest degraded state behind.
 *
 * The service is built but never `create()`d: `@AndroidEntryPoint` injection runs in `onCreate`
 * and needs a Hilt test application, while `onTimeout` only touches the two collaborators
 * assigned below. That mirrors [com.glycemicgpt.mobile.service.AlertActionReceiverTest], which
 * assigns injected fields directly rather than standing up a graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlertStreamServiceTimeoutTest {

    private lateinit var service: AlertStreamService
    private lateinit var stateHolder: AlertStreamStateHolder
    private lateinit var reporter: FgsTimeoutReporter

    @Before
    fun setUp() {
        stateHolder = AlertStreamStateHolder()
        reporter = FgsTimeoutReporter(ApplicationProvider.getApplicationContext())
        service = Robolectric.buildService(AlertStreamService::class.java).get().apply {
            alertStreamStateHolder = stateHolder
            fgsTimeoutReporter = reporter
            // Only needed by the lazy OkHttp client the onDestroy test forces into existence;
            // it is never asked to intercept anything here.
            simulateUnreachableInterceptor = mockk(relaxed = true)
        }
    }

    @Test
    fun `onTimeout stops the service itself`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(
            "onTimeout must call stopSelf() or the system kills the process",
            shadowOf(service).isStoppedBySelf,
        )
    }

    @Test
    fun `onTimeout drops the foreground state`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `onTimeout reports the stream as no longer connected`() {
        stateHolder.onStreamOpened()

        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        // DISCONNECTED is what arms the on-device alert floor: the user is told coverage
        // degraded rather than silently losing it.
        assertEquals(AlertStreamState.DISCONNECTED, stateHolder.state.value)
    }

    @Test
    fun `onTimeout records a timeout event with a resume owed`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertEquals(1, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            reporter.lastFgsType(FgsTimeoutReporter.COMPONENT_ALERT_STREAM),
        )
        assertTrue(
            "the stream is durable work; GLY-245 needs to know a resume is owed",
            reporter.isResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM),
        )
        assertTrue(reporter.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_ALERT_STREAM) > 0L)
    }

    @Test
    fun `timing out the alert stream does not mark any other service`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertEquals(0, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
        assertFalse(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
    }

    @Test
    fun `a redelivered onTimeout is harmless`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(AlertStreamState.DISCONNECTED, stateHolder.state.value)
    }

    @Test
    fun `onDestroy after a timeout completes without the dispatcher drain`() {
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        // The normal stop path waits up to 3s for the OkHttp dispatcher; on the timeout path
        // that wait would spend the grace window on the main thread. Anything close to 3s here
        // means the skip regressed.
        val elapsedMs = measureTimeMillis { service.onDestroy() }

        assertTrue(
            "onDestroy took ${elapsedMs}ms after a timeout; the dispatcher drain must be skipped",
            elapsedMs < 1_000L,
        )
        assertEquals(AlertStreamState.DISCONNECTED, stateHolder.state.value)
    }

    private companion object {
        const val START_ID = 7
    }
}
