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
import okhttp3.sse.EventSource
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
 * What [AlertStreamService.onStartCommand] does with a [ForegroundServiceStartResult.Rejected]
 * (GLY-246 review F5): a rejected first promotion must arm the on-device alert floor
 * (`onStreamStopped`) and stop, but a rejected *redundant* re-promote while the stream is already
 * connected must not tear it down (GLY-246 review F6) -- silently cancelling a healthy stream
 * would flash the alerting-degraded banner for no reason.
 *
 * [ForegroundServiceStarter] is mocked the same way [PumpConnectionServiceStartCommandTest] mocks
 * it, for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlertStreamServiceStartCommandTest {

    private lateinit var service: AlertStreamService
    private lateinit var stateHolder: AlertStreamStateHolder

    @Before
    fun setUp() {
        stateHolder = AlertStreamStateHolder()
        service = Robolectric.buildService(AlertStreamService::class.java).get().apply {
            alertStreamStateHolder = stateHolder
            fgsTimeoutReporter = FgsTimeoutReporter(ApplicationProvider.getApplicationContext())
        }
    }

    @After
    fun tearDown() {
        unmockkObject(ForegroundServiceStarter)
    }

    @Test
    fun `a rejected first promotion arms the alert floor and stops the service`() {
        stateHolder.onStreamOpened()
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } returns REJECTED

        val result = service.onStartCommand(Intent(), 0, START_ID)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(
            "a rejected start must arm the on-device alert floor, not leave the user silently uncovered",
            AlertStreamState.DISCONNECTED,
            stateHolder.state.value,
        )
    }

    @Test
    fun `a rejected redundant re-promote survives while the stream is already connected`() {
        service.eventSource = mockk<EventSource>(relaxed = true)
        stateHolder.onStreamOpened()
        // Simulate an earlier rejection's marker still being set, so the survive branch clearing
        // it is actually pinned (GLY-246 review F6 residual, NEW-6): a healthy connected stream
        // must not leave GLY-254 thinking it still owes a resume.
        service.fgsTimeoutReporter.recordForegroundStartRejected(
            FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
            IllegalStateException("missing permission"),
        )
        mockkObject(ForegroundServiceStarter)
        every {
            ForegroundServiceStarter.promote(any(), any(), any(), any(), any(), any())
        } returns REJECTED

        val result = service.onStartCommand(Intent(), 0, START_ID)

        assertEquals(Service.START_STICKY, result)
        assertFalse(
            "a healthy connected stream must survive a rejected redundant re-promote",
            shadowOf(service).isStoppedBySelf,
        )
        assertEquals(AlertStreamState.CONNECTED, stateHolder.state.value)
        assertFalse(
            "a healthy connected component must not be left owing a resume it doesn't need",
            service.fgsTimeoutReporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM),
        )
    }

    private companion object {
        const val START_ID = 13
        val REJECTED = ForegroundServiceStartResult.Rejected(
            FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
            "IllegalStateException",
            ForegroundStartRejectionReason.BUDGET_EXHAUSTED,
        )
    }
}
