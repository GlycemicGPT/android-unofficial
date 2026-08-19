// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.Activity
import android.app.Application
import android.os.Bundle
import javax.inject.Provider

/**
 * Turns "the app became visible" into a [MonitoringReconciler] call, so pump monitoring resumes
 * on app open without `GlycemicGptApp.onCreate` ever attempting a foreground-service start.
 *
 * Registered from application init, which is all application init is allowed to do now: an
 * `ActivityLifecycleCallbacks` registration costs nothing on the background process starts that
 * used to crash here (a worker run, an inbound wear message, a notification action) because no
 * Activity ever starts in them, so the reconcile simply never fires.
 *
 * [reconciler] is a [Provider] for the same reason: resolving it constructs the keystore-backed
 * [com.glycemicgpt.mobile.data.local.PumpCredentialStore], and a background process start should
 * not pay for -- or risk -- work it will never use. The first foregrounding resolves it once and
 * Hilt caches the singleton from there.
 *
 * Counting started Activities rather than reacting to every `onActivityStarted` keeps
 * screen-to-screen navigation (B starts before A stops, so the count never returns to zero) from
 * re-reconciling. A configuration change does briefly empty the count and re-fire; that costs one
 * extra reconcile, which is two in-memory reads when the service is already up.
 *
 * All callbacks arrive on the main thread, so the counter needs no synchronization.
 */
class MonitoringForegroundObserver(
    private val reconciler: Provider<MonitoringReconciler>,
) : Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0) {
            // Resolving the Provider is what *constructs* PumpCredentialStore, whose init calls
            // MasterKeys.getOrCreate and EncryptedSharedPreferences.create -- both throw on a
            // keystore or corrupted-prefs failure, and both sit outside every runCatching
            // MonitoringReconciler puts around its own reads. An escape from here escapes an
            // ActivityLifecycleCallbacks callback, which is an app crash on every single app open:
            // the failure mode this class exists to remove, not one it may reintroduce. Skipping
            // the reconcile leaves monitoring off, so the skip is reported the way the reconciler
            // reports its own monitoring-off decisions.
            runCatching {
                reconciler.get().reconcile(MonitoringReconcileTrigger.APP_FOREGROUNDED)
            }.onFailure {
                MonitoringReconciler.reportUnavailable(
                    MonitoringReconcileTrigger.APP_FOREGROUNDED,
                    it,
                )
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities > 0) startedActivities--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
