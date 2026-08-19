package com.glycemicgpt.mobile.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.glycemicgpt.mobile.data.local.AuthTokenStore
import com.glycemicgpt.mobile.data.repository.AlertRepository
import com.glycemicgpt.mobile.data.repository.ChatRepository
import com.glycemicgpt.mobile.service.FgsTimeoutReporter
import com.glycemicgpt.mobile.service.ForegroundServiceStartResult
import com.glycemicgpt.mobile.service.ForegroundServiceStarter
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Receives chat requests and alert dismiss messages from the watch via
 * Wearable MessageClient.
 *
 * Chat requests require a long-running API call (20-60s for LLM inference).
 * The service promotes itself to foreground with a notification during the
 * API call so Android doesn't destroy it before the response arrives.
 */
@AndroidEntryPoint
class WearChatRelayService : WearableListenerService() {

    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var alertRepository: AlertRepository
    @Inject lateinit var wearDataSender: WearDataSender
    @Inject lateinit var authTokenStore: AuthTokenStore
    @Inject lateinit var fgsTimeoutReporter: FgsTimeoutReporter

    // Visible to the unit test so it can launch work after a timeout and prove the scope is still
    // alive -- the difference between cancelChildren() and cancel() in onTimeout.
    @VisibleForTesting
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tracks active foreground work items. Only stop foreground when count hits 0. */
    @VisibleForTesting
    internal val activeWorkCount = AtomicInteger(0)

    /**
     * Serialises each counter mutation with the foreground transition it decides -- the same lock
     * the watch-side receivers hold around their own push counter.
     *
     * [finishWork]'s decrement-then-clamp is two operations on an atomic, not one atomic
     * operation, and the work items are not on one thread: watch messages land on GMS binder
     * threads while [onStartCommand] runs on the main thread. A [startWork] that interleaves
     * between the decrement and the `set(0)` promotes and is then clamped straight back to zero
     * and demoted by the finishing caller, so a 20-60 s chat request runs on with no foreground
     * protection and the process is killable for the whole of it.
     */
    private val foregroundLock = Any()

