// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.weardevice.data

import android.app.Notification
import android.app.Service
import timber.log.Timber

/**
 * Watch-side twin of the phone's `ForegroundServiceStarter` (GLY-246): the only place in
 * `:wear-device` allowed to call `Service.startForeground`. Both
 * [com.glycemicgpt.weardevice.push.WatchFaceReceiveService] and
 * [com.glycemicgpt.weardevice.update.WatchApkReceiveService] already ran their promotion inside a
 * generic `catch (e: Exception)` and continued unprotected on failure -- that trade is unchanged
 * here, but the rejection now leaves a durable record through [FgsTimeoutReporter] instead of a
 * release-invisible `Timber.w` (the watch has no Sentry and plants no Timber tree in release
 * builds, so the persisted counter is the only trace that survives). Giving both call sites the
 * same shape as the phone's helper is also what lets `ForegroundServiceStarterCoverageTest` (in
 * `:app`, scanning both modules by source text the way `DataSyncTimeoutCoverageTest` does) pin
 * that nothing here bypasses it.
 */
object ForegroundServiceStarter {

    fun promote(
        service: Service,
        notificationId: Int,
        notification: Notification,
        component: String,
        foregroundServiceType: Int? = null,
    ): ForegroundServiceStartResult {
        return try {
            if (foregroundServiceType != null) {
                service.startForeground(notificationId, notification, foregroundServiceType)
            } else {
                service.startForeground(notificationId, notification)
            }
            ForegroundServiceStartResult.Started
        } catch (e: IllegalStateException) {
            reject(component, e)
        } catch (e: SecurityException) {
            reject(component, e)
        }
    }

    private fun reject(component: String, error: Exception): ForegroundServiceStartResult.Rejected {
        FgsTimeoutReporter.recordForegroundStartRejected(component, error)
        Timber.w(error, "Foreground promotion rejected for %s, continuing unprotected", component)
        return ForegroundServiceStartResult.Rejected(component, error.javaClass.simpleName)
    }
}

/** What the caller can act on after a guarded promotion attempt. */
sealed interface ForegroundServiceStartResult {
    data object Started : ForegroundServiceStartResult

    /** [exceptionType] is the rejecting exception's simple class name, for telemetry/logging. */
    data class Rejected(val component: String, val exceptionType: String) : ForegroundServiceStartResult
}
