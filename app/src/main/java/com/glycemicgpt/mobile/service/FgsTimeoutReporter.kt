// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records what happens when Android 15 spends the app's `dataSync` foreground-service budget
 * (6 h per 24 h, shared across every dataSync service the app declares).
 *
 * Two jobs, both of which have to be safe to run on the few-second path between
 * [android.app.Service.onTimeout] and `stopSelf()`:
 *
 * 1. **Telemetry, not a debug log.** [Timber] is the app's telemetry channel:
 *    `SentryTimberIntegration` is installed at (ERROR event, WARNING breadcrumb), so an
 *    error-level log here is a real Sentry event. Every message leads with [EVENT_TAG] so the
 *    budget being hit in the field is greppable in logcat and searchable in Sentry.
 * 2. **A durable marker.** Timestamp, FGS type, a running count, and a resume flag per
 *    component, so work that was cut off can be picked up again once it is legal to run
 *    (GLY-245 consumes this for the alert stream).
 *
 * Backed by plain, unencrypted SharedPreferences on purpose. The contents are a timestamp, an
 * int and a counter -- nothing sensitive -- while [com.glycemicgpt.mobile.data.local.AppSettingsStore]
 * builds a keystore-backed `MasterKey` on first touch, which is exactly the kind of unbounded
 * work that must not sit on a shutdown path. The prefs handle is resolved in the constructor
 * (i.e. when Hilt injects this at service `onCreate`), so the timeout path itself never has to
 * open a file.
 */
@Singleton
class FgsTimeoutReporter @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Note that [component] was timed out by the system, and when [resumable] flag its work as
     * waiting to be resumed.
     *
     * Uses `apply()` rather than `commit()`: the write must not block the caller. Nothing is
     * tearing the process down here -- the system is only asking one service to stop -- so the
     * asynchronous flush has time to land.
     */
    fun recordTimeout(
        component: String,
        startId: Int,
        fgsType: Int,
        resumable: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val count = timeoutCount(component) + 1
        prefs.edit()
            .putLong(keyLastTimeoutAtMs(component), nowMs)
            .putInt(keyLastFgsType(component), fgsType)
            .putInt(keyTimeoutCount(component), count)
            .putBoolean(keyResumePending(component), resumable)
            .apply()
        Timber.e(
            "%s component=%s startId=%d fgsType=%d count=%d resumePending=%b -- " +
                "dataSync foreground-service budget exhausted, stopping the service",
            EVENT_TAG, component, startId, fgsType, count, resumable,
        )
    }

    /**
     * The system refused to start or promote [component] to a foreground service -- a background
     * start restriction (API 26+), a `BOOT_COMPLETED` type restriction (Android 15), the exhausted
     * `dataSync` budget (Android 15), or a missing permission. Reported at error level for the same
     * reason as a timeout: it means the app is running without the protection it thinks it has.
     *
     * Deliberately its own key space, distinct from [recordTimeout]'s
     * ([keyLastTimeoutAtMs]/[keyTimeoutCount]/[keyResumePending]): a start rejection and a mid-run
     * timeout are different events with different resume implications (GLY-246 review F4) --
     * merging them let [lastTimeoutAtMs] read non-zero while [timeoutCount] stayed zero, a
     * combination [recordTimeout] alone can never produce. [isStartRejectedPending] is cleared by
     * [clearStartRejectedPending] on that component's next successful promotion (see
     * [ForegroundServiceStarter]), so it cannot latch on forever the way sharing
     * [keyResumePending] did.
     *
     * `Timber.e` takes the exception class and a classified [ForegroundStartRejectionReason], not
     * the throwable -- same discipline as
     * [com.glycemicgpt.mobile.service.PumpPollingOrchestrator]'s exception logging (GLY-249): an
     * ERROR-level Timber call is promoted to a real Sentry event, and the raw message can carry
     * data (e.g. permission details) that does not belong in a telemetry event. The full
     * throwable still goes to DEBUG for local troubleshooting. Uses [START_REJECTED_EVENT_TAG],
     * not [EVENT_TAG]: [EVENT_TAG] documents the `dataSync` budget specifically, and a
     * `connectedDevice` rejection (e.g. [COMPONENT_PUMP_CONNECTION]) filed under it would
     * mis-attribute the cause during an incident (GLY-246 review F7).
     */
    fun recordForegroundStartRejected(
        component: String,
        error: Throwable,
        nowMs: Long = System.currentTimeMillis(),
    ): ForegroundStartRejectionReason {
        val reason = ForegroundStartRejectionReason.classify(error)
        prefs.edit()
            .putLong(keyLastStartRejectedAtMs(component), nowMs)
            .putBoolean(keyStartRejectedPending(component), true)
            .apply()
        Timber.e(
            "%s component=%s exceptionType=%s reason=%s -- foreground start refused by the system",
            START_REJECTED_EVENT_TAG, component, error.javaClass.simpleName, reason.name,
        )
        Timber.d(error, "%s detail for component=%s", START_REJECTED_EVENT_TAG, component)
        return reason
    }

    /** Wall-clock ms of the last recorded start rejection for [component], or 0 if none. */
    fun lastStartRejectedAtMs(component: String): Long =
        prefs.getLong(keyLastStartRejectedAtMs(component), 0L)

    /** True while [component]'s last foreground-start attempt was rejected and never resumed. */
    fun isStartRejectedPending(component: String): Boolean =
        prefs.getBoolean(keyStartRejectedPending(component), false)

    /**
     * Called once [component] successfully promotes to foreground again. Every component clears
     * its own marker this way (see [ForegroundServiceStarter.promote]) -- not just the alert
     * stream, which was the only caller before GLY-246 review F4.
     */
    fun clearStartRejectedPending(component: String) {
        if (!isStartRejectedPending(component)) return
        prefs.edit().putBoolean(keyStartRejectedPending(component), false).apply()
        Timber.d("%s promoted to foreground; cleared the start-rejected marker", component)
    }

    /** Wall-clock ms of the last recorded timeout for [component], or 0 if it never timed out. */
    fun lastTimeoutAtMs(component: String): Long =
        prefs.getLong(keyLastTimeoutAtMs(component), 0L)

    /** The `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` value the last timeout arrived with. */
    fun lastFgsType(component: String): Int = prefs.getInt(keyLastFgsType(component), 0)

    /** How many times [component] has been timed out, across process lifetimes. */
    fun timeoutCount(component: String): Int = prefs.getInt(keyTimeoutCount(component), 0)

    /** True while [component] has work that was cut short by a timeout and never resumed. */
    fun isResumePending(component: String): Boolean =
        prefs.getBoolean(keyResumePending(component), false)

    /** Called once [component] is doing its work again; leaves the timeout history intact. */
    fun clearResumePending(component: String) {
        if (!isResumePending(component)) return
        prefs.edit().putBoolean(keyResumePending(component), false).apply()
        Timber.d("%s resumed after an FGS timeout; cleared the resume marker", component)
    }

    private fun keyLastTimeoutAtMs(component: String) = "${component}_last_timeout_at_ms"

    private fun keyLastFgsType(component: String) = "${component}_last_fgs_type"

    private fun keyTimeoutCount(component: String) = "${component}_timeout_count"

    private fun keyResumePending(component: String) = "${component}_resume_pending"

    private fun keyLastStartRejectedAtMs(component: String) = "${component}_start_rejected_at_ms"

    private fun keyStartRejectedPending(component: String) = "${component}_start_rejected_pending"

    companion object {
        /** Stable prefix on every mid-run `dataSync` timeout report, for logcat greps and Sentry search. */
        const val EVENT_TAG = "FGS_DATASYNC_TIMEOUT"

        /**
         * Stable prefix on every start-rejection report. Separate from [EVENT_TAG]: a rejection
         * can hit a `connectedDevice` service too, which never touches the `dataSync` budget
         * [EVENT_TAG] documents (GLY-246 review F7).
         */
        const val START_REJECTED_EVENT_TAG = "FGS_START_REJECTED"

        const val COMPONENT_ALERT_STREAM = "alert_stream"
        const val COMPONENT_WEAR_CHAT_RELAY = "wear_chat_relay"
        const val COMPONENT_PUMP_CONNECTION = "pump_connection"

        private const val PREFS_NAME = "fgs_timeout"
    }
}

