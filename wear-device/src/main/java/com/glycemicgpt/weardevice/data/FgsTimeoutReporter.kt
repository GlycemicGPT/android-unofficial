// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.weardevice.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import timber.log.Timber

/**
 * Watch-side twin of the phone's `FgsTimeoutReporter`: records when Android 15 spends the
 * app's `dataSync` foreground-service budget out from under a receive service.
 *
 * Object rather than an injected singleton because the two services that need it
 * ([com.glycemicgpt.weardevice.push.WatchFaceReceiveService] and
 * [com.glycemicgpt.weardevice.update.WatchApkReceiveService]) are plain
 * `WearableListenerService`s with no Hilt entry point -- the same reason
 * [WatchDataRepository] is an object.
 *
 * The durable record is the point here. `:wear-device` has no Sentry and plants no Timber tree
 * in release builds, so an error-level log on the watch is only visible over adb; the persisted
 * counter is what still exists after the fact. Relaying watch timeouts to the phone's Sentry
 * over the Data Layer is deliberately out of scope for GLY-244.
 */
object FgsTimeoutReporter {

    /** Stable prefix on every timeout report, matching the phone's tag for cross-device greps. */
    const val EVENT_TAG = "FGS_DATASYNC_TIMEOUT"

    const val COMPONENT_WATCH_FACE_RECEIVE = "watch_face_receive"
    const val COMPONENT_WATCH_APK_RECEIVE = "watch_apk_receive"

    private const val PREFS_NAME = "fgs_timeout"

    private var prefs: SharedPreferences? = null

    /**
     * Open the backing store. Called from each service's `onCreate` so that the timeout path,
     * which has only a few seconds before the system kills the process, never has to read a
     * prefs file from disk.
     */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Note that the system timed [component] out. Uses `apply()`, never `commit()`: no disk
     * write may block the path to `stopSelf()`.
     */
    fun recordTimeout(
        component: String,
        startId: Int,
        fgsType: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val store = prefs
        val count = timeoutCount(component) + 1
        if (store == null) {
            Timber.w("FGS timeout store not initialized; %s timeout not persisted", component)
        } else {
            store.edit()
                .putLong(keyLastTimeoutAtMs(component), nowMs)
                .putInt(keyLastFgsType(component), fgsType)
                .putInt(keyTimeoutCount(component), count)
                .apply()
        }
        Timber.e(
            "%s component=%s startId=%d fgsType=%d count=%d -- " +
                "dataSync foreground-service budget exhausted, stopping the service",
            EVENT_TAG, component, startId, fgsType, count,
        )
    }

    /**
     * The system refused to promote [component] to a foreground service (GLY-246). Same durable-
     * record discipline as [recordTimeout], but its own key space: a start rejection and a
     * mid-run timeout are different events, so nothing here reuses [keyLastTimeoutAtMs] /
     * [keyTimeoutCount] (matching the phone twin's fix for GLY-246 review F4, applied here so
     * the same "timed-out-mid-run vs refused-at-start" ambiguity is never introduced on the
     * watch side either).
     */
    fun recordForegroundStartRejected(
        component: String,
        error: Throwable,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val store = prefs
        val count = startRejectedCount(component) + 1
        if (store == null) {
            Timber.w("FGS start-rejected store not initialized; %s rejection not persisted", component)
        } else {
            store.edit()
                .putLong(keyLastStartRejectedAtMs(component), nowMs)
                .putInt(keyStartRejectedCount(component), count)
                .apply()
        }
        Timber.e(
            "%s component=%s exceptionType=%s count=%d -- foreground start refused by the system",
            START_REJECTED_EVENT_TAG, component, error.javaClass.simpleName, count,
        )
    }

    /** Wall-clock ms of the last recorded start rejection for [component], or 0 if none. */
    fun lastStartRejectedAtMs(component: String): Long =
        prefs?.getLong(keyLastStartRejectedAtMs(component), 0L) ?: 0L

    /** How many times [component]'s foreground start has been rejected, across process lifetimes. */
    fun startRejectedCount(component: String): Int =
        prefs?.getInt(keyStartRejectedCount(component), 0) ?: 0

    /** Wall-clock ms of the last recorded timeout for [component], or 0 if it never timed out. */
    fun lastTimeoutAtMs(component: String): Long =
        prefs?.getLong(keyLastTimeoutAtMs(component), 0L) ?: 0L

    /** The `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` value the last timeout arrived with. */
    fun lastFgsType(component: String): Int = prefs?.getInt(keyLastFgsType(component), 0) ?: 0

    /** How many times [component] has been timed out, across process lifetimes. */
    fun timeoutCount(component: String): Int = prefs?.getInt(keyTimeoutCount(component), 0) ?: 0

    /** Test seam: production code only ever binds the store through [init]. */
    @VisibleForTesting
    @Synchronized
    internal fun setStoreForTest(store: SharedPreferences?) {
        prefs = store
    }

    private fun keyLastTimeoutAtMs(component: String) = "${component}_last_timeout_at_ms"

    private fun keyLastFgsType(component: String) = "${component}_last_fgs_type"

    private fun keyTimeoutCount(component: String) = "${component}_timeout_count"

    private fun keyLastStartRejectedAtMs(component: String) = "${component}_start_rejected_at_ms"

    private fun keyStartRejectedCount(component: String) = "${component}_start_rejected_count"

    /**
     * Stable prefix on every start-rejection report, distinct from [EVENT_TAG] (which documents
     * the `dataSync` budget specifically -- GLY-246 review F7's phone-side fix, applied here too).
     */
    const val START_REJECTED_EVENT_TAG = "FGS_START_REJECTED"
}
