package com.glycemicgpt.mobile

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.glycemicgpt.mobile.data.auth.AuthManager
import com.glycemicgpt.mobile.data.network.NetworkMonitor
import com.glycemicgpt.mobile.data.network.NetworkStatus
import com.glycemicgpt.mobile.data.repository.AlertRepository
import com.glycemicgpt.mobile.logging.ReleaseTree
import com.glycemicgpt.mobile.logging.SentryInitializer
import com.glycemicgpt.mobile.plugin.PluginRegistry
import com.glycemicgpt.mobile.service.DataRetentionWorker
import com.glycemicgpt.mobile.service.MonitoringForegroundObserver
import com.glycemicgpt.mobile.service.MonitoringReconciler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class GlycemicGptApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * A [Provider], not the singleton: nothing in application init needs a reconciler, and
     * resolving one builds the keystore-backed credential store it reads. A worker run, an
     * inbound wear message or a notification action never foregrounds an Activity, so those
     * process starts never resolve it at all.
     */
    @Inject
    lateinit var monitoringReconciler: Provider<MonitoringReconciler>

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var pluginRegistry: PluginRegistry

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var alertRepository: AlertRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Plant Timber first so SentryInitializer's own diagnostics (including an init-failure
        // log) are not silently dropped, then bring Sentry up before the rest of startup so those
        // failures are captured. Sentry is a no-op when no DSN is compiled in (release builds, or
        // debug without one).
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        SentryInitializer.init(this)
        scheduleDataRetention()

        // Start observing device connectivity so the home screen can tell "offline" apart from
        // "backend unreachable". Backend reachability itself is fed by the OkHttp path.
        networkMonitor.start()

        // Drain alert acknowledgements made while the backend was unreachable (GLY-130): every
        // transition to REACHABLE — including the initial value on cold start, which is what
        // reconciles acks that were pending when the process died — pushes locally-acknowledged,
        // unsynced alerts to the server so they stop re-firing and re-escalating. StateFlow
        // already conflates equal consecutive values, so each collected status is a transition.
        appScope.launch {
            networkMonitor.status.collect { status ->
                if (status == NetworkStatus.REACHABLE) {
                    try {
                        alertRepository.reconcilePendingAcks()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The reconcile is defensive and shouldn't throw, but a failure here
                        // must never kill this app-lifetime collector (or the process).
                        Timber.e(e, "Alert ack reconcile failed")
                    }
                }
            }
        }

        // Initialize the plugin system (discovers and creates all registered plugins)
        pluginRegistry.initialize()

        // Validate auth tokens on startup and schedule proactive refresh
        authManager.validateOnStartup(appScope)

        // Observe, never start. This runs on every process creation, including the ones nobody
        // asked for -- a periodic worker, an inbound wear message, a notification action -- where
        // the app is in the background and the platform rejects a foreground-service start with a
        // ForegroundServiceStartNotAllowedException, which becomes an "Unable to create
        // application" crash. Registering here and reconciling once an Activity is actually
        // visible resumes polling and auto-reconnect on app open with no start attempted from a
        // context that cannot legally make one (see MonitoringReconciler).
        registerActivityLifecycleCallbacks(MonitoringForegroundObserver(monitoringReconciler))
    }

    private fun scheduleDataRetention() {
        val request = PeriodicWorkRequestBuilder<DataRetentionWorker>(
            1, TimeUnit.DAYS,
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DataRetentionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
