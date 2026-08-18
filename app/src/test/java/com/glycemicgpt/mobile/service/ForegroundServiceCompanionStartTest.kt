// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PumpConnectionService.start] and [AlertStreamService.start] are the two companion-function
 * call sites GLY-246 covers (every other Context-level call site -- `GlycemicGptApp.onCreate`,
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

    @Test
    fun `PumpConnectionService start survives a platform rejection and tags the pump component`() {
        val context = rejectingContext()

        PumpConnectionService.start(context)

        assertTrue(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION))
    }

    @Test
    fun `AlertStreamService start survives a platform rejection and tags the alert stream component`() {
        val context = rejectingContext()

        AlertStreamService.start(context)

        assertTrue(reporter.isResumePending(FgsTimeoutReporter.COMPONENT_ALERT_STREAM))
    }

    private fun rejectingContext(): Context {
        val appContext: Context = ApplicationProvider.getApplicationContext()
        return mockk<Context>(relaxed = true) {
            every { applicationContext } returns appContext
            every { startForegroundService(any()) } throws
                IllegalStateException("dataSync budget exhausted")
        }
    }
}
