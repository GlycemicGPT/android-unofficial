// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.glycemicgpt.mobile.data.local.PumpCredentialStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * The decision table behind "monitoring should be running, and here is a context that may start
 * it". The point of the class is what it *does not* do: a trigger with no legal context and no
 * exemption must defer rather than attempt a start the platform would refuse with the
 * `ForegroundServiceStartNotAllowedException` that used to crash the app out of
 * `GlycemicGptApp.onCreate`.
 *
 * [PumpConnectionService] is mocked via `mockkObject` (established idiom:
 * [PumpConnectionServiceStartCommandTest], `PairingViewModelTest`) so a start can be observed and
 * a rejection forced without a real platform exception.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MonitoringReconcilerTest {

    private lateinit var credentialStore: PumpCredentialStore
    private lateinit var eligibility: BackgroundStartEligibility
    private lateinit var reconciler: MonitoringReconciler
    private val logs = mutableListOf<Pair<Int, String>>()
    private lateinit var tree: Timber.Tree

    @Before
    fun setUp() {
        credentialStore = mockk(relaxed = true)
        every { credentialStore.isPaired() } returns true
        eligibility = mockk()
        every { eligibility.isEligible() } returns false
        val context: Context = ApplicationProvider.getApplicationContext()
        reconciler = MonitoringReconciler(context, credentialStore, eligibility)

        mockkObject(PumpConnectionService.Companion)
        every { PumpConnectionService.start(any()) } returns ForegroundServiceStartResult.Started
        PumpConnectionService.isRunning = { false }

        tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += priority to message
            }
        }
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        PumpConnectionService.isRunning = { false }
        unmockkObject(PumpConnectionService.Companion)
    }

    @Test
    fun `a foregrounded app starts monitoring when a pump is paired`() {
        val decision = reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)

        assertEquals(MonitoringReconcileDecision.STARTED, decision)
        verify(exactly = 1) { PumpConnectionService.start(any()) }
    }

    @Test
    fun `boot completed starts monitoring -- connectedDevice is still exempt there`() {
        val decision = reconciler.reconcile(MonitoringReconcileTrigger.BOOT_COMPLETED)

        assertEquals(MonitoringReconcileDecision.STARTED, decision)
        verify(exactly = 1) { PumpConnectionService.start(any()) }
    }

    @Test
    fun `an unpaired device never starts anything`() {
        every { credentialStore.isPaired() } returns false

        val decision = reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)

        assertEquals(MonitoringReconcileDecision.NOT_PAIRED, decision)
        verify(exactly = 0) { PumpConnectionService.start(any()) }
    }

    @Test
    fun `an unreadable credential store decides against starting instead of throwing`() {
        // The store is keystore-backed; a read can fail, and this runs inside a BroadcastReceiver.
        every { credentialStore.isPaired() } throws IllegalStateException("keystore unavailable")

        val decision = reconciler.reconcile(MonitoringReconcileTrigger.BOOT_COMPLETED)

        assertEquals(MonitoringReconcileDecision.NOT_PAIRED, decision)
        verify(exactly = 0) { PumpConnectionService.start(any()) }
    }

    @Test
    fun `an already-running service is not started a second time`() {
        // The system-initiated START_STICKY recreation case: the platform rebuilt the service (and
        // with it the process), and a reconcile arriving alongside it must not issue a second
        // start on top of the one the platform already made.
        PumpConnectionService.isRunning = { true }

        val decision = reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)

        assertEquals(MonitoringReconcileDecision.ALREADY_RUNNING, decision)
        verify(exactly = 0) { PumpConnectionService.start(any()) }
    }

    @Test
    fun `an ineligible background trigger defers instead of attempting an illegal start`() {
        // The path a worker run, a wear message or a notification action takes: no legal context
        // and no exemption, so the start the platform would refuse is never made.
        val decision = reconciler.reconcile(MonitoringReconcileTrigger.BACKGROUND)

        assertEquals(MonitoringReconcileDecision.DEFERRED_INELIGIBLE, decision)
        verify(exactly = 0) { PumpConnectionService.start(any()) }
    }

    @Test
    fun `an exempt background trigger may start monitoring`() {
        // Battery-optimization allowlisted, or already at foreground importance: the platform
        // accepts the start, so deferring would leave monitoring off for no reason.
        every { eligibility.isEligible() } returns true

        val decision = reconciler.reconcile(MonitoringReconcileTrigger.BACKGROUND)

        assertEquals(MonitoringReconcileDecision.STARTED, decision)
        verify(exactly = 1) { PumpConnectionService.start(any()) }
    }

    @Test
    fun `a legal context never has to prove eligibility`() {
        // App-open and boot carry their own exemption; probing platform state there would be a
        // second, weaker gate that could veto a start the platform would have accepted.
        reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)
        reconciler.reconcile(MonitoringReconcileTrigger.BOOT_COMPLETED)

        verify(exactly = 0) { eligibility.isEligible() }
    }

    @Test
    fun `a platform rejection from a legal context is reported, not thrown`() {
        every { PumpConnectionService.start(any()) } returns ForegroundServiceStartResult.Rejected(
            FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
            "ForegroundServiceStartNotAllowedException",
            ForegroundStartRejectionReason.BACKGROUND_START_RESTRICTION,
        )

        val decision = reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)

        assertEquals(MonitoringReconcileDecision.START_REJECTED, decision)
    }

    @Test
    fun `a refusal on top of a service that is demonstrably up is not a failed start`() {
        // The tail of the race the isRunning() probe above loses: the service came up between that
        // probe and this start, so the platform refused a redundant start while the component was
        // alive. ForegroundServiceStarter records nothing for that case; calling it START_REJECTED
        // here would still set monitoringOff and warn that monitoring is not running, about a
        // service that is.
        every { PumpConnectionService.start(any()) } returns ForegroundServiceStartResult.Rejected(
            FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
            "ForegroundServiceStartNotAllowedException",
            ForegroundStartRejectionReason.BACKGROUND_START_RESTRICTION,
            componentStillRunning = true,
        )

        val decision = reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)

        assertEquals(MonitoringReconcileDecision.ALREADY_RUNNING, decision)
        val line = logs.single { it.second.startsWith(MonitoringReconciler.RECONCILE_EVENT_TAG) }
        assertEquals(
            "a start refused on top of a live service is not a breadcrumb",
            android.util.Log.INFO,
            line.first,
        )
    }

    @Test
    fun `every reconcile reports its trigger and decision, and nothing else`() {
        reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)

        val line = logs.single { it.second.startsWith(MonitoringReconciler.RECONCILE_EVENT_TAG) }
        assertEquals(
            "${MonitoringReconciler.RECONCILE_EVENT_TAG} trigger=APP_FOREGROUNDED decision=STARTED",
            line.second,
        )
        assertEquals(
            "a healthy start is not a Sentry breadcrumb",
            android.util.Log.INFO,
            line.first,
        )
    }

    @Test
    fun `a decision that leaves monitoring off is reported at breadcrumb level`() {
        reconciler.reconcile(MonitoringReconcileTrigger.BACKGROUND)

        val line = logs.single { it.second.startsWith(MonitoringReconciler.RECONCILE_EVENT_TAG) }
        assertEquals(
            "SentryTimberIntegration turns WARN into the breadcrumb that explains a later report",
            android.util.Log.WARN,
            line.first,
        )
        assertTrue(line.second.contains("decision=DEFERRED_INELIGIBLE"))
    }
}
