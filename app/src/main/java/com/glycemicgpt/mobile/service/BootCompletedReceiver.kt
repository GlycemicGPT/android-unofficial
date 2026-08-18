package com.glycemicgpt.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.glycemicgpt.mobile.data.local.AuthTokenStore
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Restarts background services after device reboot:
 * - [PumpConnectionService], via [MonitoringReconciler], if a pump is paired
 * - [AlertStreamService] if the user is logged in (has a refresh token)
 *
 * One of the two legal contexts the reconciler starts monitoring from -- the other is an Activity
 * becoming visible. `Application.onCreate` used to cover cold app starts by starting the service
 * outright; it no longer starts anything, because on a background process creation that start is
 * illegal and crashes the app (see [MonitoringReconciler]).
 *
 * Uses [goAsync] to extend the broadcast window beyond the default 10-second
 * ANR limit, since Hilt injection may trigger Application.onCreate().
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var monitoringReconciler: MonitoringReconciler

    @Inject
    lateinit var authTokenStore: AuthTokenStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val pendingResult = goAsync()
        try {
            restartMonitoring(context)
        } finally {
            pendingResult.finish()
        }
    }

    /**
     * The receiver's actual work, split out from [onReceive]'s broadcast bookkeeping so it can be
     * exercised without the `goAsync` window or Hilt's injection hook (same idiom as
     * [AlertActionReceiver.handleAcknowledge]).
     */
    @VisibleForTesting
    internal fun restartMonitoring(context: Context) {
        // The reconciler owns the paired check and reports its own decision, so there is nothing
        // to log here that it does not already say with the trigger attached.
        monitoringReconciler.reconcile(MonitoringReconcileTrigger.BOOT_COMPLETED)

        // Start AlertStreamService if the user is logged in so alerts resume after reboot.
        // AlertStreamService.start already routes through ForegroundServiceStarter, which
        // catches and records a platform rejection instead of throwing -- nothing left to
        // catch here.
        if (authTokenStore.getRefreshToken() != null) {
            Timber.d("Boot completed, starting AlertStreamService (user is logged in)")
            AlertStreamService.start(context)
        } else {
            Timber.d("Boot completed, user not logged in -- skipping AlertStreamService")
        }
    }
}
