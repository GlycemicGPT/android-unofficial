// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
