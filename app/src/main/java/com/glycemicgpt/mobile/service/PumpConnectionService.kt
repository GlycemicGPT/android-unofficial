package com.glycemicgpt.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.glycemicgpt.mobile.R
import com.glycemicgpt.mobile.data.local.AuthTokenStore
import com.glycemicgpt.mobile.domain.alerting.AlertFloorStatus
import com.glycemicgpt.mobile.domain.model.ConnectionState
import com.glycemicgpt.mobile.domain.pump.PumpConnectionManager
import com.glycemicgpt.mobile.wear.WearMonitoringStatusForwarder
import dagger.hilt.android.AndroidEntryPoint
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that maintains persistent BLE connection to the pump
 * and orchestrates periodic data polling.
 *
 * Holds a PARTIAL_WAKE_LOCK while the pump is connected to prevent the CPU
 * from sleeping and missing the 15-second keep-alive polls (the pump drops
 * idle BLE connections after ~30 seconds).
 *
 * The wake lock uses a 20-minute safety timeout as a leak guard, and a
 * renewal loop re-acquires it every 15 minutes while connected.
 *
 * Polling pauses when BLE connection is lost and resumes on reconnect.
 * Reduces poll frequency when phone battery drops below 15%.
 * Releases wake lock when phone battery drops below 5% (critical).
 */
@AndroidEntryPoint
class PumpConnectionService : Service() {