    /**
     * A `startService`/`startForegroundService` delivery, which for this service always carries
     * nothing to do: watch messages arrive over the GMS binding and land in [onMessageReceived],
     * which does its own foreground promotion around the work it starts.
     *
     * It still has to be answered, and only one answer works. This service is exported (the Data
     * Layer dispatches to it by intent filter) and declares `foregroundServiceType="dataSync"`, so
     * anything on the device can aim a `startForegroundService` at it -- and the platform then
     * requires a matching `startForeground`. Inheriting the default `onStartCommand` misses the
     * 30 s deadline and the process dies with
     * `ForegroundServiceDidNotStartInTimeException`; simply calling `stopSelf` instead is *also*
     * fatal, and faster -- the platform crashes a service that stops while it still owes a
     * promotion. Both were reproduced on an Android 16 emulator while validating this app's
     * background wake-up paths, at 31 s and 1.5 s of process age respectively.
     *
     * So the obligation is discharged the only way that is not fatal: promote, then drop straight
     * back out if nothing else is holding the promotion.
     *
     * The promotion is unconditional here, unlike [startWork]'s, which fires only on the 0 -> 1
     * transition. [activeWorkCount] counts work items, not foreground state, and the two come
     * apart as soon as a promotion is refused: a chat request whose promote returned
     * [ForegroundServiceStartResult.Rejected] leaves the counter at 1 with the service still in
     * the background, and a counter-gated start command would then skip the one call that
     * discharges the obligation -- straight back to the
     * `ForegroundServiceDidNotStartInTimeException` this override exists to prevent. Calling
     * `startForeground` on a service that is already foreground is harmless (it refreshes the
     * same notification id), so promoting every time costs nothing and needs no second flag to
     * track. The *demotion* stays counter-gated, which is what keeps this start command from
     * dropping the foreground state out from under a relay already under way.
     *
     * A promotion the platform refuses leaves the obligation outstanding with nothing the app can
     * do about it -- there is no second way to answer a start command -- but the refusal is at
     * least survived rather than thrown, and the notification-free relay degrades the way
     * [startWork]'s Rejected branch describes.
     *
     * The cost is a silent notification posted and removed within a millisecond, and a sliver of
     * the shared `dataSync` budget. `stopSelf` is safe once the promotion is discharged, and does
     * not disturb a relay already under way: GMS holds a binding while a chat request is being
     * handled, so the service is not destroyed.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        synchronized(foregroundLock) {
            promoteToForeground()
            if (activeWorkCount.get() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearDataContract.CHAT_REQUEST_PATH -> handleChatRequest(messageEvent)
            WearDataContract.ALERT_DISMISS_PATH -> handleAlertDismiss()
            else -> super.onMessageReceived(messageEvent)
        }
    }

    private fun handleAlertDismiss() {
        Timber.d("Received alert dismiss from watch")
        startWork()
        try {
            runBlocking {
                val serverId = alertRepository.getLatestUnacknowledgedServerId()
                if (serverId != null) {
                    // acknowledgeAlert marks the row locally before the server POST, so the
                    // watch's clearAlert below is truthful even when the backend is unreachable
                    // — the alert can't re-fire; the server sync is deferred to the reconcile.
                    alertRepository.acknowledgeAlert(serverId)
                        .onSuccess {
                            Timber.d("Acknowledged alert %s from watch dismiss", serverId)
                        }
                        .onFailure { e ->
                            Timber.w(
                                e,
                                "Alert %s acknowledged locally from watch dismiss; server sync deferred",
                                serverId,
                            )
                        }
                }
                wearDataSender.clearAlert()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to acknowledge alert on phone side")
        } finally {
            finishWork()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleChatRequest(messageEvent: MessageEvent) {
        val requestText = messageEvent.data?.let { String(it, Charsets.UTF_8).trim() } ?: ""
        val sourceNodeId = messageEvent.sourceNodeId

        if (requestText.isEmpty()) {
            Timber.w("Empty chat request received, ignoring")
            serviceScope.launch { sendError(sourceNodeId, "Empty message") }
            return
        }

        if (requestText.length > MAX_MESSAGE_LENGTH) {
            Timber.w("Chat request too long (%d chars), rejecting", requestText.length)
            serviceScope.launch { sendError(sourceNodeId, "Message too long (max $MAX_MESSAGE_LENGTH chars)") }
            return
        }

        // BLE-only mode (GLY-146): the relay's only job is forwarding to the backend chat API,
        // so with no server configured it answers honestly and terminally instead of letting
        // the send fail into a retryable-sounding "Something went wrong". Checked before the
        // foreground promotion — there is no long-running work to protect.
        // onMessageReceived runs on a background binder thread, so the sync read is off-main.
        if (!authTokenStore.isBackendConfigured()) {
            Timber.i("Chat request from watch refused: no backend configured")
            serviceScope.launch { sendError(sourceNodeId, NO_BACKEND_MESSAGE) }
            return
        }

        Timber.d("Received chat request from watch (%d chars)", requestText.length)

        // Promote to foreground so Android doesn't kill the process during the
        // long-running LLM API call (20-60s). Then block the binder thread so
        // GMS doesn't unbind the WearableListenerService before the response
        // arrives. onMessageReceived runs on a background binder thread (not
        // main), so blocking won't cause ANR.
        startWork()
        try {
            runBlocking {
                val result = withTimeoutOrNull(CHAT_API_TIMEOUT_MS) {
                    chatRepository.sendMessage(requestText)
                }

                if (result == null) {
                    Timber.w("Chat API call timed out after %dms", CHAT_API_TIMEOUT_MS)
                    sendError(sourceNodeId, "Request timed out. Try again later.")
                    return@runBlocking
                }

                result
                    .onSuccess { chatResponse ->
                        val responseJson = JSONObject().apply {
                            put("response", chatResponse.response)
                            put("disclaimer", chatResponse.disclaimer)
                        }.toString()

                        try {
                            Wearable.getMessageClient(this@WearChatRelayService)
                                .sendMessage(
                                    sourceNodeId,
                                    WearDataContract.CHAT_RESPONSE_PATH,
                                    responseJson.toByteArray(Charsets.UTF_8),
                                )
                                .await()
                            Timber.d("Sent chat response to watch")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to send chat response to watch")
                        }
                    }
                    .onFailure { error ->
                        Timber.w(error, "Chat request failed")
                        sendError(sourceNodeId, sanitizeErrorMessage(error))
                    }
            }
        } catch (e: Exception) {
            Timber.e(e, "Chat relay exception: %s", e.message)
        } finally {
            finishWork()
        }
    }

    // --- Foreground service lifecycle ---

    /**
     * Promote to foreground before starting long-running work.
     * Uses an atomic counter so concurrent work items (e.g., chat + alert dismiss
     * arriving simultaneously) keep the foreground state until ALL complete.
     */
    @VisibleForTesting
    internal fun startWork() {
        synchronized(foregroundLock) {
            if (activeWorkCount.getAndIncrement() == 0) {
                promoteToForeground()
            }
        }
    }

