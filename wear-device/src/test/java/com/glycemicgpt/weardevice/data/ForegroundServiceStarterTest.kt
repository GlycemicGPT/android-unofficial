// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.weardevice.data

import android.app.Notification
import android.app.Service
import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Watch-side twin of the phone's `ForegroundServiceStarterTest` (GLY-246 review F1): pins that
 * [ForegroundServiceStarter.promote] survives both exception types a rejected promotion can throw
 * and records a durable trace through [FgsTimeoutReporter], the same way the phone helper does.
 * `:wear-device` has no Robolectric ([FgsTimeoutReporterTest]'s doc comment), so
 * [android.app.Service] and [Notification] are mocked directly with mockk rather than built for
 * real -- neither is actually invoked beyond the mocked call, so no Android runtime is needed.
 */
class ForegroundServiceStarterTest {

    private val stored = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        stored.clear()
        FgsTimeoutReporter.setStoreForTest(mapBackedPrefs())
    }

    @After
    fun tearDown() {
        FgsTimeoutReporter.setStoreForTest(null)
    }

    @Test
    fun `promote succeeds when the platform allows it`() {
        val service = mockk<Service>(relaxed = true)
        val notification = mockk<Notification>()

        val result = ForegroundServiceStarter.promote(service, NOTIFICATION_ID, notification, COMPONENT)

        assertEquals(ForegroundServiceStartResult.Started, result)
        verify { service.startForeground(NOTIFICATION_ID, notification) }
    }

    @Test
    fun `promote survives an IllegalStateException and records the rejection`() {
        val service = mockk<Service>(relaxed = true) {
            every { startForeground(any<Int>(), any()) } throws IllegalStateException("budget exhausted")
        }
        val notification = mockk<Notification>()

        val result = ForegroundServiceStarter.promote(service, NOTIFICATION_ID, notification, COMPONENT)

        assertEquals(
            ForegroundServiceStartResult.Rejected(COMPONENT, "IllegalStateException"),
            result,
        )
        assertTrue(FgsTimeoutReporter.startRejectedCount(COMPONENT) > 0)
    }

    @Test
    fun `promote survives a SecurityException and records the rejection`() {
        val service = mockk<Service>(relaxed = true) {
            every { startForeground(any<Int>(), any()) } throws SecurityException("missing permission")
        }
        val notification = mockk<Notification>()

        val result = ForegroundServiceStarter.promote(service, NOTIFICATION_ID, notification, COMPONENT)

        assertEquals(
            ForegroundServiceStartResult.Rejected(COMPONENT, "SecurityException"),
            result,
        )
        assertTrue(FgsTimeoutReporter.startRejectedCount(COMPONENT) > 0)
    }

    @Test
    fun `promote with a foreground service type calls the 3-arg overload`() {
        val service = mockk<Service>(relaxed = true)
        val notification = mockk<Notification>()

        val result = ForegroundServiceStarter.promote(
            service, NOTIFICATION_ID, notification, COMPONENT, foregroundServiceType = DATA_SYNC_TYPE,
        )

        assertEquals(ForegroundServiceStartResult.Started, result)
        verify { service.startForeground(NOTIFICATION_ID, notification, DATA_SYNC_TYPE) }
    }

    /** Minimal SharedPreferences double, matching [FgsTimeoutReporterTest]'s fake. */
    private fun mapBackedPrefs(): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putLong(any(), any()) } answers {
            stored[firstArg()] = secondArg<Long>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            stored[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.apply() } just Runs

        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        every { prefs.getLong(any(), any()) } answers {
            stored[firstArg()] as? Long ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            stored[firstArg()] as? Int ?: secondArg()
        }
        return prefs
    }

    private companion object {
        const val COMPONENT = "test_component"
        const val NOTIFICATION_ID = 42
        const val DATA_SYNC_TYPE = 1 // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
