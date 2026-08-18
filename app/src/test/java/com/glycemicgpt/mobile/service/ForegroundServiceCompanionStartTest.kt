// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [PumpConnectionService.start] and [AlertStreamService.start] are the two companion-function
 * call sites GLY-246 covers (every other Context-level call site -- `MonitoringReconciler`,
 * `BootCompletedReceiver`, `AuthRepository`, the pairing/settings/debug ViewModels -- calls one
 * of these two, so proving they never throw and tag their own component correctly covers all of
 * them transitively; [ForegroundServiceStarterCoverageTest] is what pins that no call site
 * bypasses them). This is the counterpart to [ForegroundServiceStarterTest], which exercises the
 * shared exception-handling logic itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForegroundServiceCompanionStartTest {

    private lateinit var reporter: FgsTimeoutReporter

    @Before
    fun setUp() {
        reporter = FgsTimeoutReporter(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        // Companion state outlives a Robolectric test's Application; leaving a probe armed would
        // silently suppress the notification assertions in every test that runs after it.
        PumpConnectionService.isRunning = { false }
        AlertStreamService.isRunning = { false }
    }

    @Test
    fun `PumpConnectionService rejected redundant start while running warns nobody and records nothing`() {
        // The service is up and the BLE link is live; the platform refuses the redundant start a
        // Settings open issues. onStartCommand is never delivered, so no in-service path could
        // undo a warning posted here (PR #44 review).
        PumpConnectionService.isRunning = { true }
        val context = rejectingContext(IllegalStateException("dataSync budget exhausted"))

        val result = PumpConnectionService.start(context)

        assertEquals(
            ForegroundServiceStartResult.Rejected(
                FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
                "IllegalStateException",
                ForegroundStartRejectionReason.BUDGET_EXHAUSTED,
                componentStillRunning = true,
            ),
            result,
        )
        assertFalse(reporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION))
        val appContext: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            0,
            shadowOf(appContext.getSystemService(NotificationManager::class.java))
                .allNotifications.size,
        )
    }

    @Test
    fun `AlertStreamService rejected redundant start while connected warns nobody and records nothing`() {
        AlertStreamService.isRunning = { true }
        val context = rejectingContext(IllegalStateException("dataSync budget exhausted"))

        val result = AlertStreamService.start(context)

        assertEquals(
            ForegroundServiceStartResult.Rejected(
                FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
                "IllegalStateException",
                ForegroundStartRejectionReason.BUDGET_EXHAUSTED,
                componentStillRunning = true,
            ),
            result,
        )
        assertFalse(reporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        val appContext: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            0,
            shadowOf(appContext.getSystemService(NotificationManager::class.java))
                .allNotifications.size,
        )
    }

    @Test
    fun `a liveness probe that throws is treated as not running`() {
        // The probe reads a live service's own state; an unreadable signal must fall back to the
        // conservative answer rather than escape the rejection handler on the boot path.
        PumpConnectionService.isRunning = { throw IllegalStateException("probe blew up") }
        val context = rejectingContext(IllegalStateException("dataSync budget exhausted"))

        val result = PumpConnectionService.start(context)

        assertTrue(result is ForegroundServiceStartResult.Rejected)
        assertFalse((result as ForegroundServiceStartResult.Rejected).componentStillRunning)
        assertTrue(reporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION))
    }

    @Test
    fun `PumpConnectionService start survives an IllegalStateException and tags the pump component`() {
        val context = rejectingContext(IllegalStateException("dataSync budget exhausted"))

        val result = PumpConnectionService.start(context)

        assertEquals(
            ForegroundServiceStartResult.Rejected(
                FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
                "IllegalStateException",
                ForegroundStartRejectionReason.BUDGET_EXHAUSTED,
            ),
            result,
        )
        assertTrue(reporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION))
        // The service was never created, so this notification is the only thing that tells the
        // user pump monitoring is off (GLY-246 review F2/NEW-4).
        val appContext: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            1,
            shadowOf(appContext.getSystemService(NotificationManager::class.java))
                .allNotifications.size,
        )
    }

    @Test
    fun `PumpConnectionService start survives a SecurityException and tags the pump component`() {
        val context = rejectingContext(SecurityException("missing permission"))

        val result = PumpConnectionService.start(context)

        assertEquals(
            ForegroundServiceStartResult.Rejected(
                FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
                "SecurityException",
                ForegroundStartRejectionReason.PERMISSION_DENIED,
            ),
            result,
        )
        assertTrue(reporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION))
    }

    @Test
    fun `AlertStreamService start survives an IllegalStateException and tags the alert stream component`() {
        val context = rejectingContext(IllegalStateException("dataSync budget exhausted"))

        val result = AlertStreamService.start(context)

        assertEquals(
            ForegroundServiceStartResult.Rejected(
                FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
                "IllegalStateException",
                ForegroundStartRejectionReason.BUDGET_EXHAUSTED,
            ),
            result,
        )
        assertTrue(reporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        // The service was never created, so this notification is the only thing that tells the
        // user alert delivery is off (GLY-246 review F2/NEW-4).
        val appContext: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            1,
            shadowOf(appContext.getSystemService(NotificationManager::class.java))
                .allNotifications.size,
        )
    }

    @Test
    fun `AlertStreamService start survives a SecurityException and tags the alert stream component`() {
        val context = rejectingContext(SecurityException("missing permission"))

        val result = AlertStreamService.start(context)

        assertEquals(
            ForegroundServiceStartResult.Rejected(
                FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
                "SecurityException",
                ForegroundStartRejectionReason.PERMISSION_DENIED,
            ),
            result,
        )
        assertTrue(reporter.isStartRejectedPending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
    }

    private fun rejectingContext(error: Throwable): Context {
        val appContext: Context = ApplicationProvider.getApplicationContext()
        return mockk<Context>(relaxed = true) {
            every { applicationContext } returns appContext
            every { startForegroundService(any()) } throws error
        }
    }
}