    /** Demote from foreground when all work items complete. */
    private fun finishWork() {
        synchronized(foregroundLock) {
            if (activeWorkCount.decrementAndGet() <= 0) {
                activeWorkCount.set(0) // clamp to 0
                stopForeground(STOP_FOREGROUND_REMOVE)
                Timber.d("Chat relay returned to background")
            }
        }
    }

    /** Callers hold [foregroundLock]. */
    private fun promoteToForeground() {
        ensureNotificationChannel()
        val notification = buildNotification()
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            null
        }
        val result = ForegroundServiceStarter.promote(
            this,
            NOTIFICATION_ID,
            notification,
            FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY,
            fgsTimeoutReporter,
            foregroundServiceType,
        )
        when (result) {
            ForegroundServiceStartResult.Started -> Timber.d("Chat relay promoted to foreground")
            is ForegroundServiceStartResult.Rejected -> {
                // Android 15 refuses a dataSync promotion once the shared 24 h budget is spent
                // (ForegroundServiceStartNotAllowedException, an IllegalStateException). An
                // uncaught throw here would crash the process and take the pump connection with
                // it, over a watch chat message. Relay the request unprotected instead -- the
                // same trade the watch-side receivers already make.
            }
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Watch Chat Relay",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Processing AI chat request from watch"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Processing watch request")
            .setContentText("Asking AI...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // --- Error handling ---

    private fun sanitizeErrorMessage(error: Throwable): String {
        val raw = error.message ?: "Unknown error"
        return when {
            raw.contains("timeout", ignoreCase = true) -> "Request timed out. Try again later."
            raw.contains("Unable to resolve host", ignoreCase = true) -> "No internet connection."
            raw.contains("401", ignoreCase = true) -> "Session expired. Open phone app to sign in."
            raw.contains("500", ignoreCase = true) -> "Server error. Try again later."
            else -> "Something went wrong. Try again later."
        }
    }

    private suspend fun sendError(nodeId: String, message: String) {
        try {
            Wearable.getMessageClient(this@WearChatRelayService)
                .sendMessage(
                    nodeId,
                    WearDataContract.CHAT_ERROR_PATH,
                    message.toByteArray(Charsets.UTF_8),
                )
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to send error to watch")
        }
    }

    /**
     * The app's shared `dataSync` foreground-service budget is spent while this relay held the
     * foreground state. Stop within the system's few-second window or it throws
     * `RemoteServiceException` and kills the process along with the pump connection.
     *
     * Nothing here blocks or suspends. Nothing is persisted for resume either: a chat request is
     * request-scoped, the watch shows its own timeout, and re-asking is the user's call.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // cancelChildren, not cancel(): GMS keeps a WearableListenerService bound, so stopSelf
        // need not destroy this instance and the next watch message can land on it. Cancelling
        // the scope itself would leave it permanently dead.
        serviceScope.coroutineContext.cancelChildren()
        synchronized(foregroundLock) {
            activeWorkCount.set(0)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
        fgsTimeoutReporter.recordTimeout(
            component = FgsTimeoutReporter.COMPONENT_WEAR_CHAT_RELAY,
            startId = startId,
            fgsType = fgsType,
            resumable = false,
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 500

        /**
         * Honest terminal copy for a chat request in BLE-only mode: names the permanent
         * condition and where to fix it, and deliberately never says "try again" -- retrying
         * cannot help until a server is set up on the phone.
         */
        const val NO_BACKEND_MESSAGE =
            "AI chat needs a GlycemicGPT server — none is set up on your phone."

        private const val CHAT_API_TIMEOUT_MS = 90_000L
        private const val CHANNEL_ID = "watch_chat_relay"
        private const val NOTIFICATION_ID = 3
    }
}
