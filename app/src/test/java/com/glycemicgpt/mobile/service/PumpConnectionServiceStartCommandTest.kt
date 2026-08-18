// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.glycemicgpt.mobile.domain.alerting.AlertFloorStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
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

/**
 * What [PumpConnectionService.onStartCommand] does with a [ForegroundServiceStartResult.Rejected]
 * from [ForegroundServiceStarter.promote] is where monitoring is won or lost (GLY-246 review F5):
 * a first rejected promotion must stop the service cleanly rather than run the BLE link
 * unprotected, but a rejected *redundant* re-promote (the service is already up and running)
 * must not tear down a healthy connection over it (GLY-246 review F6). Neither branch had a test
 * before this.
 *
 * [ForegroundServiceStarter] is mocked via `mockkObject` (established idiom:
 * `PairingViewModelTest`, `MealLogViewModelTest`) to force a [ForegroundServiceStartResult.Rejected]
 * without needing a real platform exception from a Robolectric-built [Service].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PumpConnectionServiceStartCommandTest {

    private lateinit var service: PumpConnectionService

    @Before
    fun setUp() {
        service = Robolectric.buildService(PumpConnectionService::class.java).get().apply {
            fgsTimeoutReporter = FgsTimeoutReporter(ApplicationProvider.getApplicationContext())
        }
    }

    @After
    fun tearDown() {
        unmockkObject(ForegroundServiceStarter)
        // onCreate arms the companion liveness probe against this instance; leaving it armed
        // would make a later test's reconcile see a service that no longer exists.
        PumpConnectionService.isRunning = { false }
    }

    @Test
    fun `a rejected first promotion stops the service and never starts polling`() {
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } returns REJECTED

        val result = service.onStartCommand(Intent(), 0, START_ID)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(
            "a rejected first promotion must stop the service, not run the BLE link unprotected",
            shadowOf(service).isStoppedBySelf,
        )
        assertFalse("started must stay false; the startup block must never have run", service.started)
    }

    @Test
    fun `a rejected redundant re-promote survives while the service is already running`() {
        service.started = true
        val pollingOrchestrator = mockk<PumpPollingOrchestrator>(relaxed = true)
        service.pollingOrchestrator = pollingOrchestrator
        // Simulate an earlier rejection's marker still being set, so the survive branch clearing
        // it is actually pinned (GLY-246 review F6 residual, NEW-6): a healthy running component
        // must not leave GLY-254 thinking it still owes a resume.
        service.fgsTimeoutReporter.recordForegroundStartRejected(
            FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
            IllegalStateException("missing permission"),
        )
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } returns REJECTED

        val result = service.onStartCommand(Intent(), 0, START_ID)

        assertEquals(Service.START_STICKY, result)
        assertFalse(
            "a healthy running service must survive a rejected redundant re-promote",
            shadowOf(service).isStoppedBySelf,
        )
        verify(exactly = 0) { pollingOrchestrator.start(any()) }
        assertFalse(
            "a healthy running component must not be left owing a resume it doesn't need",
            service.fgsTimeoutReporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION),
        )
    }

    @Test
    fun `a sticky restart resumes monitoring from the null intent the system redelivers`() {
        // START_STICKY recreation: the platform rebuilds the service (and the process with it) and
        // calls onStartCommand with a null intent. This is the third way monitoring comes back --
        // alongside app open and boot -- and the one that no longer has Application.onCreate
        // firing a second, illegal start on top of it.
        val service = fullyWiredService()

        val result = service.onStartCommand(null, Service.START_FLAG_RETRY, START_ID)

        assertEquals(Service.START_STICKY, result)
        assertTrue("the rebuilt service must come back watching", service.started)
        verify(exactly = 1) { service.pollingOrchestrator.start(any()) }
        verify(exactly = 1) { service.connectionManager.autoReconnectIfPaired() }
    }

    @Test
    fun `a redelivered start on a live service does not restart the orchestrators`() {
        // Whatever else reaches a running service -- a sticky redelivery, a redundant start from
        // the reconciler or Settings -- must be idempotent: a second orchestrator start would
        // double every poll loop.
        val service = fullyWiredService()
        service.onStartCommand(null, Service.START_FLAG_RETRY, START_ID)

        service.onStartCommand(Intent(), 0, START_ID + 1)

        verify(exactly = 1) { service.pollingOrchestrator.start(any()) }
        verify(exactly = 1) { service.connectionManager.autoReconnectIfPaired() }
    }

    /**
     * A service instance with every injected collaborator relaxed-mocked, so the full startup
     * block in `onStartCommand` can run. `onCreate` is deliberately not driven -- Hilt's generated
     * one demands an `@HiltAndroidApp` Application this Robolectric config does not have -- and
     * nothing in the startup block needs it: `lastFloorStatus` already defaults to the value
     * `onCreate` would seed, and posting to a channel that was never created is a no-op here.
     */
    private fun fullyWiredService(): PumpConnectionService =
        Robolectric.buildService(PumpConnectionService::class.java).get().apply {
            fgsTimeoutReporter = FgsTimeoutReporter(ApplicationProvider.getApplicationContext())
            pollingOrchestrator = mockk(relaxed = true)
            backendSyncManager = mockk(relaxed = true)
            connectionManager = mockk(relaxed = true)
            alertFloorStatusProvider = mockk(relaxed = true) {
                every { current() } returns AlertFloorStatus.ServerActive
                every { observe() } returns emptyFlow()
            }
            wearMonitoringStatusForwarder = mockk(relaxed = true)
            authTokenStore = mockk(relaxed = true)
        }

    private companion object {
        const val START_ID = 11
        val REJECTED = ForegroundServiceStartResult.Rejected(
            FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
            "SecurityException",
            ForegroundStartRejectionReason.PERMISSION_DENIED,
        )
    }
}