/**
 * The platform cause behind a rejected foreground-service start (GLY-246 review F3). All three
 * causes the Linear issue names -- a background-start restriction (API 26+), a `BOOT_COMPLETED`
 * type restriction (Android 15), and the exhausted `dataSync` budget (Android 15) -- throw the
 * identical [ForegroundServiceStartNotAllowedException][java.lang.IllegalStateException] (an
 * `IllegalStateException` subclass), so the exception's class name alone cannot tell them apart;
 * only its message text can. Classification is deliberately best-effort text matching against the
 * platform's (undocumented, not API-contracted) message strings, not an exhaustive parse -- an
 * unrecognized message falls back to [OTHER] rather than guessing.
 */
enum class ForegroundStartRejectionReason {
    BUDGET_EXHAUSTED,
    BOOT_TYPE_RESTRICTION,
    BACKGROUND_START_RESTRICTION,
    PERMISSION_DENIED,
    OTHER,
    ;

    companion object {
        fun classify(error: Throwable): ForegroundStartRejectionReason {
            if (error is SecurityException) return PERMISSION_DENIED
            val message = error.message.orEmpty()
            return when {
                message.contains("time limit", ignoreCase = true) ||
                    message.contains("budget", ignoreCase = true) -> BUDGET_EXHAUSTED
                message.contains("BOOT_COMPLETED", ignoreCase = true) -> BOOT_TYPE_RESTRICTION
                message.contains("not allowed due to", ignoreCase = true) ||
                    message.contains("background", ignoreCase = true) -> BACKGROUND_START_RESTRICTION
                else -> OTHER
            }
        }
    }
}
