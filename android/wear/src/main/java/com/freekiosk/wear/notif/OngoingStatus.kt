package com.freekiosk.wear.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.freekiosk.wear.R

/**
 * Keeps a low-priority ongoing notification alive so the watchface shows the
 * DenTRIO ongoing-activity indicator — one tap from the clock straight into
 * the app, no launcher hunting. Re-posted on every summary update (also
 * self-heals after a reboot once the first Data Layer event arrives).
 */
object OngoingStatus {

    private const val CHANNEL_STATUS = "status"
    private const val NOTIF_ID_ONGOING = 300

    fun ensure(context: Context, statusText: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val launchIntent = Intent(context, com.freekiosk.wear.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val touchIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(touchIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        OngoingActivity.Builder(context, NOTIF_ID_ONGOING, builder)
            .setStaticIcon(android.R.drawable.ic_menu_recent_history)
            .setTouchIntent(touchIntent)
            .setStatus(Status.forPart(Status.TextPart(statusText)))
            .build()
            .apply(context)

        manager.notify(NOTIF_ID_ONGOING, builder.build())
    }
}
