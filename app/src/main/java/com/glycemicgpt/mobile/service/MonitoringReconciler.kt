// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import com.glycemicgpt.mobile.data.local.PumpCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether pump monitoring *should* be running and, when it should, starts it -- but only
 * from a context where a foreground-service start is legal.
 *
 * This replaces the unconditional `PumpConnectionService.start(this)` that used to sit at the end
 * of `GlycemicGptApp.onCreate`. `Application.onCreate` runs on every process creation, including
 * the many the user never triggered: a periodic `DataRetentionWorker`/`NightscoutSyncWorker` run,
 * an inbound wear message waking `WearChatRelayService`, `AlertActionReceiver` handling a
 * notification action, any ContentProvider touch. In all of those the app is in the background,
 * where Android 12+ refuses a foreground-service start outright -- field DropBox evidence on an
 * Android 16 device shows `ForegroundServiceStartNotAllowedException` (`mAllowStartForeground`
 * false) thrown out of `GlycemicGptApp.onCreate` at 198 ms of process age, which the platform
 * turns into "Unable to create application" -- the app's top background crasher. Routing the call
 * through [ForegroundServiceStarter] stopped the crash; not making the call at all is the fix.
 *
 * So application init now only registers and observes ([MonitoringForegroundObserver]), and the
 * decision to start moves here, behind an explicit trigger:
 *
 * - [MonitoringReconcileTrigger.APP_FOREGROUNDED] -- an Activity is visible, which is the
 *   textbook legal context.
 * - [MonitoringReconcileTrigger.BOOT_COMPLETED] -- still exempt for the `connectedDevice` type
 *   [PumpConnectionService] declares (the Android 15 per-type restrictions on `BOOT_COMPLETED`
 *   starts do not cover it).
 * - [MonitoringReconcileTrigger.BACKGROUND] -- no such guarantee, so eligibility is probed first
 *   (process importance, or a battery-optimization exemption) and an ineligible reconcile defers
 *   instead of attempting a start the platform would refuse.
 *
 * Deferring is safe because "should monitoring run" is derived from durable state
 * ([PumpCredentialStore.isPaired]) on every call, not latched anywhere: whatever a deferred
 * reconcile did not do, the next legal trigger does. GLY-254 extends this seam with the periodic
 * WorkManager + AlarmManager backstop that turns "the next legal trigger" from "whenever the user
 * opens the app" into a bounded interval; deliberately not built here.
 *
 * Nothing in here may throw. Its callers are an `ActivityLifecycleCallbacks` and a
 * `BroadcastReceiver`, where an escaping exception is an app crash -- which is the failure mode
 * this whole class exists to remove.
 */
