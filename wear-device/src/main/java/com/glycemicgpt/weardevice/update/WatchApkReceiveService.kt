package com.glycemicgpt.weardevice.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.glycemicgpt.weardevice.data.FgsTimeoutReporter
import com.glycemicgpt.weardevice.data.ForegroundServiceStarter
import com.glycemicgpt.weardevice.data.WearDataContract
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives wear APK bytes from the phone app via ChannelClient and installs
 * using the PackageInstaller session API for self-update.
 *
 * Flow:
 * 1. Phone opens ChannelClient with path /glycemicgpt/watch/apk/push
 * 2. Phone streams wear APK bytes through the channel
 * 3. This service receives channel open, reads bytes to a temp file
 * 4. Validates APK magic bytes and checks install permission
 * 5. Creates PackageInstaller session and commits the APK
 * 6. Sends status back to phone via MessageClient
 */
class WatchApkReceiveService : WearableListenerService() {

    companion object {
        /** Max APK size: 100 MB -- wear APKs are typically 10-30 MB */
        private const val MAX_APK_SIZE_BYTES = 100L * 1024 * 1024
        /** Timeout for receiving APK bytes from phone */
        private const val RECEIVE_TIMEOUT_MS = 300_000L
        /** APK magic bytes (PK zip header) */
        private val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private const val NOTIFICATION_CHANNEL_ID = "watch_apk_update"
        private const val FOREGROUND_ID = 9002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installMutex = Mutex()
    private val activePushCount = AtomicInteger(0)
    private val foregroundLock = Any()

    override fun onCreate() {
        super.onCreate()
        // Open the timeout store now so [onTimeout], which has only seconds to run, never has
        // to read a prefs file from disk.
        FgsTimeoutReporter.init(applicationContext)
    }

    /**
     * A `startService`/`startForegroundService` delivery, which for this service always carries
     * nothing to do: APK pushes arrive over the GMS channel and land in [onChannelOpened], which
     * does its own foreground promotion around the transfer it starts.
     *
     * It still has to be answered, and only one answer works. This service is exported (the Data
     * Layer dispatches to it by intent filter) and declares `foregroundServiceType="dataSync"`, so
     * anything on the watch can aim a `startForegroundService` at it -- and the platform then
     * requires a matching `startForeground`. Inheriting the default `onStartCommand` misses the
     * 30 s deadline and the process dies with `ForegroundServiceDidNotStartInTimeException`;
     * simply calling `stopSelf` instead is *also* fatal, and faster -- the platform crashes a
     * service that stops while it still owes a promotion. Both were reproduced on the phone side's
     * twin of this service (`WearChatRelayService`) on an Android 16 emulator.
     *
     * So the obligation is discharged the only way that is not fatal: promote, then drop straight
     * back out if no transfer is holding the promotion -- under the same [foregroundLock] a real
     * push takes, which is what keeps this start command from demoting out from under one already
     * under way. `stopSelf` is safe once the promotion is discharged, and does not destroy the
     * service while GMS holds its binding.
     *
     * The promotion is unconditional, unlike [onChannelOpened]'s, which fires on the 0 -> 1
     * transition. [activePushCount] counts transfers, not foreground state, and the two come apart
     * as soon as a promotion is refused: a transfer whose [tryPromoteToForeground] was rejected
     * leaves the counter at 1 with the service still in the background, and a counter-gated start
     * command would then skip the one call that discharges the obligation. `startForeground` on a
     * service that is already foreground just refreshes the same notification id, so promoting
     * every time costs nothing.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        synchronized(foregroundLock) {
            tryPromoteToForeground()
            if (activePushCount.get() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * The app's cumulative `dataSync` foreground-service budget (6 h per 24 h on Wear OS 5+) is
     * spent while an APK transfer was in flight. Stop within the system's few-second window or
     * it throws `RemoteServiceException` and kills the watch process.
     *
     * Dropping the foreground state -- not the cancellation -- is what actually ends the
     * dataSync billing: a blocking `InputStream.read()` inside the transfer cannot be
     * interrupted by cancelling its coroutine, which is what the receive watchdog is for. A
     * half-received APK is never committed to PackageInstaller, and the phone re-pushes on the
     * next update check, so nothing is persisted for resume.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.coroutineContext.cancelChildren()
        synchronized(foregroundLock) {
            activePushCount.set(0)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
        FgsTimeoutReporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_WATCH_APK_RECEIVE,
            startId = startId,
            fgsType = fgsType,
        )
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearDataContract.WATCH_APK_PUSH_CHANNEL) return

        Timber.d("Watch APK push channel opened from phone")
        synchronized(foregroundLock) {
            if (activePushCount.getAndIncrement() == 0) {
                tryPromoteToForeground()
            }
        }
        scope.launch {
            try {
                installMutex.withLock {
                    receiveAndInstallApk(channel)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Watch APK push failed before transfer started")
                withContext(NonCancellable) {
                    sendStatus("error", error = e.message ?: "Setup failed")
                    try {
                        Wearable.getChannelClient(applicationContext)
                            .close(channel).await()
                    } catch (ce: Exception) {
                        Timber.w(ce, "Failed to close channel after setup failure")
                    }
                }
            } finally {
                synchronized(foregroundLock) {
                    // <= 0, not == 0: [onTimeout] zeroes the counter out-of-band while this
                    // transfer is still unwinding, so this decrement can land on an already-zero
                    // counter. An exact-equality test would leave it negative and GMS keeps the
                    // instance bound, so every later APK push would skip tryPromoteToForeground
                    // and transfer (up to 100 MB) with no foreground protection. Same clamp the
                    // phone relay's finishWork has.
                    if (activePushCount.decrementAndGet() <= 0) {
                        activePushCount.set(0)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }
            }
        }
    }

    /**
     * The narrower [ForegroundServiceStarter] catch only classifies the platform's own rejection
     * types; this outer catch is the backstop the pre-GLY-246 code had (channel creation and
     * notification build ran inside the same `catch (e: Exception)` as the promotion call), kept
     * so an unexpected throw here still can't escape a `WearableListenerService` callback with no
     * enclosing try/catch of its own.
     */
    private fun tryPromoteToForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Watch App Update",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Updating watch app")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
            ForegroundServiceStarter.promote(
                this,
                FOREGROUND_ID,
                notification,
                FgsTimeoutReporter.COMPONENT_WATCH_APK_RECEIVE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            Timber.e(e, "Unexpected failure promoting watch APK receive to foreground")
            // Not recordForegroundStartRejected: everything this backstop catches came from the
            // notification setup above, or is an exception type ForegroundServiceStarter does not
            // classify -- neither is the platform refusing the start (PR #44 review).
            FgsTimeoutReporter.recordForegroundSetupFailure(
                FgsTimeoutReporter.COMPONENT_WATCH_APK_RECEIVE,
                e,
            )
        }
    }

    private suspend fun receiveAndInstallApk(channel: ChannelClient.Channel) {
        val tempFile = File.createTempFile("watch-apk-update-", ".apk", cacheDir)
        try {
            val channelClient = Wearable.getChannelClient(this)
            val inputStream = channelClient.getInputStream(channel).await()
            val watchdog = launchTimeoutWatchdog(channelClient, channel)
            try {
                inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        copyWithLimit(input, output, MAX_APK_SIZE_BYTES)
                    }
                }
            } finally {
                watchdog.cancel()
            }

            val fileSize = tempFile.length()
            if (fileSize == 0L) {
                Timber.w("Received empty watch APK, skipping install")
                sendStatus("error", error = "Empty APK received")
                return
            }

            if (!hasValidApkHeader(tempFile)) {
                Timber.w("Received file is not a valid APK (bad magic bytes)")
                sendStatus("error", error = "Invalid APK format")
                return
            }

            Timber.d("Received watch APK: %d bytes", fileSize)

            if (!packageManager.canRequestPackageInstalls()) {
                Timber.w("Cannot request package installs -- permission not granted")
                sendStatus("error", error = "Install permission not granted on watch")
                return
            }

            withContext(NonCancellable) {
                sendStatus("installing")
                installApk(tempFile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to receive/install watch APK")
            withContext(NonCancellable) {
                sendStatus("error", error = e.message ?: "Unknown error")
            }
        } finally {
            tempFile.delete()
            withContext(NonCancellable) {
                try {
                    Wearable.getChannelClient(applicationContext)
                        .close(channel).await()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to close channel")
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(packageName)
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("watch_apk_update", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                session.fsync(out)
            }

            val intent = Intent(applicationContext, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_RESULT
                putExtra("session_id", sessionId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

            Timber.d("Committing PackageInstaller session %d", sessionId)
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    private fun launchTimeoutWatchdog(
        channelClient: ChannelClient,
        channel: ChannelClient.Channel,
    ): Job = scope.launch {
        delay(RECEIVE_TIMEOUT_MS)
        Timber.w("Watch APK receive timed out after %d ms, force-closing channel", RECEIVE_TIMEOUT_MS)
        try {
            channelClient.close(channel).await()
        } catch (e: Exception) {
            Timber.w(e, "Failed to force-close channel on timeout")
        }
    }

    private suspend fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long) {
        val buffer = ByteArray(8192)
        var totalBytes = 0L
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            totalBytes += bytesRead
            currentCoroutineContext().ensureActive()
            if (totalBytes > maxBytes) {
                throw IllegalStateException(
                    "APK exceeds maximum size of ${maxBytes / (1024 * 1024)} MB",
                )
            }
            output.write(buffer, 0, bytesRead)
        }
    }

    private fun hasValidApkHeader(file: File): Boolean {
        if (file.length() < APK_MAGIC.size) return false
        val header = ByteArray(APK_MAGIC.size)
        file.inputStream().use { it.read(header) }
        return header.contentEquals(APK_MAGIC)
    }

    private suspend fun sendStatus(status: String, error: String? = null) {
        try {
            val ctx = applicationContext
            val messageClient = Wearable.getMessageClient(ctx)
            val nodeClient = Wearable.getNodeClient(ctx)
            val nodes = nodeClient.connectedNodes.await()
            val payload = buildString {
                append("status=").appendLine(sanitize(status))
                error?.let { append("error=").appendLine(sanitize(it)) }
            }.toByteArray()

            nodes.forEach { node ->
                messageClient.sendMessage(
                    node.id,
                    WearDataContract.WATCH_APK_PUSH_STATUS_PATH,
                    payload,
                ).await()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to send APK push status to phone")
        }
    }

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('=', '_')

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
