package com.freekiosk.wear.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.freekiosk.wear.R
import com.freekiosk.wear.model.WatchChatMessage
import com.freekiosk.wear.model.WatchSummary
import com.freekiosk.wear.service.WatchActionReceiver
import androidx.core.app.NotificationCompat

/**
 * Watch notifications: room-chat messages (with 👍 + quick-reply actions)
 * and the "patient waiting while the visit overruns" alert.
 */
object WatchNotifications {

    private const val CHANNEL_CHAT = "chat"
    private const val CHANNEL_ALERTS = "alerts"
    private const val NOTIF_ID_CHAT = 100
    private const val NOTIF_ID_OVERRUN = 200

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CHAT,
                context.getString(R.string.channel_chat),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(true) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            },
        )
    }

    fun showChatMessage(context: Context, message: WatchChatMessage) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val builder = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(message.senderName)
            .setContentText(message.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)

        if (message.id.isNotBlank() && !message.isSystem) {
            builder.addAction(
                0,
                context.getString(R.string.notif_reaction_action),
                actionIntent(context, WatchActionReceiver.ACTION_REACT, 1) {
                    putExtra(WatchActionReceiver.EXTRA_MESSAGE_ID, message.id)
                },
            )
        }
        builder.addAction(
            0,
            context.getString(R.string.quick_reply_1),
            actionIntent(context, WatchActionReceiver.ACTION_QUICK_REPLY, 2) {
                putExtra(
                    WatchActionReceiver.EXTRA_CONTENT,
                    context.getString(R.string.quick_reply_1),
                )
            },
        )
        builder.addAction(
            0,
            context.getString(R.string.quick_reply_2),
            actionIntent(context, WatchActionReceiver.ACTION_QUICK_REPLY, 3) {
                putExtra(
                    WatchActionReceiver.EXTRA_CONTENT,
                    context.getString(R.string.quick_reply_2),
                )
            },
        )

        manager.notify(NOTIF_ID_CHAT, builder.build())
    }

    /** Full-attention alert: the next patient waits while the visit overruns. */
    fun showOverrunAlert(context: Context, summary: WatchSummary) {
        val current = summary.currentVisit ?: return
        val next = summary.nextAppointment ?: return
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val body = context.getString(
            R.string.overrun_alert_body,
            next.patientName,
            next.minutesWaiting ?: 0,
            current.minutesOverrun,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.overrun_alert_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 400))
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIF_ID_OVERRUN, notification)
    }

    private fun actionIntent(
        context: Context,
        action: String,
        requestCode: Int,
        configure: Intent.() -> Unit,
    ): PendingIntent {
        val intent = Intent(context, WatchActionReceiver::class.java)
            .setAction(action)
            .apply(configure)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