@Singleton
class MonitoringReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pumpCredentialStore: PumpCredentialStore,
    private val backgroundStartEligibility: BackgroundStartEligibility,
) {

    /**
     * Brings pump monitoring in line with what [trigger]'s context allows, and reports why it
     * decided as it did. Safe to call redundantly -- an already-running service short-circuits to
     * [MonitoringReconcileDecision.ALREADY_RUNNING] without re-issuing a start.
     */
    fun reconcile(trigger: MonitoringReconcileTrigger): MonitoringReconcileDecision {
        val outcome = decide(trigger)
        report(trigger, outcome.decision, outcome.cause)
        return outcome.decision
    }

    private fun decide(trigger: MonitoringReconcileTrigger): Outcome {
        val paired = readIsPaired().getOrElse { failure ->
            return Outcome(
                MonitoringReconcileDecision.CREDENTIALS_UNREADABLE,
                failure.javaClass.simpleName,
            )
        }
        if (!paired) return Outcome(MonitoringReconcileDecision.NOT_PAIRED)
        if (isPumpConnectionRunning()) return Outcome(MonitoringReconcileDecision.ALREADY_RUNNING)
        if (!trigger.foregroundStartIsLegal && !backgroundStartEligibility.isEligible()) {
            return Outcome(MonitoringReconcileDecision.DEFERRED_INELIGIBLE)
        }
        // Rejections are caught, classified, durably recorded and surfaced to the user inside
        // PumpConnectionService.start/ForegroundServiceStarter -- this only needs the verdict.
        val result = PumpConnectionService.start(context)
        val decision = when {
            // A refusal with the service demonstrably alive is the tail of the race the
            // isPumpConnectionRunning() check above loses: the service came up between that probe
            // and this start, and the platform refused the redundant start on top of it.
            // ForegroundServiceStarter records nothing for that case for the same reason -- calling
            // it START_REJECTED here would set monitoringOff and warn that monitoring is not
            // running, about a service that is.
            result is ForegroundServiceStartResult.Rejected && result.componentStillRunning ->
                MonitoringReconcileDecision.ALREADY_RUNNING
            result is ForegroundServiceStartResult.Rejected ->
                MonitoringReconcileDecision.START_REJECTED
            else -> MonitoringReconcileDecision.STARTED
        }
        return Outcome(decision)
    }

    /**
     * The credential store is keystore-backed, so a read is not the pure in-memory lookup its
     * signature suggests; an unreadable store must decide against starting rather than propagate
     * out of a receiver.
     *
     * The failure is kept as a failure rather than folded into `false`. "We cannot tell whether a
     * pump is paired" and "no pump is paired" leave the same service not running, but only the
     * first one is wrong: collapsing them reports a paired user's dead monitoring as the routine
     * [MonitoringReconcileDecision.NOT_PAIRED] no-op, at INFO, with no breadcrumb (PR #46 review).
     */
    private fun readIsPaired(): Result<Boolean> = runCatching { pumpCredentialStore.isPaired() }

    /**
     * Reads the live service's own state, the same probe [PumpConnectionService.start] uses to
     * tell a redundant start apart from a real one. An unreadable signal falls back to "not
     * running": that costs at most one redundant start, while the opposite default would skip a
     * start monitoring actually needed.
     *
     * This is also what keeps a system-initiated `START_STICKY` recreation from double-starting.
     * The recreation itself is the platform re-delivering `onStartCommand` to a service it
     * rebuilt, and the service's `started` guard already makes that idempotent; what used to
     * compound it was the process rebuild running `Application.onCreate`, which fired a second,
     * unconditional start. With that gone, a reconcile arriving alongside a sticky restart sees
     * the restarted service here and stops.
     */
    private fun isPumpConnectionRunning(): Boolean =
        runCatching { PumpConnectionService.isRunning() }.getOrDefault(false)

    /**
     * One line per reconcile, carrying the trigger, the decision, and -- only when a decision was
     * forced by a caught exception -- that exception's class name. Two enums and a class name, so
     * there is no device or user data to leak into a telemetry event.
     *
     * Level is chosen by outcome, because [Timber] is the app's telemetry channel:
     * `SentryTimberIntegration` is installed at (ERROR event, WARNING breadcrumb), so a decision
     * that leaves monitoring off when it should be on becomes a breadcrumb attached to whatever
     * the user reports next, while a routine start or a not-paired no-op stays in logcat. Error
     * level is left to [FgsTimeoutReporter.recordForegroundStartRejected], which already raises
     * the real event (class name only) on the rejection path.
     */
    private fun report(
        trigger: MonitoringReconcileTrigger,
        decision: MonitoringReconcileDecision,
        cause: String?,
    ) {
        val suffix = if (cause == null) "" else " cause=$cause"
        if (decision.monitoringOff) {
            Timber.w(
                "%s trigger=%s decision=%s%s -- monitoring is not running",
                RECONCILE_EVENT_TAG, trigger.name, decision.name, suffix,
            )
        } else {
            Timber.i(
                "%s trigger=%s decision=%s%s",
                RECONCILE_EVENT_TAG, trigger.name, decision.name, suffix,
            )
        }
    }

    /** A decision, plus the exception class that forced it when one did. Class name only. */
    private data class Outcome(
        val decision: MonitoringReconcileDecision,
        val cause: String? = null,
    )

    companion object {
        /** Stable prefix on every reconcile report, for logcat greps and Sentry search. */
        const val RECONCILE_EVENT_TAG = "MONITORING_RECONCILE"

        /**
         * The one report a [MonitoringReconciler] cannot make about itself: *constructing* it is
         * what opens the keystore-backed [PumpCredentialStore], so a failure there leaves no
         * instance to report through. Same tag, decision and shape as [report], so one logcat grep
         * or Sentry search covers an unreadable store however it failed.
         */
        fun reportUnavailable(trigger: MonitoringReconcileTrigger, failure: Throwable) {
            Timber.w(
                "%s trigger=%s decision=%s cause=%s -- monitoring is not running",
                RECONCILE_EVENT_TAG,
                trigger.name,
                MonitoringReconcileDecision.CREDENTIALS_UNREADABLE.name,
                failure.javaClass.simpleName,
            )
        }
    }
}

