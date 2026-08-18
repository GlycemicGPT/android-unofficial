// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Josh Engelbrecht
package com.glycemicgpt.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.glycemicgpt.mobile.R
import com.glycemicgpt.mobile.presentation.MainActivity

/**
 * Posts a plain, high-priority notification when a rejected foreground-service start leaves
 * [FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION] or [FgsTimeoutReporter.COMPONENT_ALERT_STREAM]
 * not running (GLY-246 review F2). Before this, a rejection on the boot / closed-app path
 * recorded state and fired one telemetry event but told the user nothing -- the phone showed no
 * sign that monitoring or alert delivery was off.
 *
 * Reuses the existing `NotificationCompat`/channel idiom rather than adding a new UX surface.
 * GLY-254 owns the full monitoring-health surface and will supersede this; every call site says
 * so.
 */
object MonitoringDegradedNotifier {

    fun notify(context: Context, component: String) {
        val contentText = when (component) {
            FgsTimeoutReporter.COMPONENT_PUMP_CONNECTION ->
                context.getString(R.string.monitoring_degraded_pump_text)
            FgsTimeoutReporter.COMPONENT_ALERT_STREAM ->
                context.getString(R.string.monitoring_degraded_alert_stream_text)
            else -> return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        ensureChannel(context)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            component.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.monitoring_degraded_title))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        // Plain NotificationManager, not NotificationManagerCompat.notify: the areNotificationsEnabled()
        // guard above already covers the revocable POST_NOTIFICATIONS permission, but lint cannot
        // trace that across the call, and flags NotificationManagerCompat.notify specifically --
        // same reason AlertNotificationManager.showAlertNotification posts through the raw manager.
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(component), notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Monitoring status", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns when the platform refuses to keep pump or alert monitoring running"
            },
        )
    }

    private fun notificationId(component: String) = (component.hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)

    private const val CHANNEL_ID = "monitoring_degraded"
}
