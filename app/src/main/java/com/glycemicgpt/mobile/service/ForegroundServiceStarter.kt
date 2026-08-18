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
 * never named in a `catch` clause here -- not to dodge a verifier problem (ART resolves catch
 * types lazily, so naming an API-31 class in a catch is safe even on this app's minSdk 30), but
 * because it is simpler: `ForegroundServiceStartNotAllowedException` is an `IllegalStateException`
 * subclass, so catching the supertype (as the rest of this codebase already did -- see the
 * pre-existing guards this class replaces in [AlertStreamService.onStartCommand] and
 * [com.glycemicgpt.mobile.wear.WearChatRelayService.startWork]) covers it on every API level with
 * no `SDK_INT` branch, while [ForegroundServiceStartResult.Rejected.exceptionType] still reports
 * the concrete runtime type for telemetry.
 *
 * On rejection: never silently no-op. [FgsTimeoutReporter.recordForegroundStartRejected] persists
 * a durable start-rejected marker and reports it as telemetry, so the degraded state survives
 * process death; [FgsTimeoutReporter.clearStartRejectedPending] clears it on that component's next
 * successful [promote]. There is no reconciler to hand the retry to yet -- GLY-254 builds one --
 * so today the caller decides what "retry" means (a redundant `start()` call the next time its own
 * trigger fires, same as before this class existed). Once GLY-254 lands, its retry hook plugs in
 * here, reading [FgsTimeoutReporter.isStartRejectedPending] per component.
 */
object ForegroundServiceStarter {

    /** Companion-function call sites: `Context.startForegroundService`, before the service exists. */
    fun start(context: Context, intent: Intent, component: String): ForegroundServiceStartResult {
        // Lazy, not eager: the reporter's prefs handle must not open on the hot (success) path --
        // see the class doc on FgsTimeoutReporter. `by lazy` still resolves on first access from
        // inside whichever catch clause rejects the call (there is no other caller), but a single
        // shared instance means that resolution happens at most once per call instead of once per
        // catch clause (GLY-246 review F9). The `?: context` elvis on
        // `context.applicationContext` means Kotlin emits no null-check intrinsic for the
        // documented-nullable edge Context.getApplicationContext() can technically return -- there
        // is no null to escape the handler in the first place.
        val reporter by lazy { FgsTimeoutReporter(context.applicationContext ?: context) }
        return try {
            context.startForegroundService(intent)
            ForegroundServiceStartResult.Started
        } catch (e: IllegalStateException) {
            reject(reporter, component, e)
        } catch (e: SecurityException) {
            reject(reporter, component, e)
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
            // The component just promoted cleanly; whatever start-rejected marker it left behind
            // (from this attempt or an earlier one) no longer describes reality (GLY-246 review F4).
            reporter.clearStartRejectedPending(component)
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
        val reason = reporter.recordForegroundStartRejected(component, error)
        return ForegroundServiceStartResult.Rejected(component, error.javaClass.simpleName, reason)
    }
}

/** What the caller can act on after a guarded start attempt. */
sealed interface ForegroundServiceStartResult {
    data object Started : ForegroundServiceStartResult

    /**
     * [exceptionType] is the rejecting exception's simple class name; [reason] is
     * [FgsTimeoutReporter]'s best-effort classification of *why* -- both for telemetry/logging
     * (AC2 asks for exception type and reason).
     */
    data class Rejected(
        val component: String,
        val exceptionType: String,
        val reason: ForegroundStartRejectionReason,
    ) : ForegroundServiceStartResult
}