    companion object {
        const val CHANNEL_ID = "pump_connection"
        const val NOTIFICATION_ID = 1
        private const val LOW_BATTERY_THRESHOLD = 15
        private const val CRITICAL_BATTERY_THRESHOLD = 5
        private const val WAKE_LOCK_TAG = "GlycemicGPT:PumpBleConnection"
        private const val WAKE_LOCK_TIMEOUT_MS = 20L * 60 * 1000 // 20-minute safety timeout
        private const val WAKE_LOCK_RENEW_MS = 15L * 60 * 1000 // renew every 15 minutes
        // Shorter wake lock for reconnection: covers max 32s backoff + GATT + JPAKE auth
        private const val RECONNECT_WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 1000 // 2 minutes

        /**
         * Liveness probe the running instance publishes for [start], mirroring the `started` gate
         * the in-service survive-branch in [onStartCommand] already uses (PR #44 review). A
         * rejected *redundant* start -- Settings reopened, a re-login refresh, app `onCreate` --
         * never reaches `onStartCommand`, so without this the companion would warn that pump
         * monitoring is off while the BLE link is live and nothing would ever take the warning
         * back. Holding the instance's state behind a lambda in a companion is a leak only if
         * [onDestroy] never runs, and the reset there is the same reset the field itself gets;
         * process death takes both.
         */
        @VisibleForTesting
        @Volatile
        internal var isRunning: () -> Boolean = { false }

        fun start(context: Context): ForegroundServiceStartResult {
            val result = ForegroundServiceStarter.start(
                context,
                Intent(context, PumpConnectionService::class.java),
                FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
                isRunning,
            )
            if (result is ForegroundServiceStartResult.Rejected && !result.componentStillRunning) {
                // The service was never created, so nothing else will tell the user pump
                // monitoring is off -- most likely on the boot path, with no app UI open to
                // eventually notice via AlertFloorStatusProvider. GLY-254 owns the full
                // monitoring-health surface and will supersede this notification.
                // runCatching: this runs on the boot path, where BootCompletedReceiver has no
                // catch of its own left -- a throw here must not escape this companion.
                runCatching {
                    MonitoringDegradedNotifier.notify(
                        context.applicationContext ?: context,
                        FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
                    )
                }
            }
            return result
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PumpConnectionService::class.java))
        }
    }

    @Inject
    lateinit var pollingOrchestrator: PumpPollingOrchestrator

    @Inject
    lateinit var backendSyncManager: BackendSyncManager

    @Inject
    lateinit var connectionManager: PumpConnectionManager

    @Inject
    lateinit var alertFloorStatusProvider: AlertFloorStatusProvider

    @Inject
    lateinit var wearMonitoringStatusForwarder: WearMonitoringStatusForwarder

    @Inject
    lateinit var authTokenStore: AuthTokenStore

    @Inject
    lateinit var fgsTimeoutReporter: FgsTimeoutReporter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Last floor status emitted by the shared provider. Read by [onStartCommand]'s rebuild of
     * the foreground notification: redundant service starts (every Settings open while paired)
     * must not clobber an honest "NOT watching" text with the default copy — the provider's
     * distinctUntilChanged stream would not re-emit an unchanged status to repair it.
     */
    @Volatile
    private var lastFloorStatus: AlertFloorStatus = AlertFloorStatus.ServerActive

    /** Backend-configured mode signal, cached for [buildNotification]: onStartCommand runs on
     *  the main thread and a live EncryptedSharedPreferences read could block on first load.
     *  Seeded off the main thread in [onCreate] and refreshed with every floor-status emission.
     *  The pessimistic-false default only affects which honest "watching" phrasing shows. */
    @Volatile
    private var backendConfigured = false
    private var batteryReceiverRegistered = false
    private var bluetoothReceiverRegistered = false
    private val wakeLockSync = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionWatcherJob: Job? = null
    private var wakeLockRenewalJob: Job? = null
    private var floorStatusWatcherJob: Job? = null
    private var wearStatusForwarderJob: Job? = null

    /** Test seam: lets a rejected-redundant-repromote test set up an already-running service
     *  without driving the full startup path. */
    @VisibleForTesting
    @Volatile
    internal var started = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Timber.d("Bluetooth turned ON, triggering reconnect if paired")
                    connectionManager.autoReconnectIfPaired()
                }
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    Timber.d("Bluetooth turning OFF")
                    // GATT callbacks will handle the disconnection
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                val wasLow = pollingOrchestrator.phoneBatteryLow
                pollingOrchestrator.phoneBatteryLow = pct < LOW_BATTERY_THRESHOLD

                // Critical battery: release wake lock to preserve phone battery
                if (pct < CRITICAL_BATTERY_THRESHOLD) {
                    synchronized(wakeLockSync) {
                        if (wakeLock?.isHeld == true) {
                            releaseWakeLockLocked()
                            Timber.w(
                                "Phone battery critical (%d%%), wake lock released to preserve battery",
                                pct,
                            )
                        }
                    }
                } else {
                    synchronized(wakeLockSync) {
                        if (wakeLock?.isHeld != true) {
                            val currentState = connectionManager.connectionState.value
                            when (currentState) {
                                ConnectionState.CONNECTED -> {
                                    acquireWakeLockLocked()
                                    Timber.d("Phone battery recovered (%d%%), wake lock re-acquired", pct)
                                }
                                ConnectionState.RECONNECTING,
                                ConnectionState.CONNECTING,
                                ConnectionState.AUTHENTICATING -> {
                                    acquireReconnectWakeLockLocked()
                                    Timber.d("Phone battery recovered (%d%%), reconnect wake lock re-acquired", pct)
                                }
                                else -> { /* No wake lock needed */ }
                            }
                        }
                    }
                }

                if (wasLow != pollingOrchestrator.phoneBatteryLow) {
                    Timber.d(
                        "Phone battery %d%% - polling %s",
                        pct,
                        if (pollingOrchestrator.phoneBatteryLow) "reduced" else "normal",
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Seed from the provider's pessimistic snapshot: a cold start into an existing outage
        // (post-reboot) must not render the optimistic default while the watcher's first async
        // emission is still in flight.
        lastFloorStatus = alertFloorStatusProvider.current()
        serviceScope.launch { backendConfigured = authTokenStore.isBackendConfigured() }
        isRunning = { started }
        Timber.d("PumpConnectionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Rebuild from the cached status, never the default: a redundant start during an outage
        // must not replace the honest "NOT watching" text with all-is-well copy.
        val notification = buildNotification(lastFloorStatus)
        val result = ForegroundServiceStarter.promote(
            this,
            NOTIFICATION_ID,
            notification,
            FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION,
            fgsTimeoutReporter,
        )
        if (result is ForegroundServiceStartResult.Rejected) {
            if (started) {
                // A redundant re-promote (e.g. Settings reopened while already connected) was
                // rejected -- the BLE link and polling are already live. Tearing this down would
                // destroy a working connection over a rejection that only hit the *notification*
                // re-promotion, not the work already underway (GLY-246 review F6). The service is
                // demonstrably running under foreground protection already, so clear the marker
                // this rejection just set -- otherwise GLY-254 would read a healthy component as
                // still owing a resume (GLY-246 review F6 residual).
                fgsTimeoutReporter.clearStartRejectedPending(FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION)
                // Same reasoning for the notification an earlier rejection may have posted: pump
                // monitoring is running, so the "monitoring not running" warning is now a lie
                // (PR #44 review).
                runCatching {
                    MonitoringDegradedNotifier.clear(this, FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION)
                }
                Timber.w(
                    "PumpConnectionService redundant re-promote rejected (%s); already running, continuing",
                    result.exceptionType,
                )
            } else {
                // The BLE link and polling only matter behind a live foreground promotion --
                // without one the system can kill this process at any time. Stop cleanly instead
                // of running unprotected; the rejection is already durably recorded for GLY-254
                // to resume from.
                Timber.w(
                    "PumpConnectionService foreground start rejected (%s); stopping",
                    result.exceptionType,
                )
                // GLY-254 owns the full monitoring-health surface and will supersede this
                // notification.
                runCatching {
                    MonitoringDegradedNotifier.notify(this, FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION)
                }
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        // Guard: only start orchestrators and watchers once per service lifecycle.
        // onStartCommand may be called multiple times (re-delivery, duplicate starts).
        if (!started) {
            started = true
            pollingOrchestrator.backendSyncManager = backendSyncManager
            pollingOrchestrator.start(serviceScope)
            backendSyncManager.start(serviceScope)
            connectionManager.autoReconnectIfPaired()

            // Watch connection state to acquire/release wake lock.
            // Hold wake lock during reconnection to keep CPU awake for the
            // backoff delay coroutine and GATT/JPAKE handshake.
            connectionWatcherJob = serviceScope.launch {
                connectionManager.connectionState.collect { state ->
                    synchronized(wakeLockSync) {
                        when (state) {
                            ConnectionState.CONNECTED -> {
                                acquireWakeLockLocked()
                                startWakeLockRenewalLocked()
                            }
                            ConnectionState.RECONNECTING,
                            ConnectionState.CONNECTING,
                            ConnectionState.AUTHENTICATING -> {
                                stopWakeLockRenewalLocked()
                                acquireReconnectWakeLockLocked()
                            }
                            else -> {
                                stopWakeLockRenewalLocked()
                                releaseWakeLockLocked()
                            }
                        }
                    }
                }
            }

            // The backgrounded half of the honest alerting surface (GLY-115 AC7): while the app
            // is not open, this foreground notification is the only place that can say whether
            // anything is watching for lows/highs. Mutate it to "watching"/"NOT watching" while
            // server alerting is degraded and revert when the server reconnects. Status text
            // only — always the silent low-importance channel, never an alarm. The pipeline is
            // the same shared provider the in-app banner uses, so the two claims cannot disagree.
            floorStatusWatcherJob = serviceScope.launch {
                try {
                    alertFloorStatusProvider.observe().collect { status ->
                        backendConfigured = authTokenStore.isBackendConfigured()
                        lastFloorStatus = status
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(status))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A status-only surface must never take down the pump service (the BLE
                    // link and polling matter more than the notification text). The provider
                    // already retries upstream faults internally; this guards the notify path.
                    Timber.e(e, "Floor status watcher failed; foreground text frozen at last value")
                }
            }

            // Mirror the same coverage stream to the wrist (GLY-116 axis a): the watch renders
            // the phone's decision and locally decays it — it never re-derives coverage.
            wearStatusForwarderJob = wearMonitoringStatusForwarder.start(serviceScope)

            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            batteryReceiverRegistered = true

            ContextCompat.registerReceiver(
                this,
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            bluetoothReceiverRegistered = true
        }

        Timber.d("PumpConnectionService started in foreground with polling")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingOrchestrator.stop()
        backendSyncManager.stop()
        connectionWatcherJob?.cancel()
        connectionWatcherJob = null
        floorStatusWatcherJob?.cancel()
        floorStatusWatcherJob = null
        wearStatusForwarderJob?.cancel()
        wearStatusForwarderJob = null
        synchronized(wakeLockSync) {
            stopWakeLockRenewalLocked()
            releaseWakeLockLocked()
        }
        if (batteryReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered
            }
            batteryReceiverRegistered = false
        }
        if (bluetoothReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothStateReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered
            }
            bluetoothReceiverRegistered = false
        }
        started = false
        isRunning = { false }
        serviceScope.cancel()
        Timber.d("PumpConnectionService destroyed")
        super.onDestroy()
    }

    /** Must be called under [wakeLockSync]. */
    private fun acquireWakeLockLocked() {
        val existing = wakeLock
        if (existing != null) {
            // Re-acquire existing lock with fresh timeout (cheaper than creating a new one)
            existing.acquire(WAKE_LOCK_TIMEOUT_MS)
        } else {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        Timber.d("Wake lock acquired for BLE connection")
    }

    /**
     * Short-lived wake lock for reconnection attempts. Uses a 2-minute timeout:
     * long enough for the max 32-second backoff plus GATT connection + JPAKE auth
     * handshake, but short enough to limit battery drain if reconnection fails.
     * Must be called under [wakeLockSync].
     */
    private fun acquireReconnectWakeLockLocked() {
        val existing = wakeLock
        if (existing != null) {
            existing.acquire(RECONNECT_WAKE_LOCK_TIMEOUT_MS)
        } else {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(RECONNECT_WAKE_LOCK_TIMEOUT_MS)
            }
        }
        Timber.d("Reconnect wake lock acquired (timeout=%d ms)", RECONNECT_WAKE_LOCK_TIMEOUT_MS)
    }

    /** Must be called under [wakeLockSync]. */
    private fun releaseWakeLockLocked() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.d("Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Starts a coroutine that periodically re-acquires the wake lock before the
     * safety timeout expires. This ensures continuous CPU wakefulness during
     * overnight BLE monitoring. Must be called under [wakeLockSync].
     */
    private fun startWakeLockRenewalLocked() {
        if (wakeLockRenewalJob?.isActive == true) return
        wakeLockRenewalJob = serviceScope.launch {
            while (true) {
                delay(WAKE_LOCK_RENEW_MS)
                synchronized(wakeLockSync) {
                    if (connectionManager.connectionState.value == ConnectionState.CONNECTED) {
                        acquireWakeLockLocked()
                        Timber.d("Wake lock renewed")
                    }
                }
            }
        }
    }

    /** Must be called under [wakeLockSync]. */
    private fun stopWakeLockRenewalLocked() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pump Connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Maintains connection to insulin pump"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(floorStatus: AlertFloorStatus): Notification {
        val contentText = when (floorStatus) {
            AlertFloorStatus.ServerActive -> getString(R.string.pump_service_notification_text)
            // "Server alerts paused" is only honest when a server exists; in BLE-only mode the
            // floor is the only alerting there is, so the copy must not imply one (GLY-145).
            AlertFloorStatus.FloorWatching -> if (backendConfigured) {
                getString(R.string.pump_service_floor_watching_text)
            } else {
                getString(R.string.pump_service_floor_watching_no_server_text)
            }
            is AlertFloorStatus.FloorNotWatching ->
                getString(R.string.pump_service_floor_not_watching_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.pump_service_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
