// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import timber.log.Timber

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

    /**
     * Companion-function call sites: `Context.startForegroundService`, before the service exists.
     *
     * [isComponentRunning] tells a rejection that means "monitoring is off" apart from one that
     * means nothing at all (PR #44 review). Every redundant `start()` -- `GlycemicGptApp.onCreate`,
     * `AuthRepository`, the Settings ViewModel -- re-issues this call while the target service may
     * already be alive and healthy, and the platform can refuse it. A refused start never delivers
     * `onStartCommand`, so neither in-service clearing path runs; recording a start-rejected marker
     * there would hand GLY-254 a resume that is not owed. The probe defaults to "not running" so
     * call sites without a liveness signal keep today's behaviour.
     */
    fun start(
        context: Context,
        intent: Intent,
        component: String,
        isComponentRunning: () -> Boolean = { false },
    ): ForegroundServiceStartResult {
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
            reject(reporter, component, e, stillRunning(isComponentRunning))
        } catch (e: SecurityException) {
            reject(reporter, component, e, stillRunning(isComponentRunning))
        }
    }

    /**
     * The probe reads a live service's own state, so it runs on the boot path with whatever the
     * service was mid-way through. Nothing here may throw out of a rejection handler, and an
     * unreadable liveness signal is not evidence the component is up -- fall back to "not running",
     * which is the conservative answer (marker recorded, user warned).
     */
    private fun stillRunning(isComponentRunning: () -> Boolean): Boolean =
        runCatching { isComponentRunning() }.getOrDefault(false)

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
            // (from this attempt or an earlier one) no longer describes reality (GLY-246 review F4)
            // -- and neither does the notification that told the user monitoring was off (PR #44
            // review). Both are cleared under the same pending check so the success path costs one
            // in-memory prefs read, not a binder call, on every promotion.
            if (reporter.isStartRejectedPending(component)) {
                reporter.clearStartRejectedPending(component)
                // A degraded notification only exists for the components MonitoringDegradedNotifier
                // knows; for the rest this cancels an id that was never posted. runCatching for the
                // same reason the notify() call sites have it: nothing on the promotion path may
                // throw out of here.
                runCatching { MonitoringDegradedNotifier.clear(service, component) }
            }
            ForegroundServiceStartResult.Started
        } catch (e: IllegalStateException) {
            // In-service promotions already know their own liveness and branch on it themselves
            // (see the survive-branches in AlertStreamService/PumpConnectionService.onStartCommand),
            // so they always take the recording path.
            reject(reporter, component, e, stillRunning = false)
        } catch (e: SecurityException) {
            reject(reporter, component, e, stillRunning = false)
        }
    }

    private fun reject(
        reporter: FgsTimeoutReporter,
        component: String,
        error: Exception,
        stillRunning: Boolean,
    ): ForegroundServiceStartResult.Rejected {
        if (stillRunning) {
            // A redundant start refused while the component is demonstrably up. The durable marker
            // means "this component is not running and owes a resume", which is false here, and
            // recordForegroundStartRejected logs at ERROR -- a real Sentry event for a non-event.
            // Classify for the caller's log line and record nothing (PR #44 review).
            val reason = ForegroundStartRejectionReason.classify(error)
            Timber.w(
                "%s component=%s exceptionType=%s reason=%s -- redundant start refused while the " +
                    "component is already running; not recording",
                FgsTimeoutReporter.START_REJECTED_EVENT_TAG,
                component,
                error.javaClass.simpleName,
                reason.name,
            )
            return ForegroundServiceStartResult.Rejected(
                component,
                error.javaClass.simpleName,
                reason,
                componentStillRunning = true,
            )
        }
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
     *
     * [componentStillRunning] marks the benign case: the platform refused a redundant start while
     * the target service was demonstrably alive, so no marker was recorded and the caller must not
     * tell the user monitoring stopped (PR #44 review). Defaults to false -- the only case that
     * existed before, and the one every in-service promotion still takes.
     */
    data class Rejected(
        val component: String,
        val exceptionType: String,
        val reason: ForegroundStartRejectionReason,
        val componentStillRunning: Boolean = false,
    ) : ForegroundServiceStartResult
}
