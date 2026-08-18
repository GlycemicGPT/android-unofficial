// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.wear

import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.glycemicgpt.mobile.service.FgsTimeoutReporter
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
        // message. onTimeout cancels the scope's children rather than the scope, so a second
        // timeout -- and any later work -- still runs instead of hitting a dead scope.
        service.onTimeout(START_ID, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertEquals(2, reporter.timeoutCount(FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY))
    }

    private companion object {
        const val START_ID = 3
    }
}
