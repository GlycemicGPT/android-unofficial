// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Activity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.inject.Provider

/**
 * The replacement for the `Application.onCreate` start: monitoring resumes when the app actually
 * becomes visible, which is a context the platform lets a foreground service start from.
 *
 * Two properties matter beyond "it fires at all" -- it must fire once per foregrounding rather
 * than once per screen, and it must not resolve the reconciler (and with it the keystore-backed
 * credential store) in a process where no Activity ever starts, which is every background process
 * creation GLY-247 exists to make harmless.
 */
class MonitoringForegroundObserverTest {

    private val reconciler: MonitoringReconciler = mockk(relaxed = true) {
        every { reconcile(any()) } returns MonitoringReconcileDecision.STARTED
    }
    private var resolutions = 0
    private val observer = MonitoringForegroundObserver(
        Provider {
            resolutions++
            reconciler
        },
    )

    private fun activity(): Activity = mockk(relaxed = true)

    @Test
    fun `the app becoming visible reconciles from a foreground context`() {
        observer.onActivityStarted(activity())

        verify(exactly = 1) {
            reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)
        }
    }

    @Test
    fun `navigating between screens does not reconcile again`() {
        val home = activity()
        val settings = activity()

        observer.onActivityStarted(home)
        // The incoming screen starts before the outgoing one stops, so the app never leaves the
        // foreground and there is nothing to reconcile.
        observer.onActivityStarted(settings)
        observer.onActivityStopped(home)

        verify(exactly = 1) { reconciler.reconcile(any()) }
    }

    @Test
    fun `returning to the app after it was fully backgrounded reconciles again`() {
        val home = activity()

        observer.onActivityStarted(home)
        observer.onActivityStopped(home)
        observer.onActivityStarted(home)

        verify(exactly = 2) {
            reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)
        }
    }

    @Test
    fun `a process with no Activity never resolves the reconciler`() {
        // A periodic worker run, an inbound wear message, a notification action: application init
        // registers this observer and nothing else happens. Resolving the reconciler here would
        // build the keystore-backed credential store for no reason.
        assertEquals(0, resolutions)
        verify(exactly = 0) { reconciler.reconcile(any()) }
    }

    @Test
    fun `an unbalanced stop cannot drive the count negative and suppress a later foregrounding`() {
        // Activity callbacks are delivered in order on the main thread, but a stop the observer
        // never saw the matching start for (registered mid-flight) must not leave the counter
        // below zero -- that would swallow the next real foregrounding.
        observer.onActivityStopped(activity())
        observer.onActivityStarted(activity())

        verify(exactly = 1) {
            reconciler.reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)
        }
    }
}
