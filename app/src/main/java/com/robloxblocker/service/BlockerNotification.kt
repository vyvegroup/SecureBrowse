package com.robloxblocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.robloxblocker.R
import com.robloxblocker.ui.MainActivity

/**
 * Manages foreground notification for the VPN blocking service.
 */
object BlockerNotification {

    const val CHANNEL_ID = "securebrowse_blocker"
    const val CHANNEL_NAME = "Content Blocker"
    const val NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SecureBrowse content blocking service status"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun createRunningNotification(
        context: Context,
        title: String,
        subtitle: String,
        blockedCount: Int
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(context, BlockerVpnService::class.java).apply {
            action = "STOP_VPN"
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$subtitle — $blockedCount blocked")
            .setSmallIcon(R.drawable.ic_notification_silhouette)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification_silhouette, "Stop", stopPendingIntent)
            .build()
    }

    fun updateBlockedCount(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = createRunningNotification(
            context,
            "SecureBrowse Active",
            "Protecting against restricted domains",
            count
        )
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun createStoppedNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("SecureBrowse Stopped")
            .setContentText("Content blocking is disabled")
            .setSmallIcon(R.drawable.ic_notification_silhouette)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
