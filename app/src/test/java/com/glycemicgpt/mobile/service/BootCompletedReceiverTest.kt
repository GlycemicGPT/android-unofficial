// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.content.Context
import com.glycemicgpt.mobile.data.local.AuthTokenStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Boot is one of the two legal contexts monitoring starts from, and the only one that works with
 * the app closed -- so it has to keep resuming pump monitoring now that `Application.onCreate`
 * no longer does.
 *
 * The receiver hands the decision to [MonitoringReconciler] rather than calling
 * `PumpConnectionService.start` itself: the paired check, the already-running check and the
 * telemetry all live in one place, and boot is stamped as the trigger so a rejection here is
 * distinguishable from one on app open.
 */
class BootCompletedReceiverTest {

    private val reconciler: MonitoringReconciler = mockk {
        every { reconcile(any()) } returns MonitoringReconcileDecision.STARTED
    }
    private val authTokenStore: AuthTokenStore = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val receiver = BootCompletedReceiver().apply {
        monitoringReconciler = this@BootCompletedReceiverTest.reconciler
        authTokenStore = this@BootCompletedReceiverTest.authTokenStore
    }

    @Before
    fun setUp() {
        // AlertStreamService.start builds an Android Intent; stub it out.
        mockkObject(AlertStreamService.Companion)
        every { AlertStreamService.start(any()) } returns ForegroundServiceStartResult.Started
    }

    @After
    fun tearDown() {
        unmockkObject(AlertStreamService.Companion)
    }

    @Test
    fun `boot reconciles monitoring from the boot trigger`() {
        every { authTokenStore.getRefreshToken() } returns null

        receiver.restartMonitoring(context)

        verify(exactly = 1) {
            reconciler.reconcile(MonitoringReconcileTrigger.BOOT_COMPLETED)
        }
    }

    @Test
    fun `a logged-in user also gets the alert stream back`() {
        every { authTokenStore.getRefreshToken() } returns "refresh-token"

        receiver.restartMonitoring(context)

        verify(exactly = 1) { reconciler.reconcile(MonitoringReconcileTrigger.BOOT_COMPLETED) }
        verify(exactly = 1) { AlertStreamService.start(context) }
    }

    @Test
    fun `a logged-out user gets monitoring back without an alert stream`() {
        every { authTokenStore.getRefreshToken() } returns null

        receiver.restartMonitoring(context)

        verify(exactly = 1) { reconciler.reconcile(MonitoringReconcileTrigger.BOOT_COMPLETED) }
        verify(exactly = 0) { AlertStreamService.start(any()) }
    }
}