/**
 * Whether the platform would currently accept a foreground-service start from a caller with no
 * exemption of its own. Two ways to qualify: the process is at `IMPORTANCE_FOREGROUND_SERVICE` or
 * better — top, foreground, or already running a foreground service — or the app is allowlisted out
 * of battery optimizations, which carries a standing exemption.
 *
 * The importance threshold is deliberately narrower than the platform's own rule, which also lets
 * some merely-visible processes start a service. `IMPORTANCE_VISIBLE` covers states this app cannot
 * tell apart from the back of the stack through [ActivityManager.getMyMemoryState] alone, and the
 * cost of the two mistakes is not symmetric: a deferred start is retried on the next trigger,
 * whereas a refused one throws [android.app.ForegroundServiceStartNotAllowedException] at a
 * background caller. An app the user is currently interacting with reports `IMPORTANCE_FOREGROUND`,
 * so the case this gives up is the narrower one: visible but not interactive, such as an activity
 * behind another window or a non-focused pane in multi-window. Widen it only with evidence from the
 * platform's actual decision, not from the importance constant's name.
 *
 * Its own class rather than two private helpers on [MonitoringReconciler] because it is the one
 * part of the decision that reads live platform state -- which makes it the part a test has to be
 * able to pin, and the part GLY-254's watchdog will ask the same question of before it schedules
 * versus starts.
 *
 * Both probes fall back to "not eligible" if they fail. Guessing "eligible" here would put the
 * illegal start back, which is the entire bug.
 */
@Singleton
class BackgroundStartEligibility @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun isEligible(): Boolean = isProcessImportanceForeground() || isIgnoringBatteryOptimizations()

    private fun isProcessImportanceForeground(): Boolean = runCatching {
        val state = ActivityManager.RunningAppProcessInfo().apply {
            // The no-arg constructor seeds IMPORTANCE_FOREGROUND, so a struct that came back
            // unfilled would read as the most permissive answer there is. Seed the least
            // permissive one instead and let the platform overwrite it.
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
        }
        // getMyMemoryState, not getRunningAppProcesses: the latter is filtered down to the
        // caller's own process on modern Android anyway, and this variant needs no permission.
        ActivityManager.getMyMemoryState(state)
        state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }.getOrDefault(false)

    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)
}

/**
 * Where a reconcile came from, which is what decides whether it may start a foreground service.
 *
 * [foregroundStartIsLegal] means "this context carries its own exemption", not "this start cannot
 * fail" -- the platform can still refuse for a reason the trigger knows nothing about (an
 * exhausted budget, a revoked permission), which is why every start still goes through
 * [ForegroundServiceStarter].
 */
enum class MonitoringReconcileTrigger(internal val foregroundStartIsLegal: Boolean) {
    /** An Activity reached started state, so the app is visibly in the foreground. */
    APP_FOREGROUNDED(foregroundStartIsLegal = true),

    /**
     * `ACTION_BOOT_COMPLETED`. Android 15 restricted which foreground-service types may be
     * started from this broadcast; `connectedDevice`, the type [PumpConnectionService] declares,
     * is one of the types still allowed.
     */
    BOOT_COMPLETED(foregroundStartIsLegal = true),

    /**
     * Any trigger with no legal-context guarantee -- the periodic watchdog GLY-254 adds is the
     * intended user. Eligibility is probed before a start is attempted.
     */
    BACKGROUND(foregroundStartIsLegal = false),
}

/**
 * What a reconcile concluded. [monitoringOff] marks the ones that leave pump monitoring not
 * running when it should be, which is what makes a reconcile worth reporting as telemetry rather
 * than a debug log.
 */
enum class MonitoringReconcileDecision(internal val monitoringOff: Boolean) {
    /** A start was issued and accepted. */
    STARTED(monitoringOff = false),

    /** The service is already up; nothing to do. */
    ALREADY_RUNNING(monitoringOff = false),

    /** No pump is paired, so there is nothing to monitor. */
    NOT_PAIRED(monitoringOff = false),

    /**
     * The keystore-backed credential store could not be read or built, so pairing state is
     * unknown. Distinct from [NOT_PAIRED] and marked [monitoringOff] deliberately: a paired user
     * whose store stopped opening gets no monitoring at all, and reporting that as "nothing to
     * monitor" at INFO would hide the one deferred outcome worth a breadcrumb.
     */
    CREDENTIALS_UNREADABLE(monitoringOff = true),

    /** A background trigger with no exemption: left for the next legal trigger to pick up. */
    DEFERRED_INELIGIBLE(monitoringOff = true),

    /** The start was attempted from a legal-looking context and the platform refused it anyway. */
    START_REJECTED(monitoringOff = true),
}
