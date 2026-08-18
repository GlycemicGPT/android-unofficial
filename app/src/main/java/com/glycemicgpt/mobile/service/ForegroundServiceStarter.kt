// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent

/**
 * The single place in the app allowed to call `Context.startForegroundService` or
 * `Service.startForeground` (GLY-246). Every other call site -- companion `start()` functions,
 * in-service promotions -- routes through here so a platform rejection is handled once instead
 * of N times.
 *
 * Modern Android throws from these APIs for reasons that are all the caller's problem to
 * survive, never crash on: background-start restrictions (API 26+), the per-type restrictions
 * introduced for `BOOT_COMPLETED` starts (Android 15), and the exhausted `dataSync` budget
 * (Android 15, `ForegroundServiceStartNotAllowedException`). That exception type is deliberately
 * never named in a `catch` clause here: it only exists in the framework from API 31, and with
 * this app's minSdk 30, referencing it directly in a catch risks verifier trouble on API 30
 * devices for a class the app is never installed against. It is an `IllegalStateException`, so
 * catching that (as the rest of this codebase already does -- see the pre-existing guards this
 * class replaces in [AlertStreamService.onStartCommand] and
 * [com.glycemicgpt.mobile.wear.WearChatRelayService.startWork]) covers it on every API level, and
 * [ForegroundServiceStartResult.Rejected.exceptionType] still reports the concrete runtime type
 * for telemetry.
 *
 * On rejection: never silently no-op. [FgsTimeoutReporter.recordForegroundStartRejected] persists
 * the same resume-pending marker a timeout does and reports it as telemetry, so the degraded
 * state survives process death. There is no reconciler to hand the retry to yet -- GLY-254 builds
 * one -- so today the caller decides what "retry" means (a redundant `start()` call the next time
 * its own trigger fires, same as before this class existed). Once GLY-254 lands, its retry hook
 * plugs in here, reading [FgsTimeoutReporter.isResumePending] per component.
 */
object ForegroundServiceStarter {

    /** Companion-function call sites: `Context.startForegroundService`, before the service exists. */
    fun start(context: Context, intent: Intent, component: String): ForegroundServiceStartResult {
        return try {
            context.startForegroundService(intent)
            ForegroundServiceStartResult.Started
        } catch (e: IllegalStateException) {
            reject(FgsTimeoutReporter(context.applicationContext), component, e)
        } catch (e: SecurityException) {
            reject(FgsTimeoutReporter(context.applicationContext), component, e)
        }
    }

    /**
     * In-service call sites: `Service.startForeground`, from inside `onStartCommand`/equivalent.
     * [reporter] is the caller's own Hilt-injected [FgsTimeoutReporter] instance rather than a
     * fresh one, since these call sites already have it.
     */
    fun promote(
        service: Service,
        notificationId: Int,
        notification: Notification,
        component: String,
        reporter: FgsTimeoutReporter,
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
            reject(reporter, component, e)
        } catch (e: SecurityException) {
            reject(reporter, component, e)
        }
    }

    private fun reject(
        reporter: FgsTimeoutReporter,
        component: String,
        error: Exception,
    ): ForegroundServiceStartResult.Rejected {
        reporter.recordForegroundStartRejected(component, error)
        return ForegroundServiceStartResult.Rejected(component, error.javaClass.simpleName)
    }
}

/** What the caller can act on after a guarded start attempt. */
sealed interface ForegroundServiceStartResult {
    data object Started : ForegroundServiceStartResult

    /** [exceptionType] is the rejecting exception's simple class name, for telemetry/logging. */
    data class Rejected(val component: String, val exceptionType: String) : ForegroundServiceStartResult
}
