// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-storage proof of the durable half of [FgsTimeoutReporter] (GLY-244). The marker is what
 * survives the process, so it is tested against actual SharedPreferences rather than a fake.
 */
// Plain Application: the manifest's @HiltAndroidApp class would pull keystore-backed injection
// into a JVM test that only needs a Context for plain SharedPreferences.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FgsTimeoutReporterTest {

    private lateinit var reporter: FgsTimeoutReporter

    @Before
    fun setUp() {
        reporter = FgsTimeoutReporter(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a fresh install has no timeout history`() {
        assertEquals(0L, reporter.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        assertEquals(0, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        assertFalse(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
    }

    @Test
    fun `recordTimeout persists when, which type, and that a resume is owed`() {
        reporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
            startId = 7,
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            resumable = true,
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(
            1_700_000_000_000L,
            reporter.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_ALERT_STREAM),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            reporter.lastFgsType(FgsTimeoutReporter.COMPONENT_ALERT_STREAM),
        )
        assertEquals(1, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        assertTrue(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
    }

    @Test
    fun `repeated timeouts accumulate a count`() {
        repeat(3) {
            reporter.recordTimeout(
                component = FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
                startId = 1,
                fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                resumable = true,
            )
        }

        assertEquals(3, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
    }

    @Test
    fun `work with nothing to resume leaves no resume marker`() {
        reporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY,
            startId = 1,
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            resumable = false,
        )

        assertFalse(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
        assertEquals(1, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
    }

    @Test
    fun `clearResumePending keeps the history it clears the marker from`() {
        reporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
            startId = 1,
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            resumable = true,
            nowMs = 1_700_000_000_000L,
        )

        reporter.clearResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM)

        assertFalse(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        assertEquals(
            1_700_000_000_000L,
            reporter.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_ALERT_STREAM),
        )
        assertEquals(1, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
    }

    @Test
    fun `clearResumePending on a component that never timed out is a no-op`() {
        reporter.clearResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM)

        assertFalse(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        assertEquals(0, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
    }

    @Test
    fun `one service timing out says nothing about the others`() {
        reporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
            startId = 1,
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            resumable = true,
        )

        assertEquals(0L, reporter.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
        assertEquals(0, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
        assertFalse(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
    }

    @Test
    fun `the marker survives a new reporter over the same storage`() {
        reporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_ALERT_STREAM,
            startId = 1,
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            resumable = true,
            nowMs = 1_700_000_000_000L,
        )

        // Stands in for the next process: a fresh instance reading the same prefs file.
        val afterRestart = FgsTimeoutReporter(ApplicationProvider.getApplicationContext())

        assertTrue(afterRestart.isResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
        assertEquals(
            1_700_000_000_000L,
            afterRestart.lastTimeoutAtMs(FgsTimeoutReporter.COMPONENT_ALERT_STREAM),
        )
    }
}
