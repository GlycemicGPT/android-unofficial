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
     * The system refused to promote [component] to a `dataSync` foreground service, which on
     * Android 15 is what happens to every start attempt after the budget is spent. Reported at
     * error level for the same reason as a timeout: it means the app is running without the
     * protection it thinks it has.
     */
    fun recordForegroundStartRejected(component: String, error: Throwable) {
        Timber.e(
            error,
            "%s_START_REJECTED component=%s -- dataSync foreground start refused by the system",
            EVENT_TAG, component,
        )
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

    companion object {
        /** Stable prefix on every timeout report, for logcat greps and Sentry search. */
        const val EVENT_TAG = "FGS_DATASYNC_TIMEOUT"

        const val COMPONENT_ALERT_STREAM = "alert_stream"
        const val COMPONENT_WEAR_CHAT_RELAY = "wear_chat_relay"

        private const val PREFS_NAME = "fgs_timeout"
    }
}
