// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ForegroundServiceStarter] is the one place both a [Context.startForegroundService] call
 * (before a service exists) and a [Service.startForeground] promotion (from inside one) get
 * guarded (GLY-246). Every exception type the platform actually throws for a rejected start --
 * [IllegalStateException] (covers `ForegroundServiceStartNotAllowedException`, API 31+, and the
 * background-start restriction, API 26+) and [SecurityException] (a missing permission) -- is
 * exercised for both call shapes: a rejection must never escape as a crash, and must leave a
 * durable, telemetry-visible trace behind for GLY-254 to resume from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForegroundServiceStarterTest {

    private lateinit var reporter: FgsTimeoutReporter
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        reporter = FgsTimeoutReporter(appContext)
    }

    private fun testNotification() =
        NotificationCompat.Builder(appContext, "test_channel")
            .setContentTitle("test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

    // --- start(context, intent, component): the companion-function call shape ---

    @Test
    fun `start succeeds when the platform allows it`() {
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns appContext
        }

        val result = ForegroundServiceStarter.start(context, Intent(), COMPONENT)

        assertEquals(ForegroundServiceStartResult.Started, result)
        assertFalse(reporter.isStartRejectedPending(COMPONENT))
        // Pins the exact "start gets swallowed" regression this helper exists to prevent: a
        // Started result with no underlying platform call would pass without this (GLY-246
        // review F8).
        verify { context.startForegroundService(any()) }
    }

    @Test
    fun `start survives an IllegalStateException and reports the rejection`() {
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns appContext
            every { startForegroundService(any()) } throws IllegalStateException("budget exhausted")
        }

        val result = ForegroundServiceStarter.start(context, Intent(), COMPONENT)

        assertEquals(
            ForegroundServiceStartResult.Rejected(COMPONENT, "IllegalStateException", ForegroundStartRejectionReason.BUDGET_EXHAUSTED),
            result,
        )
        assertTrue(reporter.isStartRejectedPending(COMPONENT))
        assertTrue(reporter.lastStartRejectedAtMs(COMPONENT) > 0L)
    }

    @Test
    fun `start survives a SecurityException and reports the rejection`() {
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns appContext
            every { startForegroundService(any()) } throws SecurityException("missing permission")
        }

        val result = ForegroundServiceStarter.start(context, Intent(), COMPONENT)

        assertEquals(
            ForegroundServiceStartResult.Rejected(COMPONENT, "SecurityException", ForegroundStartRejectionReason.PERMISSION_DENIED),
            result,
        )
        assertTrue(reporter.isStartRejectedPending(COMPONENT))
    }

    // --- promote(service, ...): the in-service Service.startForeground call shape ---

    @Test
    fun `promote succeeds when the platform allows it`() {
        val service = mockk<Service>(relaxed = true)

        val result = ForegroundServiceStarter.promote(
            service, NOTIFICATION_ID, testNotification(), COMPONENT, reporter,
        )

        assertEquals(ForegroundServiceStartResult.Started, result)
        assertFalse(reporter.isStartRejectedPending(COMPONENT))
        verify { service.startForeground(NOTIFICATION_ID, any()) }
    }

    @Test
    fun `promote clears an earlier start-rejected marker on success`() {
        reporter.recordForegroundStartRejected(COMPONENT, IllegalStateException("budget exhausted"))
        assertTrue(reporter.isStartRejectedPending(COMPONENT))
        val service = mockk<Service>(relaxed = true)

        ForegroundServiceStarter.promote(service, NOTIFICATION_ID, testNotification(), COMPONENT, reporter)

        assertFalse(reporter.isStartRejectedPending(COMPONENT))
    }

    @Test
    fun `promote takes down the degraded notification the rejection left on screen`() {
        // The user-visible half of the same recovery: the marker is for GLY-254, this is for the
        // person holding the phone, who otherwise keeps a "monitoring not running" warning that
        // stopped being true the moment this promotion succeeded (PR #44 review).
        val component = FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION
        reporter.recordForegroundStartRejected(component, IllegalStateException("budget exhausted"))
        MonitoringDegradedNotifier.notify(appContext, component)
        val manager = appContext.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.activeNotifications.size)
        val service = mockk<Service>(relaxed = true) {
            every { getSystemService(NotificationManager::class.java) } returns manager
        }

        ForegroundServiceStarter.promote(service, NOTIFICATION_ID, testNotification(), component, reporter)

        assertFalse(reporter.isStartRejectedPending(component))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `promote survives an IllegalStateException and reports the rejection`() {
        val service = mockk<Service>(relaxed = true) {
            every { startForeground(any<Int>(), any()) } throws IllegalStateException("budget exhausted")
        }

        val result = ForegroundServiceStarter.promote(
            service, NOTIFICATION_ID, testNotification(), COMPONENT, reporter,
        )

        assertEquals(
            ForegroundServiceStartResult.Rejected(COMPONENT, "IllegalStateException", ForegroundStartRejectionReason.BUDGET_EXHAUSTED),
            result,
        )
        assertTrue(reporter.isStartRejectedPending(COMPONENT))
    }

    @Test
    fun `promote survives a SecurityException and reports the rejection`() {
        val service = mockk<Service>(relaxed = true) {
            every { startForeground(any<Int>(), any()) } throws SecurityException("missing permission")
        }

        val result = ForegroundServiceStarter.promote(
            service, NOTIFICATION_ID, testNotification(), COMPONENT, reporter,
        )

        assertEquals(
            ForegroundServiceStartResult.Rejected(COMPONENT, "SecurityException", ForegroundStartRejectionReason.PERMISSION_DENIED),
            result,
        )
        assertTrue(reporter.isStartRejectedPending(COMPONENT))
    }

    @Test
    fun `promote with a foreground service type succeeds and calls the 3-arg overload`() {
        val service = mockk<Service>(relaxed = true)

        val result = ForegroundServiceStarter.promote(
            service,
            NOTIFICATION_ID,
            testNotification(),
            COMPONENT,
            reporter,
            foregroundServiceType = DATA_SYNC_TYPE,
        )

        assertEquals(ForegroundServiceStartResult.Started, result)
        // Discriminates which overload actually ran: a stray 2-arg call here would leave this
        // unverified and still return Started (GLY-246 review F8).
        verify { service.startForeground(NOTIFICATION_ID, any(), DATA_SYNC_TYPE) }
    }

    @Test
    fun `promote with a foreground service type rejection still reports the type-specific overload`() {
        val service = mockk<Service>(relaxed = true) {
            every { startForeground(any<Int>(), any(), any<Int>()) } throws
                IllegalStateException("dataSync budget exhausted")
        }

        val result = ForegroundServiceStarter.promote(
            service,
            NOTIFICATION_ID,
            testNotification(),
            COMPONENT,
            reporter,
            foregroundServiceType = DATA_SYNC_TYPE,
        )

        assertEquals(
            ForegroundServiceStartResult.Rejected(COMPONENT, "IllegalStateException", ForegroundStartRejectionReason.BUDGET_EXHAUSTED),
            result,
        )
    }

    private companion object {
        const val COMPONENT = "test_component"
        const val NOTIFICATION_ID = 42
        const val DATA_SYNC_TYPE = 1 // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
